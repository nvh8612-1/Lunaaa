package com.lunaa.app.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.*

class ScreenCapturer(
    private val context: Context,
    private val resultCode: Int,
    private val resultData: Intent
) {
    private var screenCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null

    fun createVideoTrack(peerConnectionFactory: PeerConnectionFactory, preset: String): VideoTrack {
        val width: Int
        val height: Int
        val fps: Int

        if (preset == "780p") {
            width = 1280
            height = 720 // ~780p standard
            fps = 45
        } else {
            width = 854
            height = 480 // 480p
            fps = 30
        }

        screenCapturer = createScreenCapturer()
        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCapturerThread", EglBase.create().eglBaseContext)
        videoSource = peerConnectionFactory.createVideoSource(false)

        screenCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        screenCapturer?.startCapture(width, height, fps)

        return peerConnectionFactory.createVideoTrack("LunaaScreenTrack", videoSource)
    }

    private fun createScreenCapturer(): VideoCapturer {
        return ScreenCapturerAndroid(resultData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
            }
        })
    }

    fun stopCapture() {
        try {
            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
            surfaceTextureHelper?.dispose()
            videoSource?.dispose()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        Log.d(TAG, "ScreenCapturer stopped")
    }

    companion object {
        private const val TAG = "ScreenCapturer"
    }
}
