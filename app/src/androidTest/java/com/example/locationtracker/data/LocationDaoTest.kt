package com.example.locationtracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationDaoTest {

    private lateinit var database: LocationDatabase
    private lateinit var dao: LocationDao

    @Before
    fun setUp() {

        // Base de datos en memoria: no toca los datos reales de la app.
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                LocationDatabase::class.java
            ).build()

        dao = database.locationDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun location(
        timestamp: Long,
        stale: Boolean = false,
        synced: Boolean = false
    ) = LocationEntity(
        latitude = -13.52,
        longitude = -71.95,
        timestamp = timestamp,
        accuracy = 10f,
        synced = synced,
        fixTimestamp = timestamp,
        stale = stale
    )

    @Test
    fun insertLocations_updatesCounters() = runBlocking {

        dao.insertLocations(
            listOf(
                location(1_000),
                location(2_000, stale = true),
                location(3_000, synced = true)
            )
        )

        assertEquals(3, dao.observeLocationCount().first())
        assertEquals(2, dao.observeUnsyncedLocationCount().first())
        assertEquals(1, dao.observeStaleLocationCount().first())
    }

    @Test
    fun getUnsyncedLocations_returnsOldestFirst() = runBlocking {

        dao.insertLocations(
            listOf(
                location(3_000),
                location(1_000),
                location(2_000, synced = true)
            )
        )

        val pending = dao.getUnsyncedLocations()

        assertEquals(listOf(1_000L, 3_000L), pending.map { it.timestamp })
    }

    @Test
    fun markAsSynced_removesFromPending() = runBlocking {

        val id = dao.insertLocation(location(1_000))

        dao.markAsSynced(id)

        assertTrue(dao.getUnsyncedLocations().isEmpty())
        assertEquals(0, dao.observeUnsyncedLocationCount().first())
        assertEquals(1, dao.observeLocationCount().first())
    }

    @Test
    fun staleFlagAndFixTimestamp_arePersisted() = runBlocking {

        dao.insertLocation(
            location(10_000).copy(fixTimestamp = 4_000, stale = true)
        )

        val saved = dao.getAllLocations().single()

        assertTrue(saved.stale)
        assertEquals(4_000, saved.fixTimestamp)
        assertFalse(saved.synced)
    }

    @Test
    fun insertNewLocations_skipsFixesAlreadySaved() = runBlocking {

        // Primera sesión de rastreo guarda la posición de las 1_000.
        assertEquals(1, dao.insertNewLocations(listOf(location(1_000))))

        // Al reiniciar el rastreo, el proveedor entrega otra vez la
        // misma posición en caché junto con una nueva.
        val inserted =
            dao.insertNewLocations(
                listOf(location(1_000), location(2_000), location(2_000))
            )

        assertEquals(1, inserted)
        assertEquals(listOf(1_000L, 2_000L), dao.getAllLocations().map { it.fixTimestamp }.sorted())
    }

    @Test
    fun insertNewLocations_alwaysKeepsStaleRecords() = runBlocking {

        dao.insertNewLocations(listOf(location(1_000)))

        // Dos minutos sin señal: reutilizan la posición de las 1_000.
        dao.insertNewLocations(listOf(location(61_000).copy(fixTimestamp = 1_000, stale = true)))
        dao.insertNewLocations(listOf(location(121_000).copy(fixTimestamp = 1_000, stale = true)))

        assertEquals(3, dao.observeLocationCount().first())
        assertEquals(2, dao.observeStaleLocationCount().first())
    }
}
