package ir.elambar.mobl

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One-to-one peer media. Signaling is sent through the authenticated Supabase call tables.
 * STUN is configured as a best-effort direct path; production reliability across restrictive NATs
 * still requires a private TURN service and credentials.
 */
internal class WebRtcCallEngine(
    context: Context,
    private val videoEnabled: Boolean,
    eglBase: EglBase,
    localRenderer: SurfaceViewRenderer?,
    remoteRenderer: SurfaceViewRenderer?,
    private val onSignal: (String, JSONObject) -> Unit,
    private val onState: (String) -> Unit
) {
    companion object {
        @Volatile private var initialized = false
        private fun initialize(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (!initialized) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                            .createInitializationOptions()
                    )
                    initialized = true
                }
            }
        }
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val eglContext = eglBase.eglBaseContext
    private val factory: PeerConnectionFactory
    private val peerConnection: PeerConnection
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var remoteDescriptionSet = false
    private val queuedCandidates = ConcurrentLinkedQueue<IceCandidate>()
    @Volatile private var closed = false

    init {
        initialize(appContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = requireNotNull(factory.createPeerConnection(configuration, observer())) {
            "ساخت ارتباط WebRTC ناموفق بود."
        }

        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("elambar-audio", audioSource)
        audioTrack.setEnabled(true)
        peerConnection.addTrack(audioTrack, listOf("elambar-stream"))

        if (videoEnabled) {
            val enumerator = Camera2Enumerator(appContext)
            val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
                ?: throw IllegalStateException("دوربین در دسترس نیست.")
            videoCapturer = enumerator.createCapturer(cameraName, null)
                ?: throw IllegalStateException("راه‌اندازی دوربین ممکن نشد.")
            videoSource = factory.createVideoSource(false)
            surfaceTextureHelper = SurfaceTextureHelper.create("ElambarCamera", eglContext)
            videoCapturer!!.initialize(surfaceTextureHelper, appContext, videoSource!!.capturerObserver)
            videoCapturer!!.startCapture(640, 480, 24)
            videoTrack = factory.createVideoTrack("elambar-video", videoSource)
            videoTrack!!.setEnabled(true)
            localRenderer?.let {
                it.setMirror(true)
                videoTrack!!.addSink(it)
            }
            peerConnection.addTrack(videoTrack, listOf("elambar-stream"))
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = videoEnabled
        onState("در انتظار برقراری ارتباط امن...")
    }

    fun createOffer() {
        if (closed) return
        peerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description == null) return
                setLocalAndSignal(description, "offer")
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { onState("ساخت پیشنهاد تماس ناموفق بود: ${error.orEmpty()}") }
            override fun onSetFailure(error: String?) { onState("ثبت پیشنهاد تماس ناموفق بود: ${error.orEmpty()}") }
        }, MediaConstraints())
    }

    fun setRemoteOffer(sdp: String) {
        if (closed) return
        val description = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                flushCandidates()
                createAnswer()
            }
            override fun onSetFailure(error: String?) { onState("دریافت پیشنهاد تماس ناموفق بود: ${error.orEmpty()}") }
            override fun onCreateSuccess(description: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, description)
    }

    fun setRemoteAnswer(sdp: String) {
        if (closed) return
        val description = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                flushCandidates()
                onState("تماس برقرار شد.")
            }
            override fun onSetFailure(error: String?) { onState("پاسخ تماس ناموفق بود: ${error.orEmpty()}") }
            override fun onCreateSuccess(description: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, description)
    }

    fun addRemoteCandidate(payload: JSONObject) {
        if (closed) return
        val candidate = runCatching {
            IceCandidate(
                payload.optString("sdpMid").ifBlank { "0" },
                payload.optInt("sdpMLineIndex"),
                payload.optString("candidate")
            )
        }.getOrNull() ?: return
        if (remoteDescriptionSet) peerConnection.addIceCandidate(candidate)
        else queuedCandidates.add(candidate)
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        audioTrack.setEnabled(enabled)
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoTrack?.setEnabled(false) }
        runCatching { audioTrack.setEnabled(false) }
        runCatching { peerConnection.close() }
        runCatching { peerConnection.dispose() }
        runCatching { videoCapturer?.dispose() }
        runCatching { videoTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { surfaceTextureHelper?.dispose() }
        runCatching { audioTrack.dispose() }
        runCatching { audioSource.dispose() }
        runCatching { factory.dispose() }
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun createAnswer() {
        peerConnection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description == null) return
                setLocalAndSignal(description, "answer")
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) { onState("ساخت پاسخ تماس ناموفق بود: ${error.orEmpty()}") }
            override fun onSetFailure(error: String?) { onState("ثبت پاسخ تماس ناموفق بود: ${error.orEmpty()}") }
        }, MediaConstraints())
    }

    private fun setLocalAndSignal(description: SessionDescription, signalType: String) {
        peerConnection.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() {
                val payload = JSONObject().put("type", description.type.canonicalForm()).put("sdp", description.description)
                onSignal(signalType, payload)
                onState(if (signalType == "offer") "در حال تماس‌گرفتن..." else "در حال پاسخ به تماس...")
            }
            override fun onSetFailure(error: String?) { onState("ثبت ارتباط محلی ناموفق بود: ${error.orEmpty()}") }
            override fun onCreateSuccess(description: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, description)
    }

    private fun flushCandidates() {
        while (true) {
            val candidate = queuedCandidates.poll() ?: break
            peerConnection.addIceCandidate(candidate)
        }
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            when (newState) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> onState("تماس برقرار شد.")
                PeerConnection.IceConnectionState.FAILED -> onState("ارتباط مستقیم برقرار نشد؛ این شبکه ممکن است به سرور TURN نیاز داشته باشد.")
                PeerConnection.IceConnectionState.DISCONNECTED -> onState("ارتباط ناپایدار شد؛ در حال تلاش مجدد...")
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            onSignal("ice_candidate", JSONObject()
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                .put("candidate", candidate.sdp))
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: org.webrtc.MediaStream?) {}
        override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
        override fun onDataChannel(dataChannel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) {
            val track = receiver?.track()
            if (track is VideoTrack) remoteRenderer?.let { track.addSink(it) }
        }
        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track()
            if (track is VideoTrack) remoteRenderer?.let { track.addSink(it) }
        }
    }
}
