package com.example.locationtracker.worker

import com.example.locationtracker.data.LocationEntity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/*
 * Resultado de intentar subir un registro.
 */
enum class UploadResult {

    // AWS respondió 2xx.
    SUCCESS,

    // Error de red, timeout, 408, 429 o 5xx:
    // tiene sentido volver a intentarlo más tarde.
    TRANSIENT_ERROR,

    // Resto de 4xx: el servidor rechaza el registro y
    // repetir la misma petición no lo va a arreglar.
    REJECTED
}

/*
 * Sube un registro al servidor. Es una interfaz para que
 * los tests puedan sustituirla por una versión falsa.
 */
fun interface LocationUploader {

    fun upload(location: LocationEntity): UploadResult
}

class HttpLocationUploader(
    private val deviceId: String,
    private val apiUrl: String = API_URL
) : LocationUploader {

    companion object {

        private const val API_URL =
            "https://jd5lslcf71.execute-api.us-east-2.amazonaws.com/locations"

        fun classifyResponseCode(responseCode: Int): UploadResult {

            return when (responseCode) {

                in 200..299 ->
                    UploadResult.SUCCESS

                408, 429 ->
                    UploadResult.TRANSIENT_ERROR

                in 400..499 ->
                    UploadResult.REJECTED

                else ->
                    UploadResult.TRANSIENT_ERROR
            }
        }
    }

    /*
     * Crea el mismo JSON que probamos anteriormente
     * con API Gateway.
     */
    fun buildPayload(location: LocationEntity): JSONObject {

        return JSONObject().apply {

            put(
                "deviceId",
                deviceId
            )

            put(
                "timestamp",
                location.timestamp
            )

            put(
                "latitude",
                location.latitude
            )

            put(
                "longitude",
                location.longitude
            )

            put(
                "accuracy",
                location.accuracy.toDouble()
            )

            // Momento en que el GPS obtuvo la posición.
            put(
                "fixTimestamp",
                location.fixTimestamp
            )

            // true si ese minuto no hubo señal nueva y se
            // reutilizó la última posición conocida.
            put(
                "stale",
                location.stale
            )
        }
    }

    override fun upload(location: LocationEntity): UploadResult {

        var connection: HttpURLConnection? = null

        return try {

            connection =
                URL(apiUrl).openConnection() as HttpURLConnection

            connection.requestMethod =
                "POST"

            connection.connectTimeout =
                15_000

            connection.readTimeout =
                15_000

            connection.doOutput =
                true

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            // Enviar JSON.
            connection.outputStream.use { outputStream ->

                outputStream.write(
                    buildPayload(location)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                )
            }

            // Leer código HTTP.
            val responseCode =
                connection.responseCode

            println(
                "SyncWorker: AWS respondió HTTP $responseCode"
            )

            classifyResponseCode(responseCode)

        } catch (exception: Exception) {

            exception.printStackTrace()

            UploadResult.TRANSIENT_ERROR

        } finally {

            connection?.disconnect()
        }
    }
}
