package com.example.locationtracker.worker

import com.example.locationtracker.worker.HttpLocationUploader.Companion.classifyResponseCode
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseClassificationTest {

    @Test
    fun successCodes() {

        listOf(200, 201, 202, 204, 299).forEach {
            assertEquals("HTTP $it", UploadResult.SUCCESS, classifyResponseCode(it))
        }
    }

    @Test
    fun transientCodes_areRetried() {

        listOf(408, 429, 500, 502, 503, 504).forEach {
            assertEquals("HTTP $it", UploadResult.TRANSIENT_ERROR, classifyResponseCode(it))
        }
    }

    @Test
    fun permanentClientErrors_areRejected() {

        listOf(400, 401, 403, 404, 413, 422).forEach {
            assertEquals("HTTP $it", UploadResult.REJECTED, classifyResponseCode(it))
        }
    }

    @Test
    fun unexpectedCodes_areTreatedAsTransient() {

        listOf(-1, 0, 100, 301, 302, 304).forEach {
            assertEquals("HTTP $it", UploadResult.TRANSIENT_ERROR, classifyResponseCode(it))
        }
    }
}
