package com.lunaa.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.lunaa.app.R

class FloatingPopupManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatingView: View? = null
    private var isExpanded = false
    private var onExpandToggleListener: (() -> Unit)? = null

    fun showAvatarOverlay(avatarUrl: String, onToggle: () -> Unit) {
        if (floatingView != null) return
        onExpandToggleListener = onToggle

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        // Create simple floating layout programmatically to avoid complex XML inflation if needed
        val view = View.inflate(context, android.R.layout.activity_list_item, null) // or custom view
        // For simplicity, let's build custom container view
        val container = android.widget.FrameLayout(context).apply {
            background = context.getDrawable(android.R.drawable.dialog_holo_dark_frame)
            setPadding(12, 12, 12, 12)
        }

        val imageView = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            layoutParams = android.widget.FrameLayout.LayoutParams(120, 120)
        }
        container.addView(imageView)

        // Touch listener for dragging
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
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Tap action to expand/collapse
                    val diffX = Math.abs(event.rawX - initialTouchX)
                    val diffY = Math.abs(event.rawY - initialTouchY)
                    if (diffX < 10 && diffY < 10) {
                        onExpandToggleListener?.invoke()
                    }
                    true
                }
                else -> false
            }
        }

        floatingView = container
        windowManager.addView(floatingView, params)
    }

    fun showRejoinPopup(roomId: String, preset: String) {
        if (floatingView != null) return

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 150
        }

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = context.getDrawable(android.R.drawable.dialog_holo_dark_frame)
            setPadding(24, 20, 24, 20)
            elevation = 16f
        }

        val titleView = TextView(context).apply {
            text = "Tham gia lại RoomID: $roomId"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        container.addView(titleView)

        val subView = TextView(context).apply {
            text = "Bấm để tham gia lại hoặc từ chối"
            setTextColor(android.graphics.Color.LTGRAY)
            textSize = 12f
            setPadding(0, 4, 0, 10)
        }
        container.addView(subView)

        val buttonLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val btnDecline = android.widget.Button(context).apply {
            text = "Từ chối"
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            background = context.getDrawable(android.R.drawable.btn_default)
            setOnClickListener {
                removeOverlay()
            }
        }
        buttonLayout.addView(btnDecline)

        val space = android.view.View(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(16, 0)
        }
        buttonLayout.addView(space)

        val btnAccept = android.widget.Button(context).apply {
            text = "Tham gia"
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            background = context.getDrawable(android.R.drawable.btn_default)
            setOnClickListener {
                val intent = android.content.Intent(context, com.lunaa.app.service.LunaaCallService::class.java).apply {
                    action = com.lunaa.app.service.LunaaCallService.ACTION_START_CALL
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
        }
        buttonLayout.addView(btnAccept)

        container.addView(buttonLayout)

        floatingView = container
        windowManager.addView(floatingView, params)
    }
}
