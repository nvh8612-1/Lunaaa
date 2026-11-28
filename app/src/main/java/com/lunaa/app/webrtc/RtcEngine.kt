package com.lunaa.app.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import android.media.MediaRecorder
import com.lunaa.app.DiagLog

private const val RTC_TAG = "RtcEngine"
private const val SCREEN_STREAM = "lunaa_screen"
private const val METER_ID = "__meter__"
private const val SELF_KEY = "self"

class RtcEngine(
    private val context: Context,
    private val listener: RtcListener
) {
    interface RtcListener {
        fun onLocalDescriptionCreated(peerId: String, sdp: SessionDescription)
        fun onIceCandidateGenerated(peerId: String, candidate: IceCandidate)
        fun onPeerStateChanged(peerId: String, state: PeerConnection.PeerConnectionState)
        fun onRemoteVideo(peerId: String, track: VideoTrack)
    }

    private val eglBase: EglBase = EglBase.create()
    private val adm: JavaAudioDeviceModule

    // Số lần WebRTC đưa mẫu âm thanh thu được. Bằng 0 nghĩa là bộ thu chưa chạy.
    @Volatile
    var sampleCount = 0L
        private set

    // Mức âm thanh mới nhất của micro, 0..1, tính từ mẫu PCM 16-bit
    @Volatile
    private var localLevel = 0.0

    private fun onSamples(samples: JavaAudioDeviceModule.AudioSamples) {
        sampleCount++
        if (sampleCount == 1L) DiagLog.write("WebRtcAudio", "Đã nhận mẫu âm thanh đầu tiên")
        val data = samples.data
        var peak = 0
        var i = 0
        while (i + 1 < data.size) {
            val v = ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)).toShort().toInt()
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
            i += 2
        }
        localLevel = peak / 32768.0
    }
    private val factory: PeerConnectionFactory
    private val audioTrack: AudioTrack
    private val peers = HashMap<String, PeerConnection>()
    private val senders = HashMap<String, RtpSender>()

    private var screenCapturer: ScreenCapturerAndroid? = null
    private var screenHelper: SurfaceTextureHelper? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var selfPc: PeerConnection? = null
    private var selfPeerB: PeerConnection? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        // Tự cấu hình thiết bị thu âm. Bật AEC/NS phần cứng làm AudioRecord lỗi trên nhiều máy,
        // khi đó WebRTC không thu được tiếng dù micro vẫn hoạt động.
        adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setSamplesReadyCallback(JavaAudioDeviceModule.SamplesReadyCallback { samples -> onSamples(samples) })
            .createAudioDeviceModule()
        DiagLog.write("WebRtcAudio", "ADM tạo xong, AEC/NS phần cứng đã tắt")
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        val source = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("LunaaAudioTrack", source)
    }

    // Context EGL dùng để hiển thị video nhận được
    fun eglContext(): EglBase.Context = eglBase.eglBaseContext

    fun hasPeer(id: String): Boolean = peers.containsKey(id)

    fun ensurePeer(id: String): PeerConnection? {
        peers[id]?.let { return it }
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = factory.createPeerConnection(config, observerFor(id)) ?: return null
        pc.addTrack(audioTrack, listOf("lunaa_stream"))
        screenTrack?.let { track ->
            pc.addTrack(track, listOf(SCREEN_STREAM))?.let { senders[id] = it }
        }
        peers[id] = pc
        return pc
    }

    fun createOffer(id: String) {
        val pc = ensurePeer(id) ?: return
        pc.createOffer(SdpAdapter(onCreated = { setLocal(id, pc, it) }), offerConstraints())
    }

    fun createAnswer(id: String) {
        val pc = ensurePeer(id) ?: return
        pc.createAnswer(SdpAdapter(onCreated = { setLocal(id, pc, it) }), offerConstraints())
    }

    private fun setLocal(id: String, pc: PeerConnection, desc: SessionDescription) {
        pc.setLocalDescription(SdpAdapter(onSet = { listener.onLocalDescriptionCreated(id, desc) }), desc)
    }

    fun setRemoteDescription(id: String, type: SessionDescription.Type, sdp: String) {
        val pc = ensurePeer(id) ?: return
        pc.setRemoteDescription(SdpAdapter(), SessionDescription(type, sdp))
    }

    fun addIceCandidate(id: String, candidate: IceCandidate) {
        peers[id]?.addIceCandidate(candidate)
    }

    fun setMicEnabled(enabled: Boolean) {
        audioTrack.setEnabled(enabled)
    }

    // ---- Chia sẻ màn hình ----

    fun startScreenShare(data: Intent, width: Int, height: Int) {
        if (screenTrack != null) return
        val capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(RTC_TAG, "MediaProjection đã dừng")
            }
        })
        val helper = SurfaceTextureHelper.create("ScreenCapture", eglBase.eglBaseContext)
        val source = factory.createVideoSource(true)
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(width, height, 15)

        screenCapturer = capturer
        screenHelper = helper
        screenSource = source
        screenTrack = factory.createVideoTrack("lunaa_screen_track", source)

        // Gắn track vào mọi kết nối đang có, mỗi kết nối tạo offer mới
        peers.keys.toList().forEach { attachScreen(it) }
    }

    private fun attachScreen(id: String) {
        val pc = peers[id] ?: return
        val track = screenTrack ?: return
        pc.addTrack(track, listOf(SCREEN_STREAM))?.let { senders[id] = it }
        createOffer(id)
    }

    fun stopScreenShare() {
        val track = screenTrack ?: return
        try {
            screenCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e(RTC_TAG, "stopCapture lỗi", e)
        }
        senders.forEach { (id, sender) -> peers[id]?.removeTrack(sender) }
        senders.clear()
        peers.keys.toList().forEach { createOffer(it) }

        screenCapturer?.dispose()
        screenCapturer = null
        screenHelper?.dispose()
        screenHelper = null
        screenSource?.dispose()
        screenSource = null
        track.dispose()
        screenTrack = null
    }

    // ---- Đo mức âm thanh: mic được mở ngay khi vào phòng, kể cả khi chưa có ai khác ----

    // Kết nối riêng, không gửi đi đâu. Mục đích: WebRTC chỉ mở mic khi track đã gắn vào một kết nối.
    fun ensureSelfMeter() {
        if (selfPc != null) return
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val a = factory.createPeerConnection(config, observerFor(METER_ID))
        val b = factory.createPeerConnection(config, observerFor(METER_ID))
        if (a == null || b == null) {
            DiagLog.write("WebRtcSdp", "không tạo được kết nối đo (a=${a != null}, b=${b != null})")
            return
        }
        a.addTrack(audioTrack, listOf("lunaa_meter"))
        selfPeerB = b

        // Đàm phán đủ offer/answer giữa a và b (không qua mạng). WebRTC chỉ mở mic khi đã đàm phán xong.
        a.createOffer(SdpAdapter(onCreated = { offer ->
            a.setLocalDescription(SdpAdapter(onSet = {
                b.setRemoteDescription(SdpAdapter(onSet = {
                    b.createAnswer(SdpAdapter(onCreated = { answer ->
                        b.setLocalDescription(SdpAdapter(onSet = {
                            a.setRemoteDescription(SdpAdapter(), answer)
                        }), answer)
                    }), offerConstraints())
                }), offer)
            }), offer)
        }), offerConstraints())
        selfPc = a
    }

    // Mức âm thanh 0..1 của mình (khóa "self") và của từng người khác (khóa là mã người đó)
    fun resetSelfMeter() {
        selfPc?.close()
        selfPc?.dispose()
        selfPc = null
        selfPeerB?.close()
        selfPeerB?.dispose()
        selfPeerB = null
        ensureSelfMeter()
    }

    fun collectLevels(onLevels: (Map<String, Double>) -> Unit) {
        val targets = HashMap(peers)
        val self = selfPc
        val levels = java.util.concurrent.ConcurrentHashMap<String, Double>()
        levels[SELF_KEY] = localLevel
        val remaining = java.util.concurrent.atomic.AtomicInteger(targets.size)
        if (remaining.get() == 0) {
            onLevels(levels)
            return
        }
        fun done() {
            if (remaining.decrementAndGet() == 0) onLevels(HashMap(levels))
        }
        targets.forEach { (id, pc) ->
            pc.getStats { report ->
                levels[id] = maxLevel(report, "inbound-rtp")
                done()
            }
        }
    }

    private fun maxLevel(report: RTCStatsReport, type: String): Double {
        var max = 0.0
        report.statsMap.values.forEach { s ->
            if (s.type == type) {
                val v = s.members["audioLevel"] as? Double
                if (v != null && v > max) max = v
            }
        }
        return max
    }

    fun removePeer(id: String) {
        senders.remove(id)
        peers.remove(id)?.let {
            it.close()
            it.dispose()
        }
    }

    fun dispose() {
        stopScreenShare()
        selfPc?.close()
        selfPc?.dispose()
        selfPc = null
        selfPeerB?.close()
        selfPeerB?.dispose()
        selfPeerB = null
        peers.values.forEach {
            it.close()
            it.dispose()
        }
        peers.clear()
        senders.clear()
        try {
            audioTrack.dispose()
            factory.dispose()
            adm.release()
            eglBase.release()
        } catch (e: Exception) {
            Log.e(RTC_TAG, "dispose lỗi", e)
        }
    }

    private fun observerFor(id: String): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (id != METER_ID) candidate?.let { listener.onIceCandidateGenerated(id, it) }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            val track = receiver?.track()
            if (track is VideoTrack) listener.onRemoteVideo(id, track)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            if (id != METER_ID) newState?.let { listener.onPeerStateChanged(id, it) }
        }
    }

    private open class SdpAdapter(
        private val onCreated: ((SessionDescription) -> Unit)? = null,
        private val onSet: (() -> Unit)? = null
    ) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {
            if (desc != null) onCreated?.invoke(desc)
        }
        override fun onSetSuccess() {
            onSet?.invoke()
        }
        override fun onCreateFailure(error: String?) {
            DiagLog.write("WebRtcSdp", "tạo SDP lỗi: $error")
        }
        override fun onSetFailure(error: String?) {
            DiagLog.write("WebRtcSdp", "đặt SDP lỗi: $error")
        }
    }

    private fun offerConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }
}
