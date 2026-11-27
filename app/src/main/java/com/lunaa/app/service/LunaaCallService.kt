package com.lunaa.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.lunaa.app.overlay.FloatingPopupManager
import com.lunaa.app.signaling.SupabaseSignaling
import com.lunaa.app.webrtc.RtcEngine
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

class LunaaCallService : Service(), RtcEngine.RtcListener, SupabaseSignaling.SignalingListener {

    private var popupManager: FloatingPopupManager? = null
    private var signaling: SupabaseSignaling? = null
    private var rtcEngine: RtcEngine? = null

    private var currentRoomId: String = ""
    private var currentPreset: String = "780p"

    // Đặt true khi người dùng chủ động rời phòng, để onDestroy không hiện thông báo "Tham gia lại"
    private var userLeft = false

    private var callSeconds = 0
    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            callSeconds++
            val minutes = callSeconds / 60
            val seconds = callSeconds % 60
            val timeStr = String.format("%d:%02d", minutes, seconds)
            updateNotification("RoomID: $currentRoomId", "Time: $timeStr")
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        popupManager = FloatingPopupManager(this)
        startForeground(NOTIFICATION_ID, createNotification("Lunaa Call", "Đang khởi tạo..."))
        callSeconds = 0
        handler.postDelayed(timerRunnable, 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        intent?.getStringExtra("ROOM_ID")?.let { currentRoomId = it }
        intent?.getStringExtra("PRESET")?.let { currentPreset = it }

        when (action) {
            ACTION_START_CALL -> {
                userLeft = false
                initWebRtcAndSignaling()
            }
            ACTION_STOP_CALL -> {
                userLeft = true
                stopSelf()
            }
            ACTION_START_SCREEN_SHARE -> {
                // Chỗ này sẽ dùng MediaProjection (RESULT_CODE, RESULT_DATA) khi bạn triển khai chia sẻ màn hình
            }
        }

        return START_STICKY
    }

    private fun initWebRtcAndSignaling() {
        // Dọn kết nối cũ nếu có, tránh rò rỉ khi join lại
        signaling?.disconnect()
        rtcEngine?.dispose()

        rtcEngine = RtcEngine(this, this).also { engine ->
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )
            engine.createPeerConnection(iceServers)
        }

        signaling = SupabaseSignaling(
            supabaseUrl = "https://aakjtgtfrlsqhghjvdmt.supabase.co",
            supabaseKey = "sb_publishable_bfpDknie-GSHmTEIGT2LoA_AuoCu2oJ",
            roomId = currentRoomId,
            listener = this
        )
        signaling?.connect()
    }

    private fun createNotification(title: String, message: String): Notification {
        val channelId = "lunaa_call_channel_priority"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Lunaa Priority Call Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Lunaa Call ongoing with DND bypass"
                enableVibration(true)
            }
            manager?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, message: String) {
        val notification = createNotification(title, message)
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun sendRejoinNotification() {
        val channelId = "lunaa_rejoin_channel"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Lunaa Rejoin Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alert when call is disconnected or killed"
                enableVibration(true)
            }
            manager?.createNotificationChannel(channel)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val acceptIntent = Intent(this, LunaaCallService::class.java).apply {
            action = ACTION_START_CALL
            putExtra("ROOM_ID", currentRoomId)
            putExtra("PRESET", currentPreset)
        }
        val pendingAccept = PendingIntent.getService(this, 0, acceptIntent, flags)

        val declineIntent = Intent(this, LunaaCallService::class.java).apply {
            action = ACTION_STOP_CALL
        }
        val pendingDecline = PendingIntent.getService(this, 1, declineIntent, flags)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Tham gia lại RoomID: $currentRoomId")
            .setContentText("Bấm để tham gia lại hoặc từ chối cuộc gọi.")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Từ chối", pendingDecline)
            .addAction(android.R.drawable.ic_menu_call, "Tham gia", pendingAccept)
            .setContentIntent(pendingAccept)
            .setAutoCancel(true)
            .build()

        manager?.notify(NOTIFICATION_ID + 1, notification)

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                popupManager?.showRejoinPopup(currentRoomId, currentPreset)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---- RtcEngine.RtcListener ----

    override fun onLocalDescriptionCreated(sdp: SessionDescription) {
        val data = JSONObject().apply {
            put("type", sdp.type.canonicalForm())
            put("sdp", sdp.description)
        }
        signaling?.sendSignal("sdp", data)
    }

    override fun onIceCandidateGenerated(candidate: IceCandidate) {
        val data = JSONObject().apply {
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }
        signaling?.sendSignal("ice", data)
    }

    override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
        if (state == PeerConnection.PeerConnectionState.CONNECTED) {
            signaling?.disconnect()
        }
    }

    // ---- SupabaseSignaling.SignalingListener ----

    override fun onMessage(type: String, payload: JSONObject) {
        val data = payload.optJSONObject("data") ?: return
        when (type) {
            "sdp" -> {
                val isOffer = data.optString("type") == "offer"
                val descType = if (isOffer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
                rtcEngine?.setRemoteDescription(descType, data.optString("sdp"))
                if (isOffer) rtcEngine?.createAnswer()
            }
            "ice" -> {
                val candidate = IceCandidate(
                    data.optString("sdpMid"),
                    data.optInt("sdpMLineIndex"),
                    data.optString("candidate")
                )
                rtcEngine?.addIceCandidate(candidate)
            }
        }
    }

    override fun onConnected() {
        rtcEngine?.createOffer()
    }

    override fun onDisconnected() {
        // Không làm gì: nếu cần thông báo lại, xử lý ở đây
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(timerRunnable)
        signaling?.disconnect()
        rtcEngine?.dispose()
        signaling = null
        rtcEngine = null

        // Chỉ hiện thông báo khi cuộc gọi bị ngắt ngoài ý muốn
        if (!userLeft && currentRoomId.isNotEmpty()) {
            sendRejoinNotification()
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_CALL = "ACTION_START_CALL"
        const val ACTION_STOP_CALL = "ACTION_STOP_CALL"
        const val ACTION_START_SCREEN_SHARE = "ACTION_START_SCREEN_SHARE"
        private const val NOTIFICATION_ID = 1001
    }
}
