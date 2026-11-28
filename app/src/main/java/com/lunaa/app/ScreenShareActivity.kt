package com.lunaa.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.lunaa.app.service.LunaaCallService

// Mở hộp xin quyền ghi màn hình khi bấm Chia sẻ trên popup. Không hiện giao diện.
class ScreenShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SCREEN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCREEN && resultCode == RESULT_OK && data != null) {
            startService(Intent(this, LunaaCallService::class.java).apply {
                action = LunaaCallService.ACTION_START_SCREEN_SHARE
                putExtra("RESULT_DATA", data)
            })
        }
        finish()
    }

    companion object {
        private const val REQ_SCREEN = 201
    }
}
