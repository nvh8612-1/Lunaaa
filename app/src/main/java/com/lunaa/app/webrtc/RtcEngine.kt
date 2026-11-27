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
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
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

        // Create Audio Track for Mic Call
        val audioConstraints = MediaConstraints()
        val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        audioTrack = peerConnectionFactory?.createAudioTrack("LunaaAudioTrack", audioSource)
        audioTrack?.let { peerConnection?.addTrack(it, listOf("lunaa_stream")) }
    }

    fun addVideoTrack(track: VideoTrack) {
        videoTrack = track
        videoTrack?.let { peerConnection?.addTrack(it, listOf("lunaa_stream")) }
    }

    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() { listener.onLocalDescriptionCreated(desc) }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, it)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() { listener.onLocalDescriptionCreated(desc) }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, it)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun setRemoteDescription(type: SessionDescription.Type, sdp: String) {
        val desc = SessionDescription(type, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() { Log.d(TAG, "Remote description set successfully") }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, desc)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun getFactory(): PeerConnectionFactory? = peerConnectionFactory

    fun dispose() {
        try {
            peerConnection?.dispose()
            peerConnectionFactory?.dispose()
            eglBase.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        Log.d(TAG, "RtcEngine disposed")
    }

    companion object {
        private const val TAG = "RtcEngine"
    }
}
