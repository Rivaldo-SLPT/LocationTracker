package com.example.locationtracker.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/*
 * Centraliza cómo se pide a WorkManager que sincronice.
 */
object SyncScheduler {

    /*
     * Nombre único para la sincronización inmediata.
     *
     * Evita crear muchos SyncWorker iguales si llegan
     * varias ubicaciones antes de que termine una sincronización.
     */
    private const val SYNC_WORK_NAME = "location_sync_work"

    private const val PERIODIC_SYNC_WORK_NAME = "location_periodic_sync_work"

    /*
     * NetworkType.CONNECTED hace que el Worker solamente
     * pueda ejecutarse cuando exista conexión de red.
     */
    private val networkConstraints =
        Constraints.Builder()
            .setRequiredNetworkType(
                NetworkType.CONNECTED
            )
            .build()

    /*
     * KEEP: si ya hay una sincronización pendiente o
     * ejecutándose, no creamos otra. El SyncWorker sigue
     * subiendo hasta que no quedan registros pendientes,
     * así que también recoge los que se insertan mientras
     * está trabajando.
     */
    fun requestSync(context: Context) {

        val syncRequest =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraints)
                .build()

        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                SYNC_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                syncRequest
            )
    }

    /*
     * Red de seguridad: una sincronización cada 15 minutos
     * (el mínimo que permite WorkManager). Garantiza que
     * ningún registro quede pendiente indefinidamente,
     * incluso después de detener el rastreo.
     */
    fun schedulePeriodicSync(context: Context) {

        val periodicRequest =
            PeriodicWorkRequestBuilder<SyncWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(networkConstraints)
                .build()

        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(
                PERIODIC_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
    }
}
