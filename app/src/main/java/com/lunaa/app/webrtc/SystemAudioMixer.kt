package com.lunaa.app.webrtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class SystemAudioMixer(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val sampleRate: Int = 44100,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_STEREO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var recordingThread: Thread? = null

    fun startMixing(onAudioDataCaptured: (ByteArray, Int) -> Unit) {
        // Thu âm hệ thống chỉ có từ Android 10 (API 29)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "System audio capture requires Android 10 (API 29) or higher")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBufferSize, 4096)

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize * 2)
                .build()

            audioRecord?.startRecording()
            isRecording = true

            recordingThread = Thread {
                val data = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(data, 0, data.size) ?: 0
                    if (read > 0) {
                        applyVolume(data, read, VOLUME_LEVEL)
                        onAudioDataCaptured(data, read)
                    }
                }
            }.also { it.start() }

            Log.d(TAG, "SystemAudioMixer started (volume ${VOLUME_LEVEL * 100}%)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SystemAudioMixer", e)
        }
    }

    // Nhân từng mẫu PCM 16-bit little-endian với hệ số âm lượng
    private fun applyVolume(buffer: ByteArray, length: Int, level: Float) {
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val scaled = (sample * level).toInt()
            buffer[i] = (scaled and 0xFF).toByte()
            buffer[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    fun stopMixing() {
        isRecording = false
        try {
            recordingThread?.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        recordingThread = null

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "stop() failed", e)
        }
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "SystemAudioMixer stopped")
    }

    companion object {
        private const val TAG = "SystemAudioMixer"
        private const val VOLUME_LEVEL = 0.5f
    }
}
