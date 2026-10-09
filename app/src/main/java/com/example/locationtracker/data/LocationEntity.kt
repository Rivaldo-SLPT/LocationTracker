package com.example.locationtracker.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "locations")
data class LocationEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val latitude: Double,

    val longitude: Double,

    /*
     * Momento en que se creó el registro.
     */
    val timestamp: Long,

    val accuracy: Float,

    val synced: Boolean = false,

    /*
     * Momento en que el GPS obtuvo realmente esta posición.
     * En un registro normal coincide con timestamp; en uno
     * sin señal nueva es anterior e indica su antigüedad.
     */
    @ColumnInfo(defaultValue = "0")
    val fixTimestamp: Long,

    /*
     * true si en ese minuto no hubo señal nueva y se
     * reutilizó la última posición conocida.
     */
    @ColumnInfo(defaultValue = "0")
    val stale: Boolean = false
)
