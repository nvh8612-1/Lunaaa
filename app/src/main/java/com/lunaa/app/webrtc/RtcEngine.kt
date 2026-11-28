package com.lunaa.app.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*

class RtcEngine(
    private val context: Context,
    private val listener: RtcListener
) {
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private val eglBase: EglBase = EglBase.create()

    interface RtcListener {
        fun onLocalDescriptionCreated(sdp: SessionDescription)
        fun onIceCandidateGenerated(candidate: IceCandidate)
        fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
    }

    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun createPeerConnection(iceServers: List<PeerConnection.IceServer>) {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { listener.onIceCandidateGenerated(it) }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                newState?.let { listener.onConnectionStateChanged(it) }
            }
        })

        val audioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())
        audioTrack = peerConnectionFactory?.createAudioTrack("LunaaAudioTrack", audioSource)
        audioTrack?.let { peerConnection?.addTrack(it, listOf("lunaa_stream")) }
    }

    fun addVideoTrack(track: VideoTrack) {
        videoTrack = track
        peerConnection?.addTrack(track, listOf("lunaa_stream"))
    }

    fun createOffer() {
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) setLocal(desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { Log.e(TAG, "createOffer failed: $error") }
            override fun onSetFailure(error: String?) {}
        }, offerConstraints())
    }

    fun createAnswer() {
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) setLocal(desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { Log.e(TAG, "createAnswer failed: $error") }
            override fun onSetFailure(error: String?) {}
        }, offerConstraints())
    }

    private fun setLocal(desc: SessionDescription) {
        peerConnection?.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() { listener.onLocalDescriptionCreated(desc) }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) { Log.e(TAG, "setLocalDescription failed: $error") }
        }, desc)
    }

    fun setRemoteDescription(type: SessionDescription.Type, sdp: String) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() { Log.d(TAG, "Remote description set") }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) { Log.e(TAG, "setRemoteDescription failed: $error") }
        }, SessionDescription(type, sdp))
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun getFactory(): PeerConnectionFactory? = peerConnectionFactory

    fun dispose() {
        try {
            peerConnection?.dispose()
            peerConnection = null
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
            eglBase.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        Log.d(TAG, "RtcEngine disposed")
    }

    private fun offerConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }

    companion object {
        private const val TAG = "RtcEngine"
    }
}
