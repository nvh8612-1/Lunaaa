package com.lunaa.app.room

import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lunaa.app.service.LunaaCallService

/**
 * Màn hình phòng thoại toàn màn hình NẰM TRONG APP (không phải popup hay bong bóng mini).
 *
 * - Top bar kiểu Discord + badge "RoomID:000" ở góc trên bên phải.
 * - Khu vực giữa: các ô vuông bo tròn avatar tự động đổi số cột/bố cục khi người vào hoặc rời phòng;
 *   ai đang nói thì sáng viền xanh lá theo độ to nhỏ giọng nói.
 * - Thanh bar dưới cùng: 1 nút đỏ Decline, 1 nút Chia sẻ màn hình, 1 nút Mic.
 */
class VoiceRoomActivity : AppCompatActivity() {

    data class RoomParticipant(
        val id: String,
        val name: String,
        val avatarResId: Int = android.R.drawable.sym_def_app_icon,
        val voiceLevel: Float = 0f // 0f..1f
    )

    private lateinit var memberCountText: TextView
    private lateinit var roomIdBadge: TextView
    private lateinit var avatarGrid: GridLayout
    private lateinit var btnDecline: ImageButton
    private lateinit var btnShareScreen: ImageButton
    private lateinit var btnMic: ImageButton

    private var roomId: String = "000"
    private var preset: String = ""
    private var isMuted: Boolean = false
    private var isSharingScreen: Boolean = false

    private val participants = mutableListOf(
        RoomParticipant("u1", "Người 1", voiceLevel = 0f),
        RoomParticipant("u2", "Người 2", voiceLevel = 0f)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        roomId = intent.getStringExtra("ROOM_ID")?.takeIf { it.isNotBlank() } ?: "000"
        preset = intent.getStringExtra("PRESET") ?: ""

        setContentView(buildRoomLayout())
        renderParticipantsGrid()
    }

    /**
     * Gọi hàm này từ ViewModel / Socket / WebRTC khi có danh sách thành viên mới vào hoặc rời phòng.
     */
    fun updateParticipants(newList: List<RoomParticipant>) {
        participants.clear()
        participants.addAll(newList)
        renderParticipantsGrid()
    }

    /**
     * Gọi hàm này liên tục (0f..1f) khi có người nói để sáng viền xanh lá theo độ to nhỏ.
     */
    fun updateParticipantVoice(userId: String, level: Float) {
        val idx = participants.indexOfFirst { it.id == userId }
        if (idx == -1) return
        val clamped = level.coerceIn(0f, 1f)
        participants[idx] = participants[idx].copy(voiceLevel = clamped)

        val tile = avatarGrid.getChildAt(idx) as? FrameLayout ?: return
        applyTileVoiceBorder(tile, clamped)
    }

