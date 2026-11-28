package com.lunaa.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import com.lunaa.app.DiagLog

// Popup nổi: hiển thị assets/popup/index.html trong cửa sổ overlay.
// Chế độ bong bóng: cửa sổ hẹp sát mép phải/trái. Chế độ mở: cửa sổ toàn màn hình.
class CallPopupManager(
    private val context: Context,
    private val onDecline: () -> Unit,
    private val onShare: () -> Unit,
    private val onSend: (String) -> Unit
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private var webView: WebView? = null
    private var attached = false
    private var pageReady = false
    private var open = false
    private var side = "right"

    // Trạng thái để phát lại khi WebView được tạo lại
    private var lastRoom = ""
    private var lastSharing = false
    private var lastParticipants = "[]"
    private val chatLog = ArrayList<String>()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun bubbleParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        dp(64), WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = (if (side == "left") Gravity.START else Gravity.END) or Gravity.TOP
    }

    private fun openParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
        0,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    // Hiện bong bóng. Gọi lại khi đang hiện thì chỉ cập nhật tên phòng.
    fun show(roomId: String) {
        try {
            showInner(roomId)
        } catch (e: Throwable) {
            DiagLog.write("Popup", "Hiện popup lỗi: ${e.javaClass.simpleName} ${e.message}")
            hide()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showInner(roomId: String) {
        lastRoom = roomId
        if (attached) {
            exec("setRoom(${JSONObject.quote(roomId)});")
            return
        }
        if (!Settings.canDrawOverlays(context)) return

        val view = WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) {
                    pageReady = true
                    replay()
                }
            }
            addJavascriptInterface(Bridge(), "PopupBridge")
            loadUrl("file:///android_asset/popup/index.html")
        }
        webView = view
        wm.addView(view, bubbleParams())
        attached = true
        open = false
    }

    fun hide() {
        if (!attached) return
        try {
            webView?.let { wm.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        webView?.destroy()
        webView = null
        attached = false
        pageReady = false
        open = false
    }

    // Gọi khi kết thúc cuộc gọi: ẩn và xóa trạng thái
    fun reset() {
        hide()
        lastRoom = ""
        lastSharing = false
        lastParticipants = "[]"
        chatLog.clear()
    }

    fun setSharing(on: Boolean) {
        lastSharing = on
        exec("setSharing($on);")
    }

    fun setParticipants(json: String) {
        lastParticipants = json
        exec("setParticipants(${JSONObject.quote(json)});")
    }

    fun setVoice(json: String) {
        exec("setVoiceLevels(${JSONObject.quote(json)});")
    }

    fun addChat(name: String, text: String, self: Boolean) {
        val json = JSONObject().put("name", name).put("text", text).put("self", self).toString()
        chatLog.add(json)
        if (chatLog.size > 100) chatLog.removeAt(0)
        exec("addChatMessage(${JSONObject.quote(json)});")
    }

    private fun exec(js: String) {
        if (!pageReady) return
        webView?.evaluateJavascript(js, null)
    }

    // Phát lại toàn bộ trạng thái sau khi trang được tải xong
    private fun replay() {
        exec("setRoom(${JSONObject.quote(lastRoom)});")
        exec("setSharing($lastSharing);")
        exec("setParticipants(${JSONObject.quote(lastParticipants)});")
        chatLog.forEach { exec("addChatMessage(${JSONObject.quote(it)});") }
    }

    private fun applyOpen(on: Boolean) {
        val view = webView ?: return
        open = on
        try {
            wm.updateViewLayout(view, if (on) openParams() else bubbleParams())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Cầu nối JS -> Android. Các hàm này chạy trên luồng nền, nên chuyển sang luồng chính.
    private inner class Bridge {
        @JavascriptInterface
        fun setPopupOpen(on: Boolean) {
            main.post { applyOpen(on) }
        }

        @JavascriptInterface
        fun setSide(value: String) {
            main.post {
                side = if (value == "left") "left" else "right"
                if (!open) applyOpen(false)
            }
        }

        @JavascriptInterface
        fun decline() {
            main.post { onDecline() }
        }

        @JavascriptInterface
        fun toggleShare() {
            main.post { onShare() }
        }

        @JavascriptInterface
        fun sendChat(text: String) {
            main.post { onSend(text) }
        }
    }
}
