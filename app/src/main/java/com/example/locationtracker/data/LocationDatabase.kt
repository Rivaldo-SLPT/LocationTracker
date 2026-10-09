package com.example.locationtracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [LocationEntity::class],
    version = 2,
    exportSchema = false
)
abstract class LocationDatabase : RoomDatabase() {

    abstract fun locationDao(): LocationDao

    companion object {

        @Volatile
        private var INSTANCE: LocationDatabase? = null

        /*
         * Versión 1 -> 2: añade fixTimestamp y stale.
         * Los registros existentes son todos posiciones
         * reales, así que fixTimestamp = timestamp.
         */
        internal val MIGRATION_1_2 =
            object : Migration(1, 2) {

                override fun migrate(db: SupportSQLiteDatabase) {

                    db.execSQL(
                        "ALTER TABLE locations ADD COLUMN fixTimestamp INTEGER NOT NULL DEFAULT 0"
                    )

                    db.execSQL(
                        "ALTER TABLE locations ADD COLUMN stale INTEGER NOT NULL DEFAULT 0"
                    )

                    db.execSQL(
                        "UPDATE locations SET fixTimestamp = timestamp"
                    )
                }
            }

        fun getDatabase(context: Context): LocationDatabase {

            /*
             * Doble comprobación: dentro del bloque synchronized
             * volvemos a mirar INSTANCE, porque otro hilo pudo
             * haberla creado mientras esperábamos el bloqueo.
             */
            return INSTANCE ?: synchronized(this) {

                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocationDatabase::class.java,
                    "location_tracker_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance ->

                        INSTANCE = instance
                    }
            }
        }
    }
}
