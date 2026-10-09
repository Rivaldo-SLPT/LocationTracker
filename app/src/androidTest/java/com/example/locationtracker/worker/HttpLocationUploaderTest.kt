package com.example.locationtracker.worker

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.locationtracker.data.LocationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HttpLocationUploaderTest {

    @Test
    fun buildPayload_containsAllFields() {

        val uploader =
            HttpLocationUploader(
                apiUrl = "https://example.invalid/locations",
                deviceId = "test-device"
            )

        val payload =
            uploader.buildPayload(
                LocationEntity(
                    latitude = -13.523783,
                    longitude = -71.957393,
                    timestamp = 1_758_570_000_000,
                    accuracy = 25.5f,
                    fixTimestamp = 1_758_569_940_000,
                    stale = true
                )
            )

        assertEquals("test-device", payload.getString("deviceId"))
        assertEquals(1_758_570_000_000, payload.getLong("timestamp"))
        assertEquals(-13.523783, payload.getDouble("latitude"), 0.0)
        assertEquals(-71.957393, payload.getDouble("longitude"), 0.0)
        assertEquals(25.5, payload.getDouble("accuracy"), 0.0)
        assertEquals(1_758_569_940_000, payload.getLong("fixTimestamp"))
        assertTrue(payload.getBoolean("stale"))
    }

    @Test
    fun unreachableServer_isTransientError() {

        // Puerto cerrado en localhost: la conexión falla al instante.
        val uploader =
            HttpLocationUploader(
                apiUrl = "http://127.0.0.1:1/locations",
                deviceId = "test-device"
            )

        val result =
            uploader.upload(
                LocationEntity(
                    latitude = 0.0,
                    longitude = 0.0,
                    timestamp = 1,
                    accuracy = 1f,
                    fixTimestamp = 1
                )
            )

        assertEquals(UploadResult.TRANSIENT_ERROR, result)
    }
}
