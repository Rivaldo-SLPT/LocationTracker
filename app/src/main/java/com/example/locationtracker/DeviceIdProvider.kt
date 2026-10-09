package com.example.locationtracker

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import java.util.UUID

/*
 * Identificador que se envía a AWS como deviceId.
 *
 * Cada instalación tiene el suyo, así el servidor puede
 * distinguir los teléfonos. Se genera la primera vez y
 * se guarda en SharedPreferences: solo cambia si se
 * desinstala la app o se borran sus datos.
 */
object DeviceIdProvider {

    private const val PREFS_NAME =
        "device_id_prefs"

    private const val KEY_DEVICE_ID =
        "device_id"

    /*
     * ID que usaba la app cuando solo existía un teléfono.
     * Lo conserva el teléfono original para no partir su
     * historial en AWS.
     */
    private const val LEGACY_DEVICE_ID =
        "s24-ultra-001"

    @Synchronized
    fun getDeviceId(context: Context): String {

        val prefs =
            context.applicationContext.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

        prefs.getString(KEY_DEVICE_ID, null)?.let {
            return it
        }

        val deviceId =
            if (isUpdateOfOlderInstall(context)) {
                LEGACY_DEVICE_ID
            } else {
                UUID.randomUUID().toString()
            }

        prefs.edit {
            putString(KEY_DEVICE_ID, deviceId)
        }

        println(
            "DeviceIdProvider: deviceId asignado = $deviceId"
        )

        return deviceId
    }

    /*
     * Si todavía no hay ID guardado pero la app ya estaba
     * instalada antes de esta versión, es el teléfono
     * original: la versión anterior tenía el ID fijo.
     * En una instalación nueva ambas fechas coinciden.
     */
    private fun isUpdateOfOlderInstall(context: Context): Boolean {

        val packageInfo: PackageInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    0
                )
            }

        return packageInfo.firstInstallTime != packageInfo.lastUpdateTime
    }
}
