package com.lunaa.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lunaa.app.bridge.WebAppBridge
import org.json.JSONObject

class MainActivity : AppCompatActivity(), WebAppBridge.BridgeCallback {

    private lateinit var webView: WebView
    private var currentPreset = "780p"
    private var pendingRoomId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(WebAppBridge(this@MainActivity), "AndroidBridge")
            loadUrl("file:///android_asset/ui/index.html")
        }
        setContentView(webView)
    }

    // Callback từ JS chạy trên luồng nền, còn WebView chỉ được chạm trên luồng chính
    private fun runJs(script: String) {
        runOnUiThread { webView.evaluateJavascript(script, null) }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onRoomCreate(roomId: String, preset: String) = enterRoom(roomId, preset)
    override fun onRoomJoin(roomId: String, preset: String) = enterRoom(roomId, preset)

    private fun enterRoom(roomId: String, preset: String) {
        currentPreset = preset
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            pendingRoomId = roomId
            runOnUiThread {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            }
            return
        }
        showRoom(roomId)
    }

    // JSONObject.quote tạo chuỗi JS an toàn, escape được dấu nháy trong mã phòng
    private fun showRoom(roomId: String) {
        runJs("showCallControls(${JSONObject.quote(roomId)});")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        val roomId = pendingRoomId
        pendingRoomId = null
        if (roomId != null && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            showRoom(roomId)
        } else {
            toast("Cần quyền micro để vào phòng")
        }
    }

    override fun onLeaveRoom() {
        pendingRoomId = null
        runJs("hideCallControls();")
    }

    override fun onToggleScreenShare() {
        toast("Chia sẻ màn hình sẽ thêm ở bước sau")
    }

    override fun onUpdateAvatar(avatarUrl: String) {
        // Lưu avatar nếu cần sau này
    }

    override fun onSetPreset(preset: String) {
        currentPreset = preset
    }

    override fun onUiReady() {
        // Giao diện đã sẵn sàng
    }

    override fun onConnectShizuku() {
        toast("Shizuku chưa được bật ở bản này")
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val REQ_AUDIO = 101
    }
}
