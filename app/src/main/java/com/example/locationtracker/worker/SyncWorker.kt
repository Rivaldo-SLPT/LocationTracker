package com.example.locationtracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.locationtracker.DeviceIdProvider
import com.example.locationtracker.data.LocationDao
import com.example.locationtracker.data.LocationDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val locationDao: LocationDao,
    private val uploader: LocationUploader
) : CoroutineWorker(appContext, workerParams) {

    /*
     * Constructor que usa WorkManager: base de datos real
     * y envío HTTP real. Los tests usan el otro constructor
     * para inyectar una base de datos en memoria y un
     * LocationUploader falso.
     */
    constructor(
        appContext: Context,
        workerParams: WorkerParameters
    ) : this(
        appContext,
        workerParams,
        LocationDatabase.getDatabase(appContext).locationDao(),
        HttpLocationUploader(
            deviceId = DeviceIdProvider.getDeviceId(appContext)
        )
    )

    override suspend fun doWork(): Result {

        return withContext(Dispatchers.IO) {

            try {

                /*
                 * Registros que el servidor rechazó en esta
                 * ejecución. Se quedan con synced = false, pero
                 * no bloquean al resto ni provocan un bucle.
                 */
                val rejectedIds =
                    mutableSetOf<Long>()

                /*
                 * Repetimos la consulta hasta que no quede nada
                 * pendiente. Así también subimos las ubicaciones
                 * que el servicio inserte mientras este Worker
                 * ya se está ejecutando.
                 */
                while (true) {

                    val pendingLocations =
                        locationDao
                            .getUnsyncedLocations()
                            .filterNot { it.id in rejectedIds }

                    println(
                        "SyncWorker: ${pendingLocations.size} registros pendientes"
                    )

                    if (pendingLocations.isEmpty()) {
                        break
                    }

                    for (location in pendingLocations) {

                        when (uploader.upload(location)) {

                            UploadResult.SUCCESS -> {

                                // Solo marcamos como sincronizado
                                // después de que AWS responda correctamente.
                                locationDao.markAsSynced(
                                    location.id
                                )

                                println(
                                    "SyncWorker: registro ${location.id} sincronizado"
                                )
                            }

                            UploadResult.REJECTED -> {

                                rejectedIds += location.id

                                println(
                                    "SyncWorker: registro ${location.id} rechazado por el servidor"
                                )
                            }

                            UploadResult.TRANSIENT_ERROR -> {

                                println(
                                    "SyncWorker: fallo temporal al sincronizar registro ${location.id}"
                                )

                                // Dejamos synced = false.
                                // WorkManager lo intentará nuevamente
                                // con espera exponencial.
                                return@withContext Result.retry()
                            }
                        }
                    }
                }

                Result.success()

            } catch (exception: Exception) {

                exception.printStackTrace()

                Result.retry()
            }
        }
    }
}
