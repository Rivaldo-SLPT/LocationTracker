package com.example.locationtracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import com.example.locationtracker.data.LocationDatabase
import com.example.locationtracker.data.LocationEntity
import com.example.locationtracker.worker.SyncScheduler
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class LocationTrackingService : Service() {

    companion object {

        const val ACTION_START = "ACTION_START_TRACKING"
        const val ACTION_STOP = "ACTION_STOP_TRACKING"

        /*
         * Estas preferencias guardan si el usuario QUIERE
         * rastrear. Solo se usan para reanudar el rastreo
         * si Android reinicia el servicio (START_STICKY).
         */
        private const val PREFS_NAME = "location_tracker_preferences"
        private const val KEY_TRACKING_ACTIVE = "tracking_active"

        private const val CHANNEL_ID = "location_tracking_channel"
        private const val NOTIFICATION_ID = 1001

        // 30 segundos
        private const val LOCATION_INTERVAL = 30_000L

        /*
         * Margen de espera antes de dar un intervalo por "sin señal".
         * El GPS suele entregar algo después del intervalo, así que sin margen
         * se crearían registros sin señal innecesarios.
         */
        private const val STALE_GRACE_PERIOD = 15_000L

        // Cada cuánto se comprueba si falta el registro del intervalo.
        private const val STALE_CHECK_INTERVAL = 5_000L

        // Tiempo máximo del wake lock si no se renueva.
        private const val WAKE_LOCK_TIMEOUT = 2 * 60_000L

        /*
         * Estado REAL del rastreo, en memoria.
         *
         * Si Android mata el proceso, este valor vuelve a
         * false automáticamente, por lo que la interfaz
         * nunca muestra "activo" con el servicio muerto.
         */
        private val _isTracking =
            MutableStateFlow(false)

        val isTracking: StateFlow<Boolean> =
            _isTracking.asStateFlow()
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var locationCallback: LocationCallback

    private var locationUpdatesStarted = false

    /*
     * Decide cuándo toca un registro sin señal y descarta
     * ubicaciones repetidas. Solo se usa desde el hilo
     * principal (callback y temporizador).
     */
    private val recordPolicy =
        MinuteRecordPolicy(
            intervalMillis = LOCATION_INTERVAL,
            gracePeriodMillis = STALE_GRACE_PERIOD
        )

    /*
     * Última posición real recibida; se reutiliza en los
     * minutos en que el GPS no consigue señal.
     */
    private var lastKnownLocation: Location? = null

    private var staleRecordJob: Job? = null

    /*
     * Con la pantalla apagada la CPU se duerme y el temporizador
     * de registros sin señal se congela (se observaron huecos de
     * ~90 s). Un wake lock parcial mantiene la CPU despierta
     * mientras se rastrea; la pantalla sí puede apagarse.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )

    /*
     * Las escrituras se hacen de una en una y en orden, para
     * que la comprobación de duplicados de Room no compita
     * con otra inserción simultánea.
     */
    private val databaseDispatcher =
        Dispatchers.IO.limitedParallelism(1)

    override fun onCreate() {
        super.onCreate()

        fusedLocationClient =
            LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()

        createLocationCallback()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_START -> {
                startTracking()
            }

            ACTION_STOP -> {
                stopTracking()
            }

            /*
             * intent == null: Android ha reiniciado el servicio
             * tras matar el proceso (START_STICKY). Reanudamos
             * solo si el usuario no había detenido el rastreo.
             */
            null -> {

                if (isTrackingRequested()) {
                    startTracking()
                } else {
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    private fun startTracking() {

        /*
         * Evitamos registrar dos veces las actualizaciones
         * si por alguna razón llega ACTION_START nuevamente
         * mientras el servicio ya está rastreando.
         */
        if (locationUpdatesStarted) {
            return
        }

        /*
         * Comprobamos los permisos ANTES de pasar a primer plano:
         * desde Android 14, un servicio de tipo "location" sin
         * permiso de ubicación lanza SecurityException.
         *
         * MainActivity solo arranca el servicio con el permiso
         * concedido, así que este caso solo ocurre si el usuario
         * lo revoca y Android reinicia el servicio.
         */
        if (!hasLocationPermission()) {

            abortTracking()

            return
        }

        /*
         * El servicio pasa a primer plano.
         *
         * Puede fallar si Android no permite iniciar un servicio
         * en primer plano en este momento (por ejemplo, al
         * reiniciarse desde segundo plano en Android 12+).
         */
        try {

            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createNotification(),
                foregroundServiceType()
            )

            val locationRequest =
                LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    LOCATION_INTERVAL
                )
                    .setMinUpdateIntervalMillis(
                        LOCATION_INTERVAL
                    )
                    .build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                mainLooper
            )

        } catch (exception: SecurityException) {

            // Permiso de ubicación revocado justo ahora.
            exception.printStackTrace()

            abortTracking()

            return

        } catch (exception: Exception) {

            exception.printStackTrace()

            abortTracking()

            return
        }

        locationUpdatesStarted = true

        _isTracking.value = true

        acquireWakeLock()

        startStaleRecordTicker()

        saveTrackingRequested(true)

        /*
         * Red de seguridad para que ningún registro
         * quede pendiente indefinidamente.
         */
        SyncScheduler.schedulePeriodicSync(applicationContext)
    }

    /*
     * El tipo de servicio en primer plano existe desde
     * Android 10 (API 29); en versiones anteriores se pasa 0.
     */
    private fun foregroundServiceType(): Int {

        return if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.Q
        ) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
    }

    private fun hasLocationPermission(): Boolean {

        val finePermission =
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarsePermission =
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return finePermission || coarsePermission
    }

    /*
     * No se pudo iniciar el rastreo: dejamos todo
     * en estado "detenido" y cerramos el servicio.
     */
    private fun abortTracking() {

        _isTracking.value = false

        saveTrackingRequested(false)

        ServiceCompat.stopForeground(
            this,
            ServiceCompat.STOP_FOREGROUND_REMOVE
        )

        stopSelf()
    }

    private fun createLocationCallback() {

        locationCallback =
            object : LocationCallback() {

                override fun onLocationResult(
                    locationResult: LocationResult
                ) {

                    /*
                     * Android puede entregar varias ubicaciones
                     * juntas (batching). Guardamos todas, no solo
                     * la última.
                     *
                     * El proveedor a veces vuelve a entregar una
                     * ubicación que ya recibimos (mismo timestamp):
                     * la descartamos para no guardar duplicados.
                     */
                    val newLocations =
                        locationResult.locations
                            .filter { recordPolicy.isNewFix(it.time) }
                            .distinctBy { it.time }

                    if (newLocations.isEmpty()) {
                        return
                    }

                    val latestLocation =
                        newLocations.maxBy { it.time }

                    lastKnownLocation =
                        latestLocation

                    recordPolicy.onFreshLocation(
                        fixTime = latestLocation.time,
                        now = SystemClock.elapsedRealtime()
                    )

                    saveLocations(
                        newLocations.map { location ->

                            LocationEntity(
                                latitude = location.latitude,
                                longitude = location.longitude,
                                timestamp = location.time,
                                accuracy = location.accuracy,
                                fixTimestamp = location.time,
                                stale = false
                            )
                        }
                    )
                }
            }
    }

    /*
     * Comprueba periódicamente si ha pasado un intervalo sin
     * señal nueva. En ese caso guarda la última posición
     * conocida marcada como stale, para que siempre haya
     * un registro por intervalo.
     */
    private fun startStaleRecordTicker() {

        recordPolicy.start(
            SystemClock.elapsedRealtime()
        )

        staleRecordJob?.cancel()

        staleRecordJob =
            serviceScope.launch(Dispatchers.Main) {

                while (isActive) {

                    delay(STALE_CHECK_INTERVAL.milliseconds)

                    renewWakeLock()

                    val now =
                        SystemClock.elapsedRealtime()

                    if (recordPolicy.shouldRecordStale(now)) {

                        saveStaleLocation()

                        recordPolicy.onStaleRecorded(now)
                    }
                }
            }
    }

    private fun saveStaleLocation() {

        val location =
            lastKnownLocation

        if (location != null) {

            saveLocations(
                listOf(createStaleEntity(location))
            )

            return
        }

        /*
         * Aún no hemos recibido ninguna ubicación en esta
         * sesión: usamos la última que conozca el sistema.
         */
        try {

            fusedLocationClient.lastLocation
                .addOnSuccessListener { systemLocation ->

                    if (systemLocation != null && locationUpdatesStarted) {

                        lastKnownLocation = systemLocation

                        saveLocations(
                            listOf(createStaleEntity(systemLocation))
                        )
                    }
                }

        } catch (exception: SecurityException) {

            exception.printStackTrace()
        }
    }

    private fun createStaleEntity(
        location: Location
    ): LocationEntity {

        return LocationEntity(
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = System.currentTimeMillis(),
            accuracy = location.accuracy,
            fixTimestamp = location.time,
            stale = true
        )
    }

    /*
     * Guarda PRIMERO en Room y después pide sincronizar.
     *
     * Aunque no haya Internet, el guardado local sigue
     * funcionando; WorkManager esperará a tener conexión.
     */
    private fun saveLocations(
        locationEntities: List<LocationEntity>
    ) {

        serviceScope.launch(databaseDispatcher) {

            val inserted =
                LocationDatabase
                    .getDatabase(applicationContext)
                    .locationDao()
                    .insertNewLocations(locationEntities)

            if (inserted > 0) {
                SyncScheduler.requestSync(applicationContext)
            }
        }
    }

    private fun stopTracking() {

        removeLocationUpdates()

        /*
         * El usuario detuvo el rastreo: no debe
         * reanudarse si Android reinicia el servicio.
         */
        saveTrackingRequested(false)

        /*
         * Intentamos subir lo que quede pendiente
         * antes de terminar.
         */
        SyncScheduler.requestSync(applicationContext)

        ServiceCompat.stopForeground(
            this,
            ServiceCompat.STOP_FOREGROUND_REMOVE
        )

        stopSelf()
    }

    private fun removeLocationUpdates() {

        staleRecordJob?.cancel()

        staleRecordJob = null

        recordPolicy.stop()

        releaseWakeLock()

        if (locationUpdatesStarted) {

            fusedLocationClient.removeLocationUpdates(
                locationCallback
            )

            locationUpdatesStarted = false
        }

        _isTracking.value = false
    }

    private fun acquireWakeLock() {

        if (wakeLock?.isHeld == true) {
            return
        }

        wakeLock =
            getSystemService(PowerManager::class.java)
                .newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LocationTracker:tracking"
                )
                .apply {

                    setReferenceCounted(false)

                    acquire(WAKE_LOCK_TIMEOUT)
                }
    }

    /*
     * El wake lock tiene un tiempo límite: si el servicio muriera
     * sin liberarlo, Android lo suelta solo. Mientras se rastrea,
     * el temporizador lo renueva cada pocos segundos.
     */
    private fun renewWakeLock() {

        wakeLock?.acquire(WAKE_LOCK_TIMEOUT)
    }

    private fun releaseWakeLock() {

        wakeLock?.let {

            if (it.isHeld) {
                it.release()
            }
        }

        wakeLock = null
    }

    private fun isTrackingRequested(): Boolean {

        return getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        ).getBoolean(
            KEY_TRACKING_ACTIVE,
            false
        )
    }

    /*
     * Guarda persistentemente si el usuario quiere rastrear.
     */
    private fun saveTrackingRequested(
        isRequested: Boolean
    ) {

        getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        ).edit {

            putBoolean(
                KEY_TRACKING_ACTIVE,
                isRequested
            )
        }
    }

    private fun createNotificationChannel() {

        /*
         * Los canales de notificación existen
         * solamente desde Android 8.0 (API 26).
         *
         * Nuestro proyecto soporta desde API 24,
         * por eso debemos comprobar la versión.
         */
        if (android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Rastreo de ubicación",
                    NotificationManager.IMPORTANCE_LOW
                )

            channel.description =
                "Notificación utilizada mientras LocationTracker registra ubicaciones"

            val notificationManager =
                getSystemService(
                    NotificationManager::class.java
                )

            notificationManager.createNotificationChannel(
                channel
            )
        }
    }

    private fun createNotification(): Notification {

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(
                "LocationTracker"
            )
            .setContentText(
                "Rastreo activo - guardando ubicación cada 30 segundos"
            )
            .setSmallIcon(
                android.R.drawable.ic_menu_mylocation
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {

        /*
         * No borramos la preferencia aquí: si Android destruye
         * el servicio por falta de recursos, queremos que
         * START_STICKY lo reanude. Solo stopTracking() y
         * abortTracking() la ponen en false.
         */
        removeLocationUpdates()

        serviceScope.cancel()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}
