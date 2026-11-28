package com.lunaa.app.utils

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    private const val REQUEST_CODE = 1001

    // MainActivity gán hàm này để báo kết quả khi người dùng bấm Cho phép/Từ chối
    @Volatile
    var onPermissionResult: ((Boolean) -> Unit)? = null

    // Được cập nhật bởi listener: pingBinder có thể trả false ngay sau khi Shizuku vừa khởi động
    @Volatile
    private var binderReady = false

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        binderReady = true
        Log.d(TAG, "Shizuku binder received")
    }

    private val binderDead = Shizuku.OnBinderDeadListener {
        binderReady = false
        Log.d(TAG, "Shizuku binder dead")
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            onPermissionResult?.invoke(grantResult == PackageManager.PERMISSION_GRANTED)
        }
    }

    init {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (e: Throwable) {
            Log.e(TAG, "Đăng ký listener Shizuku thất bại", e)
        }
    }

    fun isShizukuAvailable(): Boolean {
        if (binderReady) return true
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    // Chờ Shizuku kết nối tối đa timeoutMs. Gọi từ luồng nền, không gọi trên luồng chính.
    fun awaitBinder(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (isShizukuAvailable()) return true
            Thread.sleep(200)
        }
        return isShizukuAvailable()
    }

    fun hasPermission(): Boolean = try {
        isShizukuAvailable() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            if (isShizukuAvailable() && !Shizuku.isPreV11() && !hasPermission()) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "requestPermission thất bại", e)
        }
    }

    // Shizuku không cho app gọi newProcess trực tiếp. Muốn cấp quyền overlay tự động cần UserService.
    fun grantOverlayPermission(packageName: String) {
        Log.w(TAG, "grantOverlayPermission chưa làm cho $packageName, dùng màn hình cài đặt")
    }
}
