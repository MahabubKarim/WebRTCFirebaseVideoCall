package com.mmk.webrtcfirebasevideocall.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.google.gson.Gson
import com.mmk.webrtcfirebasevideocall.BuildConfig
import com.mmk.webrtcfirebasevideocall.utils.DataModel
import com.mmk.webrtcfirebasevideocall.utils.DataModelType
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRTCClient @Inject constructor(
    private val context: Context,
    private val gson: Gson
) {
    //region Variables

    var webRTCClientListener: WebRTCClientListener? = null
    private lateinit var username: String

    // WebRTC context and factory
    private val eglBaseContext = EglBase.create().eglBaseContext
    private val peerConnectionFactory by lazy { createPeerConnectionFactory() }
    private var peerConnection: PeerConnection? = null

    // ICE server config (using STUN for NAT traversal)
    private val iceServer = listOf(
        /*
          urls: "turns:asia.relay.metered.ca:443?transport=tcp",
        username: "573851d670e2e5496de76bec",
        credential: "1VbM6LVlOBZIJsGe",
        * */
        PeerConnection.IceServer.builder("turns:asia.relay.metered.ca:443?transport=tcp")
            .setUsername("573851d670e2e5496de76bec")
            .setPassword("1VbM6LVlOBZIJsGe")
            .createIceServer()
        /*PeerConnection.IceServer.builder("stun:${BuildConfig.MY_IP_ADDRESS}:3478")
            .createIceServer()*/
    )

    // Media sources
    private val localVideoSource by lazy { peerConnectionFactory.createVideoSource(false) }
    private val localAudioSource by lazy { peerConnectionFactory.createAudioSource(MediaConstraints()) }
    private val videoCapturer = getVideoCapturer(context)
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    // Media constraints (both audio and video)
    private val mediaConstraint = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
    }

    // Surface views
    private lateinit var localSurfaceView: SurfaceViewRenderer
    private lateinit var remoteSurfaceView: SurfaceViewRenderer

    // Media tracks and stream
    private var localStream: MediaStream? = null
    private var localTrackId = ""
    private var localStreamId = ""
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null

    // Screen sharing
    private var permissionIntent: Intent? = null
    private var screenCapturer: VideoCapturer? = null
    private val localScreenVideoSource by lazy { peerConnectionFactory.createVideoSource(false) }
    private var localScreenShareVideoTrack: VideoTrack? = null

    //endregion

    //region Initialization

    init {
        initPeerConnectionFactory()
    }

    /**
     * Initializes the PeerConnectionFactory with specific WebRTC options.
     */
    private fun initPeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }

    /**
     * Creates and returns a configured PeerConnectionFactory instance.
     */
    private fun createPeerConnectionFactory(): PeerConnectionFactory {
        return PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBaseContext, true, true))
            .setOptions(PeerConnectionFactory.Options().apply {
                disableNetworkMonitor = false
                disableEncryption = false
            })
            .createPeerConnectionFactory()
    }

    //endregion

    //region Setup PeerConnection

    /**
     * Initializes WebRTC with a specific username and peer connection observer.
     */
    fun initializeWebrtcClient(username: String, observer: PeerConnection.Observer) {
        this.username = username
        localTrackId = "${username}_track"
        localStreamId = "${username}_stream"
        peerConnection = createPeerConnection(observer)
    }

    /**
     * Creates the PeerConnection using the given observer and configured ICE servers.
     */
    private fun createPeerConnection(observer: PeerConnection.Observer): PeerConnection? {
        return peerConnectionFactory.createPeerConnection(iceServer, observer)
    }

    //endregion

    //region Call and Negotiation

    /**
     * Creates and sends an SDP offer to the target user.
     */
    fun call(target: String) {
        peerConnection?.createOffer(object : MySdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                peerConnection?.setLocalDescription(object : MySdpObserver() {
                    override fun onSetSuccess() {
                        webRTCClientListener?.onTransferEventToSocket(
                            DataModel(target, username, DataModelType.Offer, desc?.description)
                        )
                    }
                }, desc)
            }
        }, mediaConstraint)
    }

    /**
     * Creates and sends an SDP answer in response to an offer.
     */
    fun answer(target: String) {
        peerConnection?.createAnswer(object : MySdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                peerConnection?.setLocalDescription(object : MySdpObserver() {
                    override fun onSetSuccess() {
                        webRTCClientListener?.onTransferEventToSocket(
                            DataModel(target, username, DataModelType.Answer, desc?.description)
                        )
                    }
                }, desc)
            }
        }, mediaConstraint)
    }

    /**
     * Applies the remote session description (offer/answer).
     */
    fun onRemoteSessionReceived(sessionDescription: SessionDescription) {
        peerConnection?.setRemoteDescription(MySdpObserver(), sessionDescription)
    }

    //endregion

    //region ICE Candidate Handling

    /**
     * Adds the given ICE candidate to the PeerConnection.
     */
    fun addIceCandidateToPeer(iceCandidate: IceCandidate) {
        peerConnection?.addIceCandidate(iceCandidate)
    }

    /**
     * Sends and adds the ICE candidate to the peer.
     */
    fun sendIceCandidate(target: String, iceCandidate: IceCandidate) {
        addIceCandidateToPeer(iceCandidate)
        webRTCClientListener?.onTransferEventToSocket(
            DataModel(target, username, DataModelType.IceCandidates, gson.toJson(iceCandidate))
        )
    }

    //endregion

    //region Media Stream Management

    /**
     * Releases all media sources and closes the connection.
     */
    fun closeConnection() {
        try {
            videoCapturer.dispose()
            screenCapturer?.dispose()
            localStream?.dispose()
            peerConnection?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Switches the front and back camera.
     */
    fun switchCamera() {
        videoCapturer.switchCamera(null)
    }

    /**
     * Mutes/unmutes the local audio track.
     */
    fun toggleAudio(shouldBeMuted: Boolean) {
        if (shouldBeMuted) localStream?.removeTrack(localAudioTrack)
        else localStream?.addTrack(localAudioTrack)
    }

    /**
     * Enables/disables local video streaming.
     */
    fun toggleVideo(shouldBeMuted: Boolean) {
        try {
            if (shouldBeMuted) stopCapturingCamera()
            else startCapturingCamera(localSurfaceView)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    //endregion

    //region Surface View and Streaming

    private fun initSurfaceView(view: SurfaceViewRenderer) {
        view.run {
            setMirror(false)
            setEnableHardwareScaler(true)
            init(eglBaseContext, null)
        }
    }

    /**
     * Initializes the remote SurfaceView for rendering incoming video.
     */
    fun initRemoteSurfaceView(remoteView: SurfaceViewRenderer) {
        this.remoteSurfaceView = remoteView
        initSurfaceView(remoteView)
    }

    /**
     * Initializes the local SurfaceView and starts streaming if it's a video call.
     */
    fun initLocalSurfaceView(localView: SurfaceViewRenderer, isVideoCall: Boolean) {
        this.localSurfaceView = localView
        initSurfaceView(localView)
        startLocalStreaming(localView, isVideoCall)
    }

    /**
     * Starts local media streaming by adding audio and video tracks to the stream.
     */
    private fun startLocalStreaming(localView: SurfaceViewRenderer, isVideoCall: Boolean) {
        localStream = peerConnectionFactory.createLocalMediaStream(localStreamId)

        if (isVideoCall) startCapturingCamera(localView)

        localAudioTrack =
            peerConnectionFactory.createAudioTrack("${localTrackId}_audio", localAudioSource)
        localStream?.addTrack(localAudioTrack)
        peerConnection?.addStream(localStream)
    }

    /**
     * Starts capturing video from the camera and attaches it to the view.
     */
    private fun startCapturingCamera(localView: SurfaceViewRenderer) {
        surfaceTextureHelper =
            SurfaceTextureHelper.create(Thread.currentThread().name, eglBaseContext)
        videoCapturer.initialize(surfaceTextureHelper, context, localVideoSource.capturerObserver)
        videoCapturer.startCapture(720, 480, 20)
        localVideoTrack =
            peerConnectionFactory.createVideoTrack("${localTrackId}_video", localVideoSource)
        localVideoTrack?.addSink(localView)
        localStream?.addTrack(localVideoTrack)
    }

    /**
     * Gets the front-facing video capturer using Camera2 API.
     */
    private fun getVideoCapturer(context: Context): CameraVideoCapturer =
        Camera2Enumerator(context).run {
            deviceNames.find { isFrontFacing(it) }?.let {
                createCapturer(it, null)
            } ?: throw IllegalStateException("No front camera found.")
        }

    /**
     * Stops capturing video and clears SurfaceView.
     */
    private fun stopCapturingCamera() {
        videoCapturer.dispose()
        localVideoTrack?.removeSink(localSurfaceView)
        localSurfaceView.clearImage()
        localStream?.removeTrack(localVideoTrack)
        localVideoTrack?.dispose()
    }

    //endregion

    //region Screen Sharing

    /**
     * Sets the screen capture permission intent received from MediaProjectionManager.
     */
    fun setPermissionIntent(screenPermissionIntent: Intent) {
        this.permissionIntent = screenPermissionIntent
    }

    /**
     * Starts capturing the screen and adds it to the local stream.
     */
    fun startScreenCapturing() {
        val displayMetrics = DisplayMetrics()
        val windowsManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowsManager.defaultDisplay.getMetrics(displayMetrics)

        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val surfaceTextureHelper =
            SurfaceTextureHelper.create(Thread.currentThread().name, eglBaseContext)
        screenCapturer = createScreenCapturer()
        screenCapturer!!.initialize(
            surfaceTextureHelper,
            context,
            localScreenVideoSource.capturerObserver
        )
        screenCapturer!!.startCapture(screenWidth, screenHeight, 15)

        localScreenShareVideoTrack =
            peerConnectionFactory.createVideoTrack("${localTrackId}_video", localScreenVideoSource)
        localScreenShareVideoTrack?.addSink(localSurfaceView)
        localStream?.addTrack(localScreenShareVideoTrack)
        peerConnection?.addStream(localStream)
    }

    /**
     * Stops screen capturing and removes the video track from the stream.
     */
    fun stopScreenCapturing() {
        screenCapturer?.stopCapture()
        screenCapturer?.dispose()
        localScreenShareVideoTrack?.removeSink(localSurfaceView)
        localSurfaceView.clearImage()
        localStream?.removeTrack(localScreenShareVideoTrack)
        localScreenShareVideoTrack?.dispose()
    }

    /**
     * Creates and returns a screen capturer using the MediaProjection API.
     */
    private fun createScreenCapturer(): VideoCapturer {
        return ScreenCapturerAndroid(permissionIntent, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d("permissions", "onStop: Screen casting permission stopped")
            }
        })
    }

    //endregion

    //region Listener Interface

    interface WebRTCClientListener {
        fun onTransferEventToSocket(data: DataModel)
    }

    //endregion
}
