package com.lunaa.app.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lunaa.app.service.LunaaCallService
import kotlin.math.abs

class FloatingPopupManager(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatingView: View? = null
    private var onExpandToggleListener: (() -> Unit)? = null

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun baseParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

    fun showAvatarOverlay(avatarUrl: String, onToggle: () -> Unit) {
        if (floatingView != null) return
        onExpandToggleListener = onToggle

        val params = baseParams().apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        val container = FrameLayout(context).apply {
            background = context.getDrawable(android.R.drawable.dialog_holo_dark_frame)
            setPadding(12, 12, 12, 12)
        }

        val imageView = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            layoutParams = FrameLayout.LayoutParams(120, 120)
        }
        container.addView(imageView)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = abs(event.rawX - initialTouchX)
                    val diffY = abs(event.rawY - initialTouchY)
                    if (diffX < 10 && diffY < 10) {
                        onExpandToggleListener?.invoke()
                    }
                    true
                }
                else -> false
            }
        }

        floatingView = container
        windowManager.addView(container, params)
    }

    fun showRejoinPopup(roomId: String, preset: String) {
        if (floatingView != null) return

        val params = baseParams().apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 150
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(android.R.drawable.dialog_holo_dark_frame)
            setPadding(24, 20, 24, 20)
            elevation = 16f
        }

        container.addView(TextView(context).apply {
            text = "Tham gia lại RoomID: $roomId"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        })

        container.addView(TextView(context).apply {
            text = "Bấm để tham gia lại hoặc từ chối"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(0, 4, 0, 10)
        })

        val buttonLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        buttonLayout.addView(Button(context).apply {
            text = "Từ chối"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = context.getDrawable(android.R.drawable.btn_default)
            setOnClickListener { removeOverlay() }
        })

        buttonLayout.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(16, 0)
        })

        buttonLayout.addView(Button(context).apply {
            text = "Tham gia"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = context.getDrawable(android.R.drawable.btn_default)
            setOnClickListener {
                val intent = Intent(context, LunaaCallService::class.java).apply {
                    action = LunaaCallService.ACTION_START_CALL
                    putExtra("ROOM_ID", roomId)
                    putExtra("PRESET", preset)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                removeOverlay()
            }
        })

        container.addView(buttonLayout)

        floatingView = container
        windowManager.addView(container, params)
    }

    fun removeOverlay() {
        val view = floatingView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        floatingView = null
    }
}
