package com.lunaa.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lunaa.app.overlay.FloatingPopupManager
import com.lunaa.app.signaling.SupabaseSignaling
import com.lunaa.app.webrtc.RtcEngine
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

class LunaaCallService : Service(), SupabaseSignaling.SignalingListener, RtcEngine.RtcListener {

    // MainActivity gán listener này để đẩy dữ liệu lên giao diện
    interface UiListener {
        fun onParticipants(json: String)
        fun onChat(json: String)
        fun onSpeaker(on: Boolean)
        fun onScreenShare(on: Boolean)
        fun onRemoteVideo(track: VideoTrack?)
    }

    private var popupManager: FloatingPopupManager? = null
    private var signaling: SupabaseSignaling? = null
    private var rtcEngine: RtcEngine? = null
    private var audioManager: AudioManager? = null

    private var currentRoomId = ""
    private var currentPreset = "780p"
    private var myName = "Bạn"
    private var userLeft = false
    private var callSeconds = 0
    private var localSharing = false

    // peerId -> tên hiển thị, và danh sách người đang chia sẻ màn hình
    private val peers = LinkedHashMap<String, String>()
    private val sharingPeers = HashSet<String>()

    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            callSeconds++
            val minutes = callSeconds / 60
            val seconds = callSeconds % 60
            updateNotification("RoomID: $currentRoomId", String.format("Time: %d:%02d", minutes, seconds))
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        popupManager = FloatingPopupManager(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val notification = createNotification("Lunaa Call", "Đang khởi tạo...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        callSeconds = 0
        handler.postDelayed(timerRunnable, 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val i: Intent = intent ?: return START_STICKY
        i.getStringExtra("ROOM_ID")?.let { currentRoomId = it }
        i.getStringExtra("PRESET")?.let { currentPreset = it }

        when (i.action) {
            ACTION_START_CALL -> {
                userLeft = false
                startCall()
            }
            ACTION_STOP_CALL -> {
                userLeft = true
                stopSelf()
            }
            ACTION_SET_MIC -> rtcEngine?.setMicEnabled(i.getBooleanExtra("MIC_ON", true))
            ACTION_SET_SPEAKER -> setSpeaker(i.getBooleanExtra("SPEAKER_ON", true))
            ACTION_SEND_CHAT -> sendChat(i.getStringExtra("TEXT") ?: "")
            ACTION_START_SCREEN_SHARE -> startScreenShare(i)
            ACTION_STOP_SCREEN_SHARE -> stopScreenShare()
        }
        return START_STICKY
    }

    private fun startCall() {
        teardownCall()
        myName = "Thành viên " + Build.MODEL.take(10)
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(true)
        val engine = RtcEngine(this, this)
        rtcEngine = engine
        eglContext = engine.eglContext()
        signaling = SupabaseSignaling(SUPABASE_URL, SUPABASE_KEY, currentRoomId, this).also { it.connect() }
    }

    private fun teardownCall() {
        signaling?.disconnect()
        signaling = null
        rtcEngine?.dispose()
        rtcEngine = null
        eglContext = null
        peers.clear()
        sharingPeers.clear()
        localSharing = false
        emitParticipants()
        uiListener?.onScreenShare(false)
        uiListener?.onRemoteVideo(null)
        audioManager?.mode = AudioManager.MODE_NORMAL
    }

    // ---- Chia sẻ màn hình ----

    private fun startScreenShare(i: Intent) {
        val engine = rtcEngine ?: return
        val data = i.getParcelableExtra<Intent>("RESULT_DATA") ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification("Lunaa Call", "Đang chia sẻ màn hình"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            }
            engine.startScreenShare(data, 1280, 720)
            localSharing = true
            signaling?.send("presence", JSONObject().put("action", "screen").put("on", true))
            uiListener?.onScreenShare(true)
        } catch (e: Exception) {
            Log.e(TAG, "Bắt đầu chia sẻ màn hình lỗi", e)
        }
    }

    private fun stopScreenShare() {
        if (!localSharing) return
        rtcEngine?.stopScreenShare()
        localSharing = false
        signaling?.send("presence", JSONObject().put("action", "screen").put("on", false))
        uiListener?.onScreenShare(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Lunaa Call", "Đang trong phòng"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        }
    }

    // ---- SupabaseSignaling.SignalingListener ----

    override fun onConnected() {
        signaling?.send("presence", JSONObject().put("action", "join").put("name", myName))
    }

    override fun onDisconnected() {
        // Chưa tự kết nối lại khi mất mạng
    }

    override fun onMessage(type: String, payload: JSONObject) {
        val from = payload.optString("from")
        val me = signaling?.selfId ?: return
        if (from.isEmpty() || from == me) return
        val to = payload.optString("to")
        if (to.isNotEmpty() && to != me) return

        when (type) {
            "presence" -> onPresence(from, payload)
            "sdp" -> onSdp(from, payload)
            "ice" -> onIce(from, payload)
            "chat" -> emitChat(payload.optString("name", "Thành viên"), payload.optString("text"), false)
        }
    }

    private fun onPresence(from: String, p: JSONObject) {
        when (p.optString("action")) {
            "join" -> {
                peers[from] = p.optString("name", "Thành viên")
                emitParticipants()
                signaling?.send(
                    "presence",
                    JSONObject().put("action", "here").put("name", myName).put("to", from)
                )
                if (localSharing) {
                    signaling?.send(
                        "presence",
                        JSONObject().put("action", "screen").put("on", true).put("to", from)
                    )
                }
                connectTo(from)
            }
            "here" -> {
                peers[from] = p.optString("name", "Thành viên")
                emitParticipants()
                connectTo(from)
            }
            "leave" -> {
                peers.remove(from)
                sharingPeers.remove(from)
                rtcEngine?.removePeer(from)
                emitParticipants()
                if (sharingPeers.isEmpty()) uiListener?.onRemoteVideo(null)
            }
            "screen" -> {
                if (p.optBoolean("on")) {
                    sharingPeers.add(from)
                } else {
                    sharingPeers.remove(from)
                    if (sharingPeers.isEmpty()) uiListener?.onRemoteVideo(null)
                }
                emitParticipants()
            }
        }
    }

    // Quy ước tránh offer hai chiều: người có mã nhỏ hơn là bên tạo offer
    private fun connectTo(peerId: String) {
        val engine = rtcEngine ?: return
        if (engine.hasPeer(peerId)) return
        val me = signaling?.selfId ?: return
        if (me < peerId) engine.createOffer(peerId) else engine.ensurePeer(peerId)
    }

    private fun onSdp(from: String, p: JSONObject) {
        val engine = rtcEngine ?: return
        val sdp = p.optString("sdp")
        if (p.optString("type") == "offer") {
            engine.setRemoteDescription(from, SessionDescription.Type.OFFER, sdp)
            engine.createAnswer(from)
        } else {
            engine.setRemoteDescription(from, SessionDescription.Type.ANSWER, sdp)
        }
    }

    private fun onIce(from: String, p: JSONObject) {
        val candidate = IceCandidate(
            p.optString("sdpMid"),
            p.optInt("sdpMLineIndex"),
            p.optString("candidate")
        )
        rtcEngine?.addIceCandidate(from, candidate)
    }

    // ---- RtcEngine.RtcListener ----

    override fun onLocalDescriptionCreated(peerId: String, sdp: SessionDescription) {
        signaling?.send(
            "sdp",
            JSONObject()
                .put("type", sdp.type.canonicalForm())
                .put("sdp", sdp.description)
                .put("to", peerId)
        )
    }

    override fun onIceCandidateGenerated(peerId: String, candidate: IceCandidate) {
        signaling?.send(
            "ice",
            JSONObject()
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                .put("candidate", candidate.sdp)
                .put("to", peerId)
        )
    }

    override fun onPeerStateChanged(peerId: String, state: PeerConnection.PeerConnectionState) {
        if (state == PeerConnection.PeerConnectionState.FAILED ||
            state == PeerConnection.PeerConnectionState.CLOSED
        ) {
            rtcEngine?.removePeer(peerId)
        }
    }

    override fun onRemoteVideo(peerId: String, track: VideoTrack) {
        uiListener?.onRemoteVideo(track)
    }

    // ---- Chat, loa, giao diện ----

    private fun sendChat(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        signaling?.send("chat", JSONObject().put("name", myName).put("text", clean))
        emitChat("Bạn", clean, true)
    }

    private fun setSpeaker(on: Boolean) {
        audioManager?.isSpeakerphoneOn = on
        uiListener?.onSpeaker(on)
    }

    private fun emitParticipants() {
        val arr = JSONArray()
        peers.forEach { (id, name) ->
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("sharing", sharingPeers.contains(id))
            )
        }
        uiListener?.onParticipants(arr.toString())
    }

    private fun emitChat(name: String, text: String, self: Boolean) {
        uiListener?.onChat(JSONObject().put("name", name).put("text", text).put("self", self).toString())
    }

    // ---- Thông báo ----

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
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, createNotification(title, message))
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
        val declineIntent = Intent(this, LunaaCallService::class.java).apply { action = ACTION_STOP_CALL }
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(timerRunnable)
        signaling?.send("presence", JSONObject().put("action", "leave").put("name", myName))
        teardownCall()
        if (!userLeft && currentRoomId.isNotEmpty()) {
            sendRejoinNotification()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LunaaCallService"

        @Volatile
        var uiListener: UiListener? = null

        // Context EGL để MainActivity hiển thị video nhận được
        @Volatile
        var eglContext: EglBase.Context? = null

        const val ACTION_START_CALL = "ACTION_START_CALL"
        const val ACTION_STOP_CALL = "ACTION_STOP_CALL"
        const val ACTION_START_SCREEN_SHARE = "ACTION_START_SCREEN_SHARE"
        const val ACTION_STOP_SCREEN_SHARE = "ACTION_STOP_SCREEN_SHARE"
        const val ACTION_SET_MIC = "ACTION_SET_MIC"
        const val ACTION_SET_SPEAKER = "ACTION_SET_SPEAKER"
        const val ACTION_SEND_CHAT = "ACTION_SEND_CHAT"

        private const val NOTIFICATION_ID = 1001
        private const val SUPABASE_URL = "https://aakjtgtfrlsqhghjvdmt.supabase.co"
        private const val SUPABASE_KEY = "sb_publishable_bfpDknie-GSHmTEIGT2LoA_AuoCu2oJ"
    }
}
