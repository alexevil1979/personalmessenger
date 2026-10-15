package ru.pm.messenger

import android.content.Context
import android.media.AudioManager
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
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
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack
import org.webrtc.MediaStream
import org.webrtc.DataChannel
import ru.pm.messenger.data.RemoteIce

class CallEngine(
    private val context: Context,
) {
    private val egl = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var connection: PeerConnection? = null
    private var capturer: VideoCapturer? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var localVideo: VideoTrack? = null
    private var renderersReady = false
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    fun attach(local: SurfaceViewRenderer, remote: SurfaceViewRenderer) {
        localRenderer = local
        remoteRenderer = remote
        if (!renderersReady) {
            local.init(egl.eglBaseContext, null)
            remote.init(egl.eglBaseContext, null)
            local.setMirror(true)
            local.setEnableHardwareScaler(true)
            remote.setEnableHardwareScaler(true)
            renderersReady = true
        }
        localVideo?.addSink(local)
    }

    fun start(kind: String, ice: List<RemoteIce>, onIce: (IceCandidate) -> Unit, onReady: (SessionDescription) -> Unit, offer: Boolean) {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.isSpeakerphoneOn = kind == "video"
        ensureFactory()
        val servers = ice.map { item ->
            val builder = PeerConnection.IceServer.builder(item.urls)
            if (item.username.isNotBlank()) builder.setUsername(item.username).setPassword(item.credential)
            builder.createIceServer()
        }
        connection = factory?.createPeerConnection(
            PeerConnection.RTCConfiguration(servers),
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
                override fun onIceCandidate(candidate: IceCandidate) = onIce(candidate)
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                override fun onAddStream(stream: MediaStream) = Unit
                override fun onRemoveStream(stream: MediaStream) = Unit
                override fun onDataChannel(channel: DataChannel) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit
                override fun onTrack(transceiver: RtpTransceiver) {
                    val track = transceiver.receiver.track()
                    if (track is VideoTrack) remoteRenderer?.let { track.addSink(it) }
                }
            },
        )
        val audioSource: AudioSource = factory!!.createAudioSource(MediaConstraints())
        val audioTrack: AudioTrack = factory!!.createAudioTrack("audio0", audioSource)
        audioTrack.setEnabled(true)
        connection?.addTrack(audioTrack, listOf("call"))
        if (kind == "video") addCamera()
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        if (offer) {
            connection?.createOffer(sdpObserver(onReady), constraints)
        }
    }

    fun acceptOffer(sdp: String, onAnswer: (String) -> Unit) {
        val description = SessionDescription(SessionDescription.Type.OFFER, sdp)
        connection?.setRemoteDescription(object : SimpleSdp() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                flushIce()
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
                connection?.createAnswer(sdpObserver { created ->
                    onAnswer(created.description)
                }, constraints)
            }
        }, description)
    }

    fun acceptAnswer(sdp: String) {
        connection?.setRemoteDescription(object : SimpleSdp() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                flushIce()
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun addIce(sdp: String, mid: String, index: Int) {
        if (sdp.isBlank()) return
        val candidate = IceCandidate(mid, index, sdp)
        if (!remoteDescriptionSet) pendingIce += candidate else connection?.addIceCandidate(candidate)
    }

    fun stop() {
        capturer?.runCatching { stopCapture() }
        capturer?.dispose()
        capturer = null
        connection?.close()
        connection?.dispose()
        connection = null
        factory?.dispose()
        factory = null
        localRenderer?.release()
        remoteRenderer?.release()
        egl.release()
        val audio = context.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_NORMAL
        audio.isSpeakerphoneOn = false
    }

    private fun addCamera() {
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull() ?: return
        val camera = enumerator.createCapturer(name, null) ?: return
        val helper = SurfaceTextureHelper.create("pm-camera", egl.eglBaseContext)
        val source = factory!!.createVideoSource(camera.isScreencast)
        camera.initialize(helper, context, source.capturerObserver)
        camera.startCapture(640, 480, 24)
        val track: VideoTrack = factory!!.createVideoTrack("video0", source)
        localVideo = track
        localRenderer?.let { track.addSink(it) }
        connection?.addTrack(track, listOf("call"))
        capturer = camera
    }

    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun flushIce() {
        pendingIce.forEach { connection?.addIceCandidate(it) }
        pendingIce.clear()
    }

    private fun sdpObserver(onReady: (SessionDescription) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {
            connection?.setLocalDescription(SimpleSdp(), description)
            onReady(description)
        }
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    private open class SimpleSdp : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }
}
