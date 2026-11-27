package com.lunaa.app.utils

import rikka.shizuku.Shizuku
import android.content.pm.PackageManager
import android.os.Build

object ShizukuHelper {

    private val ON_REQUEST_PERMISSION_RESULT_LISTENER = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        // Handle permission request result from Shizuku
    }

    init {
        try {
            Shizuku.addRequestPermissionResultListener(ON_REQUEST_PERMISSION_RESULT_LISTENER)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            if (isShizukuAvailable()) {
                if (Shizuku.isPreV11()) {
                    false
                } else {
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                }
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission() {
        try {
            if (isShizukuAvailable() && !hasPermission()) {
                if (!Shizuku.isPreV11()) {
                    Shizuku.requestPermission(0)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Use Shizuku service / shell to grant overlay permission or perform privileged operations silently if permitted.
     */
    fun grantOverlayPermission(packageName: String) {
        try {
            if (isShizukuAvailable() && hasPermission()) {
                val command = "appops set $packageName SYSTEM_ALERT_WINDOW allow"
                val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
                process.waitFor()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
