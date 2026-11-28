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
import java.util.concurrent.TimeUnit

class SupabaseSignaling(
    private val supabaseUrl: String,
    private val supabaseKey: String,
    private val roomId: String,
    private val listener: SignalingListener
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            // Realtime của Supabase đóng kết nối nếu không có heartbeat mỗi ~25 giây
            val ping = JSONObject().apply {
                put("topic", "phoenix")
                put("event", "heartbeat")
                put("payload", JSONObject())
                put("ref", JSONObject.NULL)
            }
            webSocket?.send(ping.toString())
            mainHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    interface SignalingListener {
        fun onMessage(type: String, payload: JSONObject)
        fun onConnected()
        fun onDisconnected()
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
                val joinMsg = JSONObject().apply {
                    put("topic", "realtime:$roomId")
                    put("event", "phx_join")
                    put("payload", JSONObject())
                    put("ref", "1")
                }
                ws.send(joinMsg.toString())
                mainHandler.removeCallbacks(heartbeat)
                mainHandler.postDelayed(heartbeat, HEARTBEAT_MS)
                listener.onConnected()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val event = json.optString("event")
                    val payload = json.optJSONObject("payload") ?: JSONObject()
                    if (event == "broadcast" || event == "phx_reply") {
                        val type = payload.optString("type", event)
                        listener.onMessage(type, payload)
                    }
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

    fun sendSignal(type: String, data: JSONObject) {
        val message = JSONObject().apply {
            put("topic", "realtime:$roomId")
            put("event", "broadcast")
            put("payload", JSONObject().apply {
                put("type", type)
                put("data", data)
            })
        }
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
