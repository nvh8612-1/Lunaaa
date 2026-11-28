package com.lunaa.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lunaa.app.bridge.WebAppBridge
import com.lunaa.app.service.LunaaCallService

class MainActivity : AppCompatActivity(), WebAppBridge.BridgeCallback {

    private lateinit var webView: WebView
    private var currentPreset: String = "780p"
    private var currentAvatarUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize WebView UI Layer (Super lightweight, pure HTML/CSS/JS)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(WebAppBridge(this@MainActivity), "AndroidBridge")
            loadUrl("file:///android_asset/ui/index.html")
        }
        setContentView(webView)

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
        
        // Check Overlay Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            // Try Shizuku first to grant overlay automatically if available, otherwise fallback to standard intent
            if (com.lunaa.app.utils.ShizukuHelper.isShizukuAvailable() && com.lunaa.app.utils.ShizukuHelper.hasPermission()) {
                com.lunaa.app.utils.ShizukuHelper.grantOverlayPermission(packageName)
            } else {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
            }
        }

        // Initialize Shizuku background helper if available
        if (com.lunaa.app.utils.ShizukuHelper.isShizukuAvailable() && !com.lunaa.app.utils.ShizukuHelper.hasPermission()) {
            com.lunaa.app.utils.ShizukuHelper.requestPermission()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Handle permission results if needed
        }
    }

    override fun onToggleScreenShare() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, SCREEN_CAPTURE_REQ_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQ_CODE && resultCode == RESULT_OK && data != null) {
            // Pass MediaProjection result to service for screen capture & system audio mixing
            val intent = Intent(this, LunaaCallService::class.java).apply {
                action = LunaaCallService.ACTION_START_SCREEN_SHARE
                putExtra("RESULT_CODE", resultCode)
                putExtra("RESULT_DATA", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
        private const val OVERLAY_PERMISSION_REQ_CODE = 102
        private const val SCREEN_CAPTURE_REQ_CODE = 103
    }

    override fun onUpdateAvatar(avatarUrl: String) {
        currentAvatarUrl = avatarUrl
    }

    override fun onSetPreset(preset: String) {
        currentPreset = preset
    }

    override fun onUiReady() {
        // UI ready callback
    }

    override fun onConnectShizuku() {
        if (com.lunaa.app.utils.ShizukuHelper.isShizukuAvailable()) {
            if (!com.lunaa.app.utils.ShizukuHelper.hasPermission()) {
                com.lunaa.app.utils.ShizukuHelper.requestPermission()
                android.widget.Toast.makeText(this, "Đang yêu cầu quyền Shizuku...", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(this, "Shizuku đã được kết nối và cấp quyền!", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            android.widget.Toast.makeText(this, "Không tìm thấy dịch vụ Shizuku trên thiết bị!", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onRoomCreate(roomId: String, preset: String) {
        checkPermissionAndStartCall(roomId, preset)
    }

    override fun onRoomJoin(roomId: String, preset: String) {
        checkPermissionAndStartCall(roomId, preset)
    }

    override fun onLeaveRoom() {
        val intent = Intent(this, LunaaCallService::class.java).apply {
            action = LunaaCallService.ACTION_STOP_CALL
        }
        startService(intent)
        webView.evaluateJavascript("hideCallControls();", null)
    }

    private fun checkPermissionAndStartCall(roomId: String, preset: String) {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
            android.widget.Toast.makeText(this, "Cần cấp quyền Micro và Thông báo để gọi!", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            startCallService(roomId, preset)
            webView.evaluateJavascript("showCallControls('$roomId');", null)
        }
    }

    private fun startCallService(roomId: String, preset: String) {
        val intent = Intent(this, LunaaCallService::class.java).apply {
            action = LunaaCallService.ACTION_START_CALL
            putExtra("ROOM_ID", roomId)
            putExtra("PRESET", preset)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onDestroy() {
        // • BỊ HỦY HOÀN TOÀN (onDestroy -> destroy WebView) khi thoát app để giải phóng 100% RAM giao diện.
        try {
            webView.stopLoading()
            webView.clearCache(true)
            webView.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }
}
