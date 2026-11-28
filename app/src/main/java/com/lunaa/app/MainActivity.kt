package com.lunaa.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lunaa.app.bridge.WebAppBridge
import com.lunaa.app.service.LunaaCallService
import com.lunaa.app.utils.ShizukuHelper
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class MainActivity : AppCompatActivity(), WebAppBridge.BridgeCallback {

    private lateinit var webView: WebView
    private lateinit var videoView: SurfaceViewRenderer
    private var inCall = false
    private var sharing = false
    private var remoteTrack: VideoTrack? = null
    private var videoInited = false
    private var pendingRoomId: String? = null
    private var pendingPreset = "780p"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#111214")))

        // Lớp dưới: video nhận được. Lớp trên: WebView trong suốt, giao diện nằm đè lên video.
        videoView = SurfaceViewRenderer(this).apply {
            setEnableHardwareScaler(true)
            setMirror(false)
            visibility = View.GONE
        }
        webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(WebAppBridge(this@MainActivity), "AndroidBridge")
            loadUrl("file:///android_asset/ui/index.html")
        }
        setContentView(FrameLayout(this).apply {
            addView(videoView, FrameLayout.LayoutParams(0, 0))
            addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        })

        LunaaCallService.uiListener = object : LunaaCallService.UiListener {
            override fun onParticipants(json: String) =
                runJs("onParticipants(${JSONObject.quote(json)});")
            override fun onChat(json: String) =
                runJs("onChatMessage(${JSONObject.quote(json)});")
            override fun onSpeaker(on: Boolean) =
                runJs("setSpeakerState($on);")
            override fun onScreenShare(on: Boolean) {
                sharing = on
                runJs("setScreenState($on);")
            }
            override fun onRemoteVideo(track: VideoTrack?) = attachRemoteVideo(track)
            override fun onCallEnded() {
                inCall = false
                sharing = false
                attachRemoteVideo(null)
                runJs("hideCallControls();")
            }
            override fun onAudioLevels(json: String) =
                runJs("onAudioLevels(${JSONObject.quote(json)});")
        }

        ShizukuHelper.onPermissionResult = { granted ->
            toast(if (granted) "Shizuku đã cấp quyền cho Lunaa" else "Shizuku đã từ chối quyền")
        }
        checkAndRequestPermissions()
    }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMISSIONS)
        }

        if (!Settings.canDrawOverlays(this)) {
            if (ShizukuHelper.isShizukuAvailable() && ShizukuHelper.hasPermission()) {
                ShizukuHelper.grantOverlayPermission(packageName)
            } else {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    REQ_OVERLAY
                )
            }
        }

        if (ShizukuHelper.isShizukuAvailable() && !ShizukuHelper.hasPermission()) {
            ShizukuHelper.requestPermission()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALL_AUDIO) {
            val roomId = pendingRoomId
            pendingRoomId = null
            if (roomId != null && hasMic()) {
                startCall(roomId, pendingPreset)
            } else if (roomId != null) {
                toast("Cần quyền micro để vào phòng")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCREEN && resultCode == RESULT_OK && data != null) {
            startService(Intent(this, LunaaCallService::class.java).apply {
                action = LunaaCallService.ACTION_START_SCREEN_SHARE
                putExtra("RESULT_DATA", data)
            })
        }
    }

    // Nút Back: nếu đang mở màn hình gọi thì thu nhỏ, không thoát app
    override fun onBackPressed() {
        webView.evaluateJavascript("handleBack()") { result ->
            if (result != "true") {
                runOnUiThread {
                    if (webView.canGoBack()) webView.goBack() else finish()
                }
            }
        }
    }

    private fun runJs(script: String) {
        runOnUiThread { webView.evaluateJavascript(script, null) }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    // Đặt video nhận được lên đúng ô trong giao diện
    private fun attachRemoteVideo(track: VideoTrack?) {
        runOnUiThread {
            remoteTrack?.removeSink(videoView)
            remoteTrack = track
            if (track == null) {
                videoView.visibility = View.GONE
                return@runOnUiThread
            }
            val ctx = LunaaCallService.eglContext ?: return@runOnUiThread
            if (!videoInited) {
                videoView.init(ctx, null)
                videoInited = true
            }
            track.addSink(videoView)
            runJs("reportVideoRect();")
        }
    }

    // ---- WebAppBridge.BridgeCallback (gọi từ JS, luồng nền) ----

    override fun onRoomCreate(roomId: String, preset: String) = joinOrCreate(roomId, preset)
    override fun onRoomJoin(roomId: String, preset: String) = joinOrCreate(roomId, preset)

    private fun joinOrCreate(roomId: String, preset: String) {
        if (!hasMic()) {
            pendingRoomId = roomId
            pendingPreset = preset
            runOnUiThread {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_CALL_AUDIO
                )
            }
            return
        }
        startCall(roomId, preset)
    }

    private fun startCall(roomId: String, preset: String) {
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
        if (!Settings.canDrawOverlays(this)) {
            toast("Cấp quyền hiển thị trên ứng dụng khác để thấy bong bóng chat khi thu nhỏ")
        }
        inCall = true
        runJs("showCallControls(${JSONObject.quote(roomId)});")
    }

    override fun onLeaveRoom() {
        if (inCall) {
            startService(Intent(this, LunaaCallService::class.java).apply {
                action = LunaaCallService.ACTION_STOP_CALL
            })
        }
        inCall = false
        sharing = false
        attachRemoteVideo(null)
        runOnUiThread {
            if (videoInited) {
                videoView.release()
                videoInited = false
            }
        }
        runJs("hideCallControls();")
    }

    override fun onToggleScreenShare() {
        if (!inCall) {
            toast("Vào phòng trước khi chia sẻ màn hình")
            return
        }
        if (sharing) {
            startService(Intent(this, LunaaCallService::class.java).apply {
                action = LunaaCallService.ACTION_STOP_SCREEN_SHARE
            })
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SCREEN)
    }

    override fun onVideoRect(x: Double, y: Double, w: Double, h: Double) {
        runOnUiThread {
            if (w <= 0 || h <= 0 || remoteTrack == null) {
                videoView.visibility = View.GONE
                return@runOnUiThread
            }
            val d = resources.displayMetrics.density
            val lp = videoView.layoutParams as FrameLayout.LayoutParams
            lp.width = (w * d).toInt()
            lp.height = (h * d).toInt()
            lp.leftMargin = (x * d).toInt()
            lp.topMargin = (y * d).toInt()
            videoView.layoutParams = lp
            videoView.visibility = View.VISIBLE
        }
    }

    override fun onMicToggle(enabled: Boolean) {
        if (!inCall) return
        startService(Intent(this, LunaaCallService::class.java).apply {
            action = LunaaCallService.ACTION_SET_MIC
            putExtra("MIC_ON", enabled)
        })
    }

    override fun onSpeakerToggle(on: Boolean) {
        if (!inCall) return
        startService(Intent(this, LunaaCallService::class.java).apply {
            action = LunaaCallService.ACTION_SET_SPEAKER
            putExtra("SPEAKER_ON", on)
        })
    }

    override fun onSendChat(text: String) {
        if (!inCall) return
        startService(Intent(this, LunaaCallService::class.java).apply {
            action = LunaaCallService.ACTION_SEND_CHAT
            putExtra("TEXT", text)
        })
    }

    override fun onUpdateAvatar(avatarUrl: String) {
        // Lưu avatar nếu cần sau này
    }

    override fun onSetPreset(preset: String) {
        pendingPreset = preset
    }

    override fun onUiReady() {
        // Giao diện đã sẵn sàng
    }

    override fun onConnectShizuku() {
        // Chờ kết nối vì Shizuku có thể cần vài giây sau khi cấp quyền; không chạy trên luồng chính
        Thread {
            val ready = ShizukuHelper.awaitBinder(3000)
            com.lunaa.app.DiagLog.write("Shizuku", "binder=$ready perm=${ShizukuHelper.hasPermission()}")
            when {
                !ready -> toast("Lunaa chưa kết nối được Shizuku. Mở Shizuku, kiểm tra trạng thái Running rồi thử lại")
                ShizukuHelper.hasPermission() -> toast("Shizuku đã kết nối và có quyền")
                else -> {
                    ShizukuHelper.requestPermission()
                    toast("Hãy cho phép Lunaa trong hộp thoại Shizuku")
                }
            }
        }.start()
    }

    // Bong bóng chat chỉ hiện khi bạn thoát ra màn hình khác, trong app thì dùng khung chat
    override fun onStart() {
        super.onStart()
        if (inCall) startService(Intent(this, LunaaCallService::class.java).apply {
            action = LunaaCallService.ACTION_BUBBLE_HIDE
        })
    }

    override fun onStop() {
        super.onStop()
        if (inCall) startService(Intent(this, LunaaCallService::class.java).apply {
            action = LunaaCallService.ACTION_BUBBLE_SHOW
        })
    }

    // Mở micro trực tiếp (không qua WebRTC) để biết phần cứng có thu được tiếng không
    override fun onTestMic() {
        Thread {
            val result = micProbe()
            com.lunaa.app.DiagLog.write("MicTest", result)
            toast(result)
        }.start()
    }

    private fun micProbe(): String {
        if (!hasMic()) return "Chưa có quyền micro cho Lunaa"
        val rate = 16000
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return "Thiết bị không hỗ trợ ghi âm (minBuf=$minBuf)"
        val rec = android.media.AudioRecord(
            android.media.MediaRecorder.AudioSource.MIC, rate,
            android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
        )
        if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return "Không mở được micro. Đóng app khác đang dùng mic (kể cả đang trong phòng)"
        }
        rec.startRecording()
        val buf = ShortArray(minBuf)
        var peak = 0
        val end = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < end) {
            val n = rec.read(buf, 0, buf.size)
            for (i in 0 until n.coerceAtLeast(0)) {
                val a = kotlin.math.abs(buf[i].toInt())
                if (a > peak) peak = a
            }
        }
        rec.stop()
        rec.release()
        return if (peak > 300) {
            "Micro hoạt động (đỉnh=$peak). Lỗi nằm ở đường WebRTC"
        } else {
            "Micro không có tín hiệu (đỉnh=$peak). Kiểm tra quyền micro và app khác đang chiếm mic"
        }
    }

    override fun onDestroy() {
        LunaaCallService.uiListener = null
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val REQ_PERMISSIONS = 101
        private const val REQ_CALL_AUDIO = 102
        private const val REQ_OVERLAY = 103
        private const val REQ_SCREEN = 104
    }
}
