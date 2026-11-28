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

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            if (!isShizukuAvailable()) return false
            if (Shizuku.isPreV11()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
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

    /**
     * Hiện chưa cấp quyền overlay qua Shizuku.
     * Shizuku không còn cho phép gọi newProcess trực tiếp từ app.
     * Muốn chạy lệnh shell đặc quyền, cần bind một UserService của Shizuku.
     * Hiện tại MainActivity sẽ dùng màn hình cài đặt overlay khi hàm này không cấp quyền.
     */
    fun grantOverlayPermission(packageName: String) {
        Log.w(TAG, "grantOverlayPermission not implemented for $packageName; use system settings")
    }
}
