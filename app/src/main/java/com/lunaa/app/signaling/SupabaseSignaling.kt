package com.lunaa.app.signaling

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class SupabaseSignaling(
    private val supabaseUrl: String,
    private val supabaseKey: String,
    private val roomId: String,
    private val listener: SignalingListener
) {
    // Mã định danh của máy này trong phòng, dùng để bỏ qua tin của chính mình và nhận đúng tin gửi cho mình
    val selfId: String = UUID.randomUUID().toString().take(8)

    interface SignalingListener {
        // payload là nội dung tin, luôn có trường "from"
        fun onMessage(type: String, payload: JSONObject)
        fun onConnected()
        fun onDisconnected()
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val heartbeat = object : Runnable {
        override fun run() {
            val ping = JSONObject()
                .put("topic", "phoenix")
                .put("event", "heartbeat")
                .put("payload", JSONObject())
                .put("ref", JSONObject.NULL)
            webSocket?.send(ping.toString())
            mainHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    fun connect() {
        webSocket?.close(1000, "Reconnect")
        webSocket = null

        val wsUrl = supabaseUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://") +
            "/realtime/v1/websocket?apikey=$supabaseKey&v=10"

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                // self=false: server không gửi lại tin của chính mình
                val join = JSONObject()
                    .put("topic", "realtime:$roomId")
                    .put("event", "phx_join")
                    .put(
                        "payload",
                        JSONObject().put("config", JSONObject().put("broadcast", JSONObject().put("self", false)))
                    )
                    .put("ref", "1")
                ws.send(join.toString())
                mainHandler.removeCallbacks(heartbeat)
                mainHandler.postDelayed(heartbeat, HEARTBEAT_MS)
                listener.onConnected()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("event") != "broadcast") return
                    val envelope = json.optJSONObject("payload") ?: return
                    val name = envelope.optString("event")
                    val inner = envelope.optJSONObject("payload") ?: JSONObject()
                    if (name.isNotEmpty()) listener.onMessage(name, inner)
                } catch (e: Exception) {
                    Log.e(TAG, "Parse message failed", e)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                mainHandler.removeCallbacks(heartbeat)
                listener.onDisconnected()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                mainHandler.removeCallbacks(heartbeat)
                listener.onDisconnected()
            }
        })
    }

    // Gửi một tin tới cả phòng. data là các trường của tin, sẽ tự thêm "from".
    fun send(type: String, data: JSONObject) {
        val inner = JSONObject(data.toString()).put("from", selfId)
        val message = JSONObject()
            .put("topic", "realtime:$roomId")
            .put("event", "broadcast")
            .put(
                "payload",
                JSONObject().put("type", "broadcast").put("event", type).put("payload", inner)
            )
            .put("ref", JSONObject.NULL)
        webSocket?.send(message.toString())
    }

    fun disconnect() {
        mainHandler.removeCallbacks(heartbeat)
        webSocket?.close(1000, "Client normal disconnect")
        webSocket = null
    }

    companion object {
        private const val TAG = "SupabaseSignaling"
        private const val HEARTBEAT_MS = 25_000L
    }
}
