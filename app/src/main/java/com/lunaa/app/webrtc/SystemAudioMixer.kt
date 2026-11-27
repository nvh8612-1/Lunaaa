package com.lunaa.app.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import java.nio.ByteBuffer

class SystemAudioMixer(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val sampleRate: Int = 44100,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_STEREO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    fun startMixing(onAudioDataCaptured: (ByteArray, Int) -> Unit) {
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormatObj = AudioFormat.Builder()
            .setEncoding(audioFormat)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, 4096)

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfiguration(config)
                .setAudioFormat(audioFormatObj)
                .setBufferSizeInBytes(bufferSize * 2)
                .build()

            audioRecord?.startRecording()
            isRecording = true

            recordingThread = Thread {
                val data = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(data, 0, data.size) ?: 0
                    if (read > 0) {
                        // Apply 50% volume mixing level as requested
                        mixAudioLevel(data, read, 0.5f)
                        onAudioDataCaptured(data, read)
                    }
                }
            }
            recordingThread?.start()
            Log.d(TAG, "SystemAudioMixer started successfully (50% volume mixing)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SystemAudioMixer", e)
        }
    }

    private fun mixAudioLevel(buffer: ByteArray, length: Int, level: Float) {
        // Simple PCM 16-bit volume scaling
        var i = 0
        while (i < length - 1) {
            var sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            if (sample > 32767) sample -= 65536
            
            sample = (sample * level).toInt()
            
            buffer[i] = (sample and 0xFF).toByte()
            buffer[i + 1] = ((sample shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    fun stopMixing() {
        isRecording = false
        recordingThread?.join(500)
        recordingThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
        Log.d(TAG, "SystemAudioMixer stopped")
    }

    companion object {
        private const val TAG = "SystemAudioMixer"
    }
}
