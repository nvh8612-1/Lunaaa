package com.lunaa.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.lunaa.app.R
import com.lunaa.app.webrtc.RtcEngine
import com.lunaa.app.signaling.SupabaseSignaling
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

class LunaaCallService : Service(), RtcEngine.RtcListener, SupabaseSignaling.SignalingListener {

    private var popupManager: com.lunaa.app.overlay.FloatingPopupManager? = null
    private var signaling: SupabaseSignaling? = null
    private var currentRoomId: String = ""
    private var currentPreset: String = "780p"
    
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
        popupManager = com.lunaa.app.overlay.FloatingPopupManager(this)
        startForeground(NOTIFICATION_ID, createNotification("Lunaa Call", "Đang khởi tạo..."))
        callSeconds = 0
        handler.postDelayed(timerRunnable, 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        currentRoomId = intent?.getStringExtra("ROOM_ID") ?: ""
        currentPreset = intent?.getStringExtra("PRESET") ?: "780p"

        if (action == ACTION_START_CALL) {
            initWebRtcAndSignaling()
        } else if (action == ACTION_STOP_CALL) {
            stopSelf()
        }

        return START_STICKY
    }

    private fun initWebRtcAndSignaling() {
        rtcEngine = RtcEngine(this, this)
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").create(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").create()
        )
        rtcEngine?.createPeerConnection(iceServers)

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
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
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

        // Direct service restart action intent for Accept (Join)
        val intentAccept = Intent(this, LunaaCallService::class.java).apply {
            action = ACTION_START_CALL
            putExtra("ROOM_ID", currentRoomId)
            putExtra("PRESET", currentPreset)
        }
        val pendingIntentAccept = android.app.PendingIntent.getService(
            this, 0, intentAccept, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        // Action intent for Decline (Dismiss notification/popup)
        val intentDecline = Intent(this, LunaaCallService::class.java).apply {
            action = ACTION_STOP_CALL
        }
        val pendingIntentDecline = android.app.PendingIntent.getService(
            this, 1, intentDecline, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Tham gia lại RoomID: $currentRoomId")
            .setContentText("Bấm để tham gia lại hoặc từ chối cuộc gọi.")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Từ chối", pendingIntentDecline)
            .addAction(android.R.drawable.ic_menu_call, "Tham gia", pendingIntentAccept)
            .setContentIntent(pendingIntentAccept)
            .setAutoCancel(true)
            .build()

        manager?.notify(NOTIFICATION_ID + 1, notification)

        // Show floating overlay popup with Decline/Accept buttons if permission granted
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this)) {
                popupManager?.showRejoinPopup(currentRoomId, currentPreset)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

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

    override fun onMessage(type: String, payload: JSONObject) {
        val data = payload.optJSONObject("data") ?: return
        if (type == "sdp") {
            val sdpType = data.optString("type")
            val sdpSdp = data.optString("sdp")
            val descType = if (sdpType == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
            rtcEngine?.setRemoteDescription(descType, sdpSdp)
            if (descType == SessionDescription.Type.OFFER) {
                rtcEngine?.createAnswer()
            }
        } else if (type == "ice") {
            val sdpMid = data.optString("sdpMid")
            val sdpMLineIndex = data.optInt("sdpMLineIndex")
            val candidateStr = data.optString("candidate")
            val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateStr)
            rtcEngine?.addIceCandidate(candidate)
        }
    }

    override fun onConnected() {
        rtcEngine?.createOffer()
    }

    override fun onDisconnected() {}

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timerRunnable)
        signaling?.disconnect()
        rtcEngine?.dispose()
        if (currentRoomId.isNotEmpty()) {
            sendRejoinNotification()
        }
    }

    companion object {
        const val ACTION_START_CALL = "ACTION_START_CALL"
        const val ACTION_STOP_CALL = "ACTION_STOP_CALL"
        private const val NOTIFICATION_ID = 1001
    }
}
