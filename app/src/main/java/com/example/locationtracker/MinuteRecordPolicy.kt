package com.example.locationtracker

/*
 * Decide cuándo hay que guardar un registro para mantener
 * "un registro por minuto".
 *
 * No depende de Android: los tiempos se pasan como
 * parámetro, así se puede probar con tests unitarios.
 *
 * - now: reloj monotónico (SystemClock.elapsedRealtime()).
 * - fixTime: momento en que el GPS obtuvo la posición.
 */
class MinuteRecordPolicy(
    private val intervalMillis: Long,
    private val gracePeriodMillis: Long
) {

    /*
     * Timestamp de la última posición real guardada.
     */
    var lastFixTime = 0L
        private set

    /*
     * Momento a partir del cual, si no ha llegado señal
     * nueva, hay que guardar un registro sin señal.
     */
    var nextStaleRecordAt = Long.MAX_VALUE
        private set

    fun start(now: Long) {

        nextStaleRecordAt =
            now + intervalMillis + gracePeriodMillis
    }

    fun stop() {

        nextStaleRecordAt = Long.MAX_VALUE
    }

    /*
     * El proveedor a veces vuelve a entregar una ubicación
     * que ya recibimos (mismo timestamp): no es nueva.
     */
    fun isNewFix(fixTime: Long): Boolean {

        return fixTime > lastFixTime
    }

    /*
     * Hay señal nueva: el siguiente registro sin señal solo
     * se crea si pasa un minuto (más un margen, porque el GPS
     * suele entregar cada ~61 s) sin recibir otra ubicación.
     */
    fun onFreshLocation(fixTime: Long, now: Long) {

        lastFixTime =
            maxOf(lastFixTime, fixTime)

        nextStaleRecordAt =
            now + intervalMillis + gracePeriodMillis
    }

    fun shouldRecordStale(now: Long): Boolean {

        return now >= nextStaleRecordAt
    }

    /*
     * Mientras siga sin señal, un registro por minuto.
     */
    fun onStaleRecorded(now: Long) {

        nextStaleRecordAt =
            now + intervalMillis
    }
}
