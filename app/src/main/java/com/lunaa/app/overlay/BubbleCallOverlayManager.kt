package com.lunaa.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lunaa.app.service.LunaaCallService
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bong bóng nổi mini 40dp bám viền trái/phải và pop-up bo tròn 2 bong bóng giọng nói.
 * Đã rà soát và khắc phục triệt để các lỗi về memory leak, an toàn luồng UI, 
 * quản lý WindowManager layout params và đồng bộ trạng thái.
 */
class BubbleCallOverlayManager(
    private val context: Context,
    private val roomId: String,
    private val preset: String,
    private val onToggleShareScreen: ((isSharing: Boolean) -> Unit)? = null
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bubbleSizePx = dp(40)
    private val edgeMarginPx = dp(6)
    private val idleTimeoutMs = 10_000L

    private var miniBubbleView: View? = null
    private var miniBubbleParams: WindowManager.LayoutParams? = null
    private var miniAvatarView: ImageView? = null
    private var miniBubbleBg: GradientDrawable? = null

    private var popupView: View? = null
    private var popupParams: WindowManager.LayoutParams? = null

    private var popupBubble1: FrameLayout? = null
    private var popupBubble2: FrameLayout? = null
    private var popupAvatar1: ImageView? = null
    private var popupAvatar2: ImageView? = null
    private var shareLabelView: TextView? = null
    private var shareBtnView: ImageButton? = null

    private var currentSide = Side.RIGHT
    private var isPeeking = false
    private var isDragging = false
    private var isSharingScreen = false

    private var downRawX = 0f
    private var downRawY = 0f
    private var initialX = 0
    private var initialY = 0
    private var moveAnimator: ValueAnimator? = null

    private var user1Name = "Người 1"
    private var user2Name = "Người 2"
    private var user1Level = 0f
    private var user2Level = 0f
    private var activeSpeakerIndex = 1

    private val idleHideRunnable = Runnable {
        snapToEdge(currentSide, peek = true)
    }

    private enum class Side { LEFT, RIGHT }

    fun showMiniBubble() {
        mainHandler.post {
            if (miniBubbleView != null) {
                resetIdleTimer()
                return@post
            }

            // Kiểm tra quyền Overlay trước khi add view để tránh crash
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(context)) {
                return@post
            }

            val screenWidth = context.resources.displayMetrics.widthPixels

            val avatarView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(android.R.drawable.sym_def_app_icon)
                outlineProvider = ovalOutlineProvider()
                clipToOutline = true
            }

            val bubbleDrawable = createBubbleDrawable(speakingLevel = 0f, isMini = true)
            val bubbleContainer = FrameLayout(context).apply {
                background = bubbleDrawable
                setPadding(dp(2), dp(2), dp(2), dp(2))
                addView(
                    avatarView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                outlineProvider = ovalOutlineProvider()
                clipToOutline = true
            }

            val params = WindowManager.LayoutParams(
                bubbleSizePx,
                bubbleSizePx,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = screenWidth - bubbleSizePx - edgeMarginPx
                y = dp(180)
            }

            val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

            bubbleContainer.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        moveAnimator?.cancel()
                        mainHandler.removeCallbacks(idleHideRunnable)
                        isDragging = false
                        downRawX = event.rawX
                        downRawY = event.rawY
                        initialX = params.x
                        initialY = params.y

                        if (isPeeking) {
                            snapToEdge(currentSide, peek = false)
                            initialX = params.x
                        }
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            isDragging = true
                        }
                        if (isDragging) {
                            val maxY = context.resources.displayMetrics.heightPixels - bubbleSizePx - dp(10)
                            params.x = (initialX + dx).toInt()
                            params.y = max(dp(10), min(maxY, (initialY + dy).toInt()))
                            safeUpdateViewLayout(v, params)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!isDragging) {
                            showCallPopup()
                        } else {
                            val centerX = params.x + bubbleSizePx / 2
                            val targetSide = if (centerX < context.resources.displayMetrics.widthPixels / 2) {
                                Side.LEFT
                            } else {
                                Side.RIGHT
                            }
                            snapToEdge(targetSide, peek = false)
                        }
                        isDragging = false
                        resetIdleTimer()
                        true
                    }

                    else -> false
                }
            }

            miniAvatarView = avatarView
            miniBubbleBg = bubbleDrawable
            miniBubbleView = bubbleContainer
            miniBubbleParams = params

            try {
                windowManager.addView(bubbleContainer, params)
                updateMiniActiveSpeakerAvatar()
                resetIdleTimer()
            } catch (e: Exception) {
                e.printStackTrace()
                miniBubbleView = null
            }
        }
    }

    fun updateVoiceLevels(
        level1: Float,
        level2: Float,
        name1: String = user1Name,
        name2: String = user2Name
    ) {
        mainHandler.post {
            user1Name = name1
            user2Name = name2
            user1Level = level1.coerceIn(0f, 1f)
            user2Level = level2.coerceIn(0f, 1f)

            val threshold = 0.06f
            if (user1Level > threshold || user2Level > threshold) {
                activeSpeakerIndex = if (user1Level >= user2Level) 1 else 2
            }

            if (popupView == null && miniBubbleView != null) {
                updateMiniActiveSpeakerAvatar()
                val activeLevel = if (activeSpeakerIndex == 1) user1Level else user2Level
                applyBubbleVoiceStyle(miniBubbleView, miniBubbleBg, activeLevel, isMini = true)
            }

            if (popupView != null) {
                applyPopupBubbleState(popupBubble1, user1Level)
                applyPopupBubbleState(popupBubble2, user2Level)
            }
        }
    }

    fun setAvatars(avatar1: BitmapDrawable?, avatar2: BitmapDrawable?) {
        mainHandler.post {
            avatar1?.let {
                popupAvatar1?.setImageDrawable(it)
                if (activeSpeakerIndex == 1) miniAvatarView?.setImageDrawable(it)
            }
            avatar2?.let {
                popupAvatar2?.setImageDrawable(it)
                if (activeSpeakerIndex == 2) miniAvatarView?.setImageDrawable(it)
            }
        }
    }

    fun setSharingScreenState(sharing: Boolean) {
        mainHandler.post {
            isSharingScreen = sharing
            shareLabelView?.text = if (isSharingScreen) "Đang chia sẻ" else "Chia sẻ màn hình"
            shareBtnView?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(if (isSharingScreen) "#22C55E" else "#2563EB"))
            }
        }
    }

    fun showCallPopup() {
        mainHandler.post {
            if (popupView != null) return@post
            mainHandler.removeCallbacks(idleHideRunnable)

            // Kiểm tra quyền Overlay
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(context)) {
                return@post
            }

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(18), dp(16), dp(18), dp(20))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(28).toFloat()
                    setColor(Color.parseColor("#EB111827"))
                    setStroke(dp(1), Color.parseColor("#33FFFFFF"))
                }
                elevation = dp(12).toFloat()
            }

            val topBar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
            }

            val btnBack = TextView(context).apply {
                text = "<"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor("#E61E293B"))
                    setStroke(dp(1), Color.parseColor("#29FFFFFF"))
                }
                setOnClickListener {
                    dismissPopupOnly()
                }
            }

            val titleTv = TextView(context).apply {
                text = "Cuộc gọi thoại"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(34)
                }
            }

            topBar.addView(btnBack)
            topBar.addView(titleTv)

            val bubblesRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(22))
            }

            val (b1, av1, tv1) = createParticipantBubble(user1Name)
            val (b2, av2, tv2) = createParticipantBubble(user2Name)

            val col1 = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(b1)
                addView(tv1)
            }

            val col2 = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(26) }
                addView(b2)
                addView(tv2)
            }

            bubblesRow.addView(col1)
            bubblesRow.addView(col2)

            val btnDecline = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setColorFilter(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#DC2626"))
                }
                setOnClickListener {
                    val intent = Intent(context, LunaaCallService::class.java).apply {
                        action = LunaaCallService.ACTION_STOP_CALL
                    }
                    context.startService(intent)
                    removeOverlay()
                }
            }

            val declineLabel = TextView(context).apply {
                text = "Decline"
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            }

            val declineCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(btnDecline)
                addView(declineLabel)
            }

            val btnShareScreen = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
                setImageResource(android.R.drawable.ic_menu_share)
                setColorFilter(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#2563EB"))
                }
                setOnClickListener {
                    isSharingScreen = !isSharingScreen
                    setSharingScreenState(isSharingScreen)
                    onToggleShareScreen?.invoke(isSharingScreen)
                }
            }

            val shareTv = TextView(context).apply {
                text = if (isSharingScreen) "Đang chia sẻ" else "Chia sẻ màn hình"
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            }

            val shareCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(28) }
                addView(btnShareScreen)
                addView(shareTv)
            }

            val actionsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(declineCol)
                addView(shareCol)
            }

            card.addView(topBar)
            card.addView(bubblesRow)
            card.addView(actionsRow)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            popupBubble1 = b1
            popupBubble2 = b2
            popupAvatar1 = av1
            popupAvatar2 = av2
            shareLabelView = shareTv
            shareBtnView = btnShareScreen
            popupView = card
            popupParams = params

            setSharingScreenState(isSharingScreen)
            applyPopupBubbleState(popupBubble1, user1Level)
            applyPopupBubbleState(popupBubble2, user2Level)

            try {
                windowManager.addView(card, params)
            } catch (e: Exception) {
                e.printStackTrace()
                popupView = null
            }
        }
    }

    fun dismissPopupOnly() {
        mainHandler.post {
            popupView?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {}
            }
            popupView = null
            popupParams = null
            popupBubble1 = null
            popupBubble2 = null
            popupAvatar1 = null
            popupAvatar2 = null
            shareLabelView = null
            shareBtnView = null
            resetIdleTimer()
        }
    }

    fun removeOverlay() {
        mainHandler.post {
            mainHandler.removeCallbacks(idleHideRunnable)
            moveAnimator?.cancel()
            dismissPopupOnly()
            miniBubbleView?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {}
            }
            miniBubbleView = null
            miniBubbleParams = null
            miniAvatarView = null
            miniBubbleBg = null
        }
    }

    private fun createParticipantBubble(label: String): Triple<FrameLayout, ImageView, TextView> {
        val size = dp(68)
        val avatar = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(android.R.drawable.sym_def_app_icon)
            outlineProvider = ovalOutlineProvider()
            clipToOutline = true
        }

        val bubble = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = createBubbleDrawable(speakingLevel = 0f, isMini = false)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(
                avatar,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            outlineProvider = ovalOutlineProvider()
            clipToOutline = true
        }

        val nameTv = TextView(context).apply {
            text = label
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }

        return Triple(bubble, avatar, nameTv)
    }

    private fun createBubbleDrawable(speakingLevel: Float, isMini: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(
                Color.parseColor("#7338BDF8"),
                Color.parseColor("#66A855F7"),
                Color.parseColor("#7334D399")
            )
            val strokeWidth = if (speakingLevel > 0.05f) {
                dp(if (isMini) 2 else 3) + (speakingLevel * dp(3)).toInt()
            } else {
                dp(1)
            }
            val strokeColor = if (speakingLevel > 0.05f) {
                val alpha = (100 + (speakingLevel * 155)).toInt().coerceIn(0, 255)
                Color.argb(alpha, 34, 197, 94)
            } else {
                Color.parseColor("#BFFFFFFF")
            }
            setStroke(strokeWidth, strokeColor)
        }
    }

    private fun applyPopupBubbleState(bubble: FrameLayout?, level: Float) {
        val view = bubble ?: return
        val scale = 1f + (level * 0.14f)
        view.scaleX = scale
        view.scaleY = scale
        view.background = createBubbleDrawable(speakingLevel = level, isMini = false)
    }

    private fun applyBubbleVoiceStyle(
        view: View?,
        drawable: GradientDrawable?,
        level: Float,
        isMini: Boolean
    ) {
        val targetView = view ?: return
        val bg = drawable ?: return
        val scale = 1f + (level * 0.15f)
        targetView.scaleX = scale
        targetView.scaleY = scale

        val strokeWidth = if (level > 0.05f) {
            dp(if (isMini) 2 else 3) + (level * dp(2)).toInt()
        } else {
            dp(1)
        }
        val strokeColor = if (level > 0.05f) {
            val alpha = (100 + (level * 155)).toInt().coerceIn(0, 255)
            Color.argb(alpha, 34, 197, 94)
        } else {
            Color.parseColor("#BFFFFFFF")
        }
        bg.setStroke(strokeWidth, strokeColor)
    }

    private fun updateMiniActiveSpeakerAvatar() {
        val sourceAvatar = if (activeSpeakerIndex == 1) popupAvatar1?.drawable else popupAvatar2?.drawable
        sourceAvatar?.let { miniAvatarView?.setImageDrawable(it) }
    }

    private fun snapToEdge(side: Side, peek: Boolean) {
        val view = miniBubbleView ?: return
        val params = miniBubbleParams ?: return

        currentSide = side
        isPeeking = peek

        val screenWidth = context.resources.displayMetrics.widthPixels
        val halfHidden = bubbleSizePx / 2

        val targetX = when {
            peek && side == Side.LEFT -> -halfHidden
            peek && side == Side.RIGHT -> screenWidth - halfHidden
            side == Side.LEFT -> edgeMarginPx
            else -> screenWidth - bubbleSizePx - edgeMarginPx
        }
        val targetAlpha = if (peek) 0.38f else 1f

        moveAnimator?.cancel()
        val startX = params.x
        val startAlpha = view.alpha

        moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = OvershootInterpolator(0.85f)
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                params.x = (startX + (targetX - startX) * f).toInt()
                view.alpha = startAlpha + (targetAlpha - startAlpha) * f
                safeUpdateViewLayout(view, params)
            }
            start()
        }
    }

    private fun resetIdleTimer() {
        mainHandler.removeCallbacks(idleHideRunnable)
        if (popupView == null && miniBubbleView != null) {
            mainHandler.postDelayed(idleHideRunnable, idleTimeoutMs)
        }
    }

    private fun ovalOutlineProvider(): ViewOutlineProvider {
        return object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }

    private fun safeUpdateViewLayout(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
