package com.lunaa.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

// Popup nổi khi đang trong phòng mà bạn thoát ra app khác: Từ chối (rời phòng) | Chia sẻ màn hình
class CallPopupManager(
    private val context: Context,
    private val onDecline: () -> Unit,
    private val onShare: () -> Unit
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private var view: View? = null
    private var shareBtn: Button? = null

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun rounded(color: String): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Color.parseColor(color))
    }

    fun show(roomId: String) {
        if (view != null || !Settings.canDrawOverlays(context)) return

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#2B2D31")
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        box.addView(TextView(context).apply {
            text = "Đang trong phòng $roomId"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        box.addView(TextView(context).apply {
            text = "Cuộc gọi đang diễn ra"
            setTextColor(Color.parseColor("#23A559"))
            textSize = 12f
            setPadding(0, dp(2), 0, dp(12))
        })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val decline = Button(context).apply {
            text = "Từ chối"
            setTextColor(Color.WHITE)
            background = rounded("#DA373C")
            setOnClickListener { onDecline() }
        }
        val share = Button(context).apply {
            text = "Chia sẻ màn hình"
            setTextColor(Color.WHITE)
            background = rounded("#5865F2")
            setOnClickListener { onShare() }
        }
        val lpLeft = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(6)
        }
        val lpRight = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(6)
        }
        row.addView(decline, lpLeft)
        row.addView(share, lpRight)
        box.addView(row)

        val params = WindowManager.LayoutParams(
            dp(300), WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(60)
        }

        wm.addView(box, params)
        view = box
        shareBtn = share
    }

    // Đổi nút chia sẻ thành Dừng chia sẻ khi đang chia sẻ
    fun setSharing(on: Boolean) {
        shareBtn?.text = if (on) "Dừng chia sẻ" else "Chia sẻ màn hình"
    }

    fun hide() {
        view?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        view = null
        shareBtn = null
    }
}
