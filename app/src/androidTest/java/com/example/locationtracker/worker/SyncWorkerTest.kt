package com.example.locationtracker.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.locationtracker.data.LocationDao
import com.example.locationtracker.data.LocationDatabase
import com.example.locationtracker.data.LocationEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncWorkerTest {

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    private lateinit var database: LocationDatabase
    private lateinit var dao: LocationDao

    @Before
    fun setUp() {

        // Base de datos en memoria: no toca los datos reales de la app.
        database =
            Room.inMemoryDatabaseBuilder(
                context,
                LocationDatabase::class.java
            ).build()

        dao = database.locationDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun location(timestamp: Long) =
        LocationEntity(
            latitude = -13.52,
            longitude = -71.95,
            timestamp = timestamp,
            accuracy = 10f,
            fixTimestamp = timestamp
        )

    private fun insert(vararg timestamps: Long) = runBlocking {
        dao.insertLocations(timestamps.map { location(it) })
    }

    /*
     * Crea el SyncWorker con la base de datos en memoria y
     * un uploader falso (sin llamadas reales a AWS).
     */
    private fun runWorker(uploader: LocationUploader): ListenableWorker.Result {

        val worker =
            TestListenableWorkerBuilder<SyncWorker>(context)
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters
                        ) = SyncWorker(appContext, workerParameters, dao, uploader)
                    }
                )
                .build()

        return runBlocking { worker.doWork() }
    }

    private fun pendingTimestamps() = runBlocking {
        dao.getUnsyncedLocations().map { it.timestamp }
    }

    @Test
    fun nothingPending_succeedsWithoutUploading() {

        var calls = 0

        val result = runWorker { calls++; UploadResult.SUCCESS }

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, calls)
    }

    @Test
    fun allUploaded_marksEverythingSynced() {

        insert(1_000, 2_000, 3_000)

        val uploaded = mutableListOf<Long>()

        val result = runWorker { uploaded += it.timestamp; UploadResult.SUCCESS }

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), uploaded)
        assertTrue(pendingTimestamps().isEmpty())
    }

    @Test
    fun transientError_stopsAndRetriesKeepingRemainingPending() {

        insert(1_000, 2_000, 3_000)

        val result =
            runWorker {
                if (it.timestamp == 2_000L) UploadResult.TRANSIENT_ERROR
                else UploadResult.SUCCESS
            }

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(listOf(2_000L, 3_000L), pendingTimestamps())
    }

    @Test
    fun rejectedRecord_doesNotBlockOthersNorLoopForever() {

        insert(1_000, 2_000, 3_000)

        var rejectedCalls = 0

        val result =
            runWorker {
                if (it.timestamp == 2_000L) {
                    rejectedCalls++
                    UploadResult.REJECTED
                } else {
                    UploadResult.SUCCESS
                }
            }

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, rejectedCalls)
        assertEquals(listOf(2_000L), pendingTimestamps())
    }

    @Test
    fun locationInsertedDuringSync_isUploadedInSameRun() {

        insert(1_000)

        val uploaded = mutableListOf<Long>()

        val result =
            runWorker {
                uploaded += it.timestamp

                // Simula que el servicio guarda una ubicación nueva
                // mientras el Worker está subiendo.
                if (it.timestamp == 1_000L) insert(2_000)

                UploadResult.SUCCESS
            }

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(1_000L, 2_000L), uploaded)
        assertTrue(pendingTimestamps().isEmpty())
    }

    @Test
    fun uploaderException_isRetried() {

        insert(1_000)

        val result = runWorker { throw IllegalStateException("fallo") }

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(listOf(1_000L), pendingTimestamps())
    }
}
