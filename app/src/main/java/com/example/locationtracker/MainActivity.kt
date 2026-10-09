package com.example.locationtracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.locationtracker.data.LocationDatabase
import com.example.locationtracker.ui.theme.LocationTrackerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {

            LocationTrackerTheme {

                TrackingScreen()
            }
        }
    }
}

@Composable
fun TrackingScreen() {

    val context =
        LocalContext.current

    val locationDao =
        remember {
            LocationDatabase
                .getDatabase(context)
                .locationDao()
        }

    /*
     * Estado real del servicio (en memoria). Si el proceso
     * muere, vuelve a false: la interfaz nunca muestra
     * "activo" con el servicio detenido.
     *
     * collectAsStateWithLifecycle deja de observar cuando
     * la app pasa a segundo plano.
     */
    val isTracking by
    LocationTrackingService.isTracking
        .collectAsStateWithLifecycle()

    /*
     * Room emite un valor nuevo cada vez que cambia la
     * tabla, así que no hace falta consultar en bucle.
     */
    val savedLocations by
    remember { locationDao.observeLocationCount() }
        .collectAsStateWithLifecycle(initialValue = 0)

    val pendingLocations by
    remember { locationDao.observeUnsyncedLocationCount() }
        .collectAsStateWithLifecycle(initialValue = 0)

    val staleLocations by
    remember { locationDao.observeStaleLocationCount() }
        .collectAsStateWithLifecycle(initialValue = 0)

    /*
     * Mensaje puntual (por ejemplo, permiso denegado).
     * Si es null se muestra el estado del rastreo.
     */
    var message by
    remember {
        mutableStateOf<String?>(null)
    }

    /*
     * Comprueba si tenemos permiso
     * de ubicación.
     */
    fun hasLocationPermission(): Boolean {

        val fine =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarse =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    fun startTrackingService() {

        val intent =
            Intent(
                context,
                LocationTrackingService::class.java
            ).apply {

                action =
                    LocationTrackingService.ACTION_START
            }

        ContextCompat.startForegroundService(
            context,
            intent
        )

        message = null
    }

    /*
     * Solicitud de permisos de ubicación.
     */
    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val fine =
                permissions[
                    Manifest.permission.ACCESS_FINE_LOCATION
                ] ?: false

            val coarse =
                permissions[
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ] ?: false

            if (fine || coarse) {

                /*
                 * El usuario pulsó "Iniciar rastreo" y acaba
                 * de conceder el permiso: arrancamos sin
                 * obligarle a pulsar otra vez.
                 */
                startTrackingService()

            } else {

                message =
                    "Permiso de ubicación denegado"
            }
        }

    /*
     * Solicitud del permiso de notificaciones.
     */
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.RequestPermission()
        ) {
            // No necesitamos realizar ninguna acción adicional aquí.
        }

    /*
     * Al abrir la aplicación pedimos el permiso
     * de notificaciones (Android 13+).
     */
    LaunchedEffect(Unit) {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            val notificationPermission =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                )

            if (
                notificationPermission !=
                PackageManager.PERMISSION_GRANTED
            ) {

                notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),

        verticalArrangement =
            Arrangement.Center,

        horizontalAlignment =
            Alignment.CenterHorizontally
    ) {

        Text(
            text = "LocationTracker"
        )

        Spacer(
            modifier =
                Modifier.height(24.dp)
        )

        Text(
            text =
                if (isTracking) {
                    "Estado: RASTREO ACTIVO"
                } else {
                    "Estado: RASTREO DETENIDO"
                }
        )

        Spacer(
            modifier =
                Modifier.height(8.dp)
        )

        Text(
            text =
                message
                    ?: if (isTracking) {
                        "Rastreo activo"
                    } else {
                        "Rastreo detenido"
                    }
        )

        Spacer(
            modifier =
                Modifier.height(16.dp)
        )

        Text(
            text =
                "Intervalo de registro: 30 segundos"
        )

        Spacer(
            modifier =
                Modifier.height(24.dp)
        )

        Text(
            text =
                "Registros guardados: $savedLocations"
        )

        Text(
            text =
                "Sin señal nueva (última posición): $staleLocations"
        )

        Text(
            text =
                "Pendientes de sincronizar: $pendingLocations"
        )

        Spacer(
            modifier =
                Modifier.height(24.dp)
        )

        Button(
            enabled = !isTracking,

            onClick = {

                if (hasLocationPermission()) {

                    startTrackingService()

                } else {

                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        ) {

            Text(
                text = "INICIAR RASTREO"
            )
        }

        Spacer(
            modifier =
                Modifier.height(12.dp)
        )

        Button(
            enabled = isTracking,

            onClick = {

                val intent =
                    Intent(
                        context,
                        LocationTrackingService::class.java
                    ).apply {

                        action =
                            LocationTrackingService.ACTION_STOP
                    }

                context.startService(
                    intent
                )

                message = null
            }
        ) {

            Text(
                text = "DETENER RASTREO"
            )
        }
    }
}
