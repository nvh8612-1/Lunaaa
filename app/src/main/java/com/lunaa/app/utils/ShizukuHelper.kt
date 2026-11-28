package com.lunaa.app.utils

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    private const val REQUEST_CODE = 1001

    // MainActivity gán hàm này để hiện thông báo khi người dùng bấm Cho phép/Từ chối
    @Volatile
    var onPermissionResult: ((Boolean) -> Unit)? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            onPermissionResult?.invoke(grantResult == PackageManager.PERMISSION_GRANTED)
        }
    }

    init {
        try {
            // Sticky: nhận binder ngay cả khi Shizuku đã chạy trước khi app mở
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (e: Throwable) {
            Log.e(TAG, "Đăng ký listener Shizuku thất bại", e)
        }
    }

    fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
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

    // Shizuku không cho app gọi newProcess trực tiếp nữa.
    // Muốn cấp quyền overlay tự động cần bind UserService của Shizuku.
    fun grantOverlayPermission(packageName: String) {
        Log.w(TAG, "grantOverlayPermission chưa làm cho $packageName, dùng màn hình cài đặt")
    }
}