    private fun buildRoomLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(16), dp(14), dp(16), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#1E293B"), Color.parseColor("#090D16"))
            )
        }

        // 1. Thanh bar phong cách Discord + RoomID:000 ở góc trên bên phải
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#D90F172A"))
                setStroke(dp(1), Color.parseColor("#1AFFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(10)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#22C55E"))
            }
        }

        val channelTitle = TextView(this).apply {
            text = "Voice Channel"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        memberCountText = TextView(this).apply {
            text = " • ${participants.size} người"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        roomIdBadge = TextView(this).apply {
            text = "RoomID:$roomId"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#F21E293B"))
                setStroke(dp(1), Color.parseColor("#26FFFFFF"))
            }
        }

        topBar.addView(statusDot)
        topBar.addView(channelTitle)
        topBar.addView(memberCountText)
        topBar.addView(roomIdBadge)

        // 2. Khu vực giữa chứa lưới ô vuông bo tròn avatar tự động đổi bố cục
        val stage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(14)
            }
        }

        avatarGrid = GridLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        stage.addView(avatarGrid)

        // 3. Thanh bar dưới cùng: 1 nút đỏ Decline, 1 nút Chia sẻ màn hình, 1 nút Mic
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#E00F172A"))
                setStroke(dp(1), Color.parseColor("#1AFFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnDecline = createCircleButton(
            bgColor = "#DC2626",
            iconRes = android.R.drawable.ic_menu_close_clear_cancel,
            contentDesc = "Decline"
        ).apply {
            setOnClickListener {
                val intent = Intent(this@VoiceRoomActivity, LunaaCallService::class.java).apply {
                    action = LunaaCallService.ACTION_STOP_CALL
                }
                startService(intent)
                finish()
            }
        }

        btnShareScreen = createCircleButton(
            bgColor = "#2563EB",
            iconRes = android.R.drawable.ic_menu_share,
            contentDesc = "Chia sẻ màn hình"
        ).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                marginStart = dp(20)
                marginEnd = dp(20)
            }
            setOnClickListener {
                isSharingScreen = !isSharingScreen
                updateShareButtonStyle()
            }
        }

        btnMic = createCircleButton(
            bgColor = "#334155",
            iconRes = android.R.drawable.ic_btn_speak_now,
            contentDesc = "Mic"
        ).apply {
            setOnClickListener {
                isMuted = !isMuted
                updateMicButtonStyle()
            }
        }

        bottomBar.addView(btnDecline)
        bottomBar.addView(btnShareScreen)
        bottomBar.addView(btnMic)

        root.addView(topBar)
        root.addView(stage)
        root.addView(bottomBar)
        return root
    }

    private fun renderParticipantsGrid() {
        val count = participants.size.coerceAtLeast(1)
        val cols = when {
            count <= 1 -> 1
            count <= 4 -> 2
            else -> 3
        }
        val rows = ((count + cols - 1) / cols).coerceAtLeast(1)

        memberCountText.text = " • ${participants.size} người"
        roomIdBadge.text = "RoomID:$roomId"

        avatarGrid.removeAllViews()
        avatarGrid.columnCount = cols
        avatarGrid.rowCount = rows

        val screenWidth = resources.displayMetrics.widthPixels
        val horizontalPadding = dp(32)
        val gap = dp(12)
        val tileSize = ((screenWidth - horizontalPadding - gap * (cols - 1)) / cols)
            .coerceAtMost(dp(240))
            .coerceAtLeast(dp(84))

        participants.forEachIndexed { index, person ->
            val tile = buildRoundedSquareAvatarTile(person, tileSize)
            val row = index / cols
            val col = index % cols

            val lp = GridLayout.LayoutParams().apply {
                width = tileSize
                height = tileSize
                rowSpec = GridLayout.spec(row)
                columnSpec = GridLayout.spec(col)
                if (col < cols - 1) rightMargin = gap
                if (row < rows - 1) bottomMargin = gap
            }
            tile.layoutParams = lp
            avatarGrid.addView(tile)
        }
    }

    private fun buildRoundedSquareAvatarTile(person: RoomParticipant, size: Int): FrameLayout {
        val radiusPx = dp(24).toFloat()

        val avatar = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(person.avatarResId)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(20).toFloat())
                }
            }
            clipToOutline = true
        }

        val nameTag = TextView(this).apply {
            text = person.name
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 11f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#B8020617"))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                leftMargin = dp(8)
                bottomMargin = dp(8)
            }
        }

        val tile = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
            clipToOutline = true
            addView(avatar)
            addView(nameTag)
        }

        applyTileVoiceBorder(tile, person.voiceLevel)
        return tile
    }

    private fun applyTileVoiceBorder(tile: FrameLayout, level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        val scale = 1f + (clamped * 0.04f)
        tile.scaleX = scale
        tile.scaleY = scale

        val strokeWidth = if (clamped > 0.05f) {
            dp(2) + (clamped * dp(3)).toInt()
        } else {
            dp(1)
        }
        val strokeColor = if (clamped > 0.05f) {
            val alpha = (110 + (clamped * 145)).toInt().coerceIn(0, 255)
            Color.argb(alpha, 34, 197, 94) // Viền xanh lá theo độ to nhỏ giọng nói
        } else {
            Color.parseColor("#24FFFFFF")
        }

        tile.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(Color.parseColor("#D90F172A"))
            setStroke(strokeWidth, strokeColor)
        }
    }

    private fun createCircleButton(bgColor: String, iconRes: Int, contentDesc: String): ImageButton {
        return ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
            this.contentDescription = contentDesc
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(bgColor))
            }
        }
    }

    private fun updateShareButtonStyle() {
        btnShareScreen.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (isSharingScreen) "#22C55E" else "#2563EB"))
        }
    }

    private fun updateMicButtonStyle() {
        btnMic.alpha = if (isMuted) 0.55f else 1f
        btnMic.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (isMuted) "#475569" else "#334155"))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        fun start(context: android.content.Context, roomId: String = "000", preset: String = "") {
            val intent = Intent(context, VoiceRoomActivity::class.java).apply {
                putExtra("ROOM_ID", roomId)
                putExtra("PRESET", preset)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && context !is AppCompatActivity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }
    }
}
