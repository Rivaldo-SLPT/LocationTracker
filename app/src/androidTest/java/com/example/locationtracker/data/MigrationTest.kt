package com.example.locationtracker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    // Nombre propio: no toca la base de datos real de la app.
    private val testDbName = "migration-test.db"

    private val context: Context =
        ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context.deleteDatabase(testDbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
    }

    /*
     * Crea la base de datos tal como la dejaba la versión 1
     * de la app (sin fixTimestamp ni stale).
     */
    private fun createVersion1Database() {

        val db =
            SQLiteDatabase.openOrCreateDatabase(
                context.getDatabasePath(testDbName),
                null
            )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `locations` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`latitude` REAL NOT NULL, " +
                "`longitude` REAL NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`accuracy` REAL NOT NULL, " +
                "`synced` INTEGER NOT NULL)"
        )

        listOf(
            Triple(1_000L, 25.5f, 1),
            Triple(2_000L, 100f, 0)
        ).forEach { (timestamp, accuracy, synced) ->

            db.insert(
                "locations",
                null,
                ContentValues().apply {
                    put("latitude", -13.52)
                    put("longitude", -71.95)
                    put("timestamp", timestamp)
                    put("accuracy", accuracy)
                    put("synced", synced)
                }
            )
        }

        db.version = 1
        db.close()
    }

    @Test
    fun migrate1To2_keepsDataAndFillsNewColumns() = runBlocking {

        createVersion1Database()

        val database =
            Room.databaseBuilder(
                context,
                LocationDatabase::class.java,
                testDbName
            )
                .addMigrations(LocationDatabase.MIGRATION_1_2)
                .build()

        try {

            val dao = database.locationDao()

            // Room valida el esquema al abrir: si la migración no
            // coincide con LocationEntity, esta llamada falla.
            val locations =
                dao.getAllLocations().sortedBy { it.timestamp }

            assertEquals(2, locations.size)

            locations.forEach {
                assertEquals(it.timestamp, it.fixTimestamp)
                assertFalse(it.stale)
            }

            assertEquals(listOf(true, false), locations.map { it.synced })
            assertEquals(25.5f, locations[0].accuracy)

            // Los registros nuevos siguen funcionando tras migrar.
            dao.insertLocation(
                locations[0].copy(id = 0, timestamp = 3_000, stale = true)
            )

            assertEquals(3, dao.getAllLocations().size)

        } finally {

            database.close()
        }
    }
}
