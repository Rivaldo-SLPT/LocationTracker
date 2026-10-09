package com.example.locationtracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {

    @Insert
    suspend fun insertLocation(location: LocationEntity): Long

    @Insert
    suspend fun insertLocations(locations: List<LocationEntity>)

    @Query("SELECT MAX(fixTimestamp) FROM locations WHERE stale = 0")
    suspend fun getLatestFreshFixTimestamp(): Long?

    /*
     * Inserta solo las posiciones reales que no estén ya
     * guardadas. El proveedor puede volver a entregar una
     * posición en caché, por ejemplo al reiniciar el rastreo.
     *
     * Los registros stale siempre se insertan.
     * Devuelve cuántos registros se insertaron.
     */
    @Transaction
    suspend fun insertNewLocations(locations: List<LocationEntity>): Int {

        val latestFix =
            getLatestFreshFixTimestamp() ?: 0L

        val newLocations =
            locations
                .filter { it.stale || it.fixTimestamp > latestFix }
                .distinctBy { if (it.stale) it.timestamp to true else it.fixTimestamp to false }

        insertLocations(newLocations)

        return newLocations.size
    }

    @Query("SELECT * FROM locations ORDER BY timestamp DESC")
    suspend fun getAllLocations(): List<LocationEntity>

    @Query("SELECT * FROM locations WHERE synced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedLocations(): List<LocationEntity>

    /*
     * Los contadores se exponen como Flow: Room emite un
     * valor nuevo cada vez que cambia la tabla, así la
     * interfaz no necesita consultar en bucle.
     */
    @Query("SELECT COUNT(*) FROM locations")
    fun observeLocationCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM locations WHERE synced = 0")
    fun observeUnsyncedLocationCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM locations WHERE stale = 1")
    fun observeStaleLocationCount(): Flow<Int>

    @Query("UPDATE locations SET synced = 1 WHERE id = :locationId")
    suspend fun markAsSynced(locationId: Long)
}
