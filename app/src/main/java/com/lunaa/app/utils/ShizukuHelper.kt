package com.lunaa.app.utils

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

object ShizukuHelper {

    private const val TAG = "ShizukuHelper"

    private val onRequestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            Log.d(TAG, "Shizuku permission result: request=$requestCode grant=$grantResult")
        }

    init {
        try {
            Shizuku.addRequestPermissionResultListener(onRequestPermissionResultListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add Shizuku listener", e)
        }
    }

    fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        false
    }

    fun hasPermission(): Boolean = try {
        isShizukuAvailable() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    fun requestPermission() {
        try {
            if (isShizukuAvailable() && !hasPermission() && !Shizuku.isPreV11()) {
                Shizuku.requestPermission(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed", e)
        }
    }

    // Shizuku không còn cho app gọi newProcess trực tiếp.
    // Muốn cấp quyền overlay tự động cần bind UserService của Shizuku.
    fun grantOverlayPermission(packageName: String) {
        Log.w(TAG, "grantOverlayPermission chưa làm cho $packageName, dùng màn hình cài đặt")
    }
}
