package com.lunaa.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

// Bong bóng chat nổi trên mọi màn hình. Bấm bong bóng để mở khung chat nhỏ.
class ChatBubbleManager(private val context: Context, private val onSend: (String) -> Unit) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private var bubble: TextView? = null
    private var panel: View? = null
    private var messageBox: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var unread = 0
    private val messages = ArrayList<Triple<String, String, Boolean>>()

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    fun show() {
        if (bubble != null || !Settings.canDrawOverlays(context)) return

        val view = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#5865F2"))
            }
        }
        val params = WindowManager.LayoutParams(
            dp(56), dp(56), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(220)
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = e.rawX
                    touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.rawX - touchX) < 10 && abs(e.rawY - touchY) < 10) togglePanel()
                    true
                }
                else -> false
            }
        }

        wm.addView(view, params)
        bubble = view
        refreshBubble()
    }

    fun hide() {
        hidePanel()
        bubble?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        bubble = null
        unread = 0
    }

    fun reset() {
        hide()
        messages.clear()
    }

    fun addMessage(name: String, text: String, self: Boolean) {
        messages.add(Triple(name, text, self))
        if (messages.size > 100) messages.removeAt(0)
        if (panel != null) {
            appendRow(messages.last())
        } else if (!self) {
            unread++
            refreshBubble()
        }
    }

    private fun refreshBubble() {
        bubble?.text = if (unread > 0) unread.toString() else "Chat"
    }

    private fun togglePanel() {
        if (panel != null) {
            hidePanel()
        } else {
            unread = 0
            refreshBubble()
            showPanel()
        }
    }

    private fun showPanel() {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#2B2D31"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(context).apply {
                text = "Trò chuyện"
                setTextColor(Color.WHITE)
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(Button(context).apply {
            text = "Đóng"
            setOnClickListener { hidePanel() }
        })
        box.addView(header)

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(context).apply { addView(list) }
        box.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260)))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(context).apply {
            hint = "Nhắn trong phòng"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(context).apply {
            text = "Gửi"
            setOnClickListener {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    input.setText("")
                    onSend(t)
                }
            }
        })
        box.addView(row)

        val params = WindowManager.LayoutParams(
            dp(300), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(80)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        wm.addView(box, params)
        panel = box
        messageBox = list
        scroll = sv
        messages.forEach { appendRow(it) }
    }

    private fun hidePanel() {
        panel?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        panel = null
        messageBox = null
        scroll = null
    }

    private fun appendRow(m: Triple<String, String, Boolean>) {
        val box = messageBox ?: return
        box.addView(TextView(context).apply {
            text = "${m.first}: ${m.second}"
            setTextColor(if (m.third) Color.parseColor("#B5BAFF") else Color.WHITE)
            textSize = 14f
            setPadding(0, dp(4), 0, dp(4))
        })
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }
}
