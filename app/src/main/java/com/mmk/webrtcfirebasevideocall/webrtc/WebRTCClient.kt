package com.mmk.webrtcfirebasevideocall.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.annotation.IntDef
import androidx.annotation.UiContext
import com.google.gson.Gson
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

/**
 * WebRTCClient is a class that handles the WebRTC connection.
 * It is responsible for creating and managing the PeerConnection,
 * and for sending and receiving SDP and ICE candidates.
 *
 * @param context The application context.
 * @param gson The Gson instance for serializing and deserializing objects.
 */
@Singleton
class WebRTCClient @Inject constructor(
    private val context: Context,
    private val gson: Gson
){
    var listener: WebRTCClientListener? = null

    private lateinit var username: String

    //webrtc variables
    private val eglBaseContext = EglBase.create().eglBaseContext
    private val peerConnectionFactory by lazy { createPeerConnectionFactory() }
    private var peerConnection: PeerConnection? = null
    private val iceServer = listOf(
        PeerConnection.IceServer.builder("turn:a.relay.metered.ca:443?transport=tcp")
            .setUsername("83eebabf8b4cce9d5dbcb649").setPassword("2D7JvfkOQtBdYW3R")
            .createIceServer()
    )
    private val localVideoSource by lazy { peerConnectionFactory.createVideoSource(false) }
    private val localAudioSource by lazy { peerConnectionFactory.createAudioSource(MediaConstraints()) }
    private val videoCapturer = getVideoCapturer(context)
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val mediaConstraint = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
    }

    //call variables
    private lateinit var localSurfaceView: SurfaceViewRenderer
    private lateinit var remoteSurfaceView: SurfaceViewRenderer
    private var localStream: MediaStream? = null
    private var localTrackId = ""
    private var localStreamId = ""
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null

    //screen casting
    private var permissionIntent: Intent? = null
    private var screenCapturer: VideoCapturer? = null
    private val localScreenVideoSource by lazy { peerConnectionFactory.createVideoSource(false) }
    private var localScreenShareVideoTrack: VideoTrack? = null

    //region Basic Initialization
    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true).setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }

    private fun createPeerConnectionFactory(): PeerConnectionFactory {
        return PeerConnectionFactory.builder().setVideoDecoderFactory(
            DefaultVideoDecoderFactory(eglBaseContext)
        ).setVideoEncoderFactory(
            DefaultVideoEncoderFactory(
                eglBaseContext, true, true
            )
        ).setOptions(PeerConnectionFactory.Options().apply {
            disableNetworkMonitor = false
            disableEncryption = false
        }).createPeerConnectionFactory()
    }

    fun initializeWebrtcClient(
        username: String, observer: PeerConnection.Observer
    ) {
        this.username = username
        localTrackId = "${username}_track"
        localStreamId = "${username}_stream"
        peerConnection = createPeerConnection(observer)
    }

    private fun createPeerConnection(observer: PeerConnection.Observer): PeerConnection? {
        return peerConnectionFactory.createPeerConnection(iceServer, observer)
    }
    //endregion

    //region Streaming Section

    /**
     * Initializes the local SurfaceViewRenderer for displaying local video and starts local streaming.
     *
     * This function sets up the surface view to render video and begins streaming local media. If the
     * session is a video call, it initiates camera capture to provide video input.
     *
     * Dependencies:
     * - `initSurfaceView`: A helper function to configure the SurfaceViewRenderer.
     * - `startLocalStreaming`: Starts streaming local media, adding audio and optionally video tracks.
     * - `localSurfaceView`: The SurfaceViewRenderer instance for local video.
     */
    private fun initSurfaceView(view: SurfaceViewRenderer) {
        view.run {
            setMirror(false)
            setEnableHardwareScaler(true)
            init(eglBaseContext, null)
        }
    }

    /**
     * Initializes the local SurfaceViewRenderer for displaying local video and starts local streaming.
     *
     * This function sets up the surface view to render video and begins streaming local media. If the
     * session is a video call, it initiates camera capture to provide video input.
     *
     * @param view The SurfaceViewRenderer to be initialized for local video rendering.
     * @param isVideoCall A boolean indicating if the call type is a video call.
     */
    fun initRemoteSurfaceView(view: SurfaceViewRenderer) {
        this.remoteSurfaceView = view
        initSurfaceView(view)
    }

    /**
     * Initializes the local SurfaceViewRenderer for displaying local video and starts local streaming.
     *
     * This function sets up the surface view to render video and begins streaming local media. If the
     * session is a video call, it initiates camera capture to provide video input.
     *
     * Dependencies:
     * - `initSurfaceView`: A helper function to configure the SurfaceViewRenderer.
     * - `startLocalStreaming`: Starts streaming local media, adding audio and optionally video tracks.
     * - `localSurfaceView`: The SurfaceViewRenderer instance for local video.
     *
     * @param localView The SurfaceViewRenderer that will display the local video.
     * @param isVideoCall A boolean indicating if the current call session is a video call.
     */
    fun initLocalSurfaceView(localView: SurfaceViewRenderer, isVideoCall: Boolean) {
        this.localSurfaceView = localView
        initSurfaceView(localView)
        startLocalStreaming(localView, isVideoCall)
    }

    /**
     * Starts local streaming by creating a local media stream and adding audio and video tracks to it.
     * If the call is a video call, it captures video from the camera and adds it to the stream.
     * The stream is then added to the peer connection for transmission to the remote peer.
     *
     * Dependencies:
     * - `peerConnectionFactory`: Used to create audio and video tracks, and local media stream.
     * - `localAudioSource`: The source for creating the local audio track.
     * - `localTrackId`: The identifier for the local tracks.
     * - `localStreamId`: The identifier for the local media stream.
     * - `peerConnection`: The WebRTC peer connection to which the local stream is added.
     *
     * @param localView The SurfaceViewRenderer where the local video is rendered.
     * @param isVideoCall A boolean indicating if the call type is a video call.
     */
    private fun startLocalStreaming(localView: SurfaceViewRenderer, isVideoCall: Boolean) {
        localStream = peerConnectionFactory.createLocalMediaStream(localStreamId)
        if (isVideoCall) {
            startCapturingCamera(localView)
        }

        localAudioTrack =
            peerConnectionFactory.createAudioTrack(localTrackId + "_audio", localAudioSource)
        localStream?.addTrack(localAudioTrack)
        peerConnection?.addStream(localStream)
    }

    /**
     * Starts local streaming by creating a local media stream and adding audio and video tracks to it.
     * If the call is a video call, it captures video from the camera and adds it to the stream.
     * The stream is then added to the peer connection for transmission to the remote peer.
     *
     * Dependencies:
     * - `localAudioSource`: The source for creating the local audio track.
     * - `localTrackId`: The identifier for the local tracks.
     * - `localStreamId`: The identifier for the local media stream.
     * - `peerConnection`: The WebRTC peer connection to which the local stream is added.
     * - `peerConnectionFactory`: Used to create audio and video tracks, and local media stream.
     */
    private fun startCapturingCamera(localView: SurfaceViewRenderer) {
        surfaceTextureHelper = SurfaceTextureHelper.create(
            Thread.currentThread().name, eglBaseContext
        )

        videoCapturer.initialize(
            surfaceTextureHelper, context, localVideoSource.capturerObserver
        )

        videoCapturer.startCapture(
            720, 480, 20
        )

        localVideoTrack =
            peerConnectionFactory.createVideoTrack(localTrackId + "_video", localVideoSource)
        localVideoTrack?.addSink(localView)
        localStream?.addTrack(localVideoTrack)
    }

    /**
     * Gets the video capturer for the local camera.
     * If the device has a front-facing camera, it uses that, otherwise it uses the default camera.
     *
     * Dependencies:
     * - `Camera2Enumerator`: Uses this to get the list of available cameras.
     */
    private fun getVideoCapturer(context: Context): CameraVideoCapturer =
        Camera2Enumerator(context).run {
            deviceNames.find {
                isFrontFacing(it)
            }?.let {
                createCapturer(it, null)
            } ?: throw IllegalStateException()
        }

    /**
     * Determines if the given device name represents a front-facing camera.
     * Dependencies:
     * - `Camera2Enumerator`: Uses this to check if the device name is a front-facing camera.
     */
    private fun isFrontFacing(deviceName: String) =
        Camera2Enumerator(context).isFrontFacing(deviceName)

    /**
     * Stops local camera capture and disposes of the video capturer and local video track.
     * This method is used to stop the local video stream and release the resources used by it.
     *
     * Dependencies:
     * - `videoCapturer`: The video capturer used to capture the local video.
     * - `localVideoTrack`: The local video track that was added to the local stream.
     * - `localStream`: The local media stream that contains the video track.
     * - `localSurfaceView`: The SurfaceViewRenderer where the local video is rendered.
     */
    private fun stopCapturingCamera() {
        videoCapturer.dispose()
        localVideoTrack?.removeSink(localSurfaceView)
        localSurfaceView.clearImage()
        localStream?.removeTrack(localVideoTrack)
        localVideoTrack?.dispose()
    }
    //endregion

    //region Negotiation Part with Peer-to-Peer Connection

    /**
     * Makes a call to the remote peer.
     * This method creates an offer sdp and sends it to the remote peer.
     * The offer sdp is used to negotiate the connection with the remote peer.
     *
     * Dependencies:
     * - `peerConnection`: The peer connection used to create the offer sdp.
     * - `MySdpObserver`: The sdp observer used to receive the offer sdp.
     * - `listener`: The listener that receives the offer sdp and sends it to the remote peer.
     */
    fun call(target: String) {
        peerConnection?.createOffer(object : MySdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                super.onCreateSuccess(desc)
                peerConnection?.setLocalDescription(object : MySdpObserver() {
                    override fun onSetSuccess() {
                        super.onSetSuccess()
                        listener?.onTransferEventToSocket(
                            DataModel(
                                type = DataModelType.Offer,
                                sender = username,
                                target = target,
                                data = desc?.description
                            )
                        )
                    }
                }, desc)
            }
        }, mediaConstraint)
    }

    /**
     * The Session Description Protocol (SDP) is a text-based protocol used to describe multimedia communication sessions.
     * It is used to negotiate the parameters of the connection between the local and remote peers.
     * The SDP is used to convey the following information:
     * - The type of media to be exchanged (audio, video, etc.).
     * - The IP addresses and ports used by the local and remote peers.
     * - The type of encryption used to protect the data.
     * - The format of the data to be exchanged (RTP, RTCP, etc.).
     * - The maximum bandwidth of the connection.
     * - The maximum number of streams that can be transmitted.
     * - The maximum number of streams that can be received.
     * - The sender's and receiver's capabilities.
     * - The type of ICE candidates used to establish the connection.
     */

    /**
     * Sends an answer to the remote peer.
     * This method creates an answer sdp and sends it to the remote peer.
     * The answer sdp is used to negotiate the connection with the remote peer.
     *
     * Dependencies:
     * - `peerConnection`: The peer connection used to create the answer sdp.
     * - `MySdpObserver`: The sdp observer used to receive the answer sdp.
     * - `listener`: The listener that receives the answer sdp and sends it to the remote peer.
     */
    fun answer(target: String) {
        peerConnection?.createAnswer(object : MySdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                super.onCreateSuccess(desc)
                peerConnection?.setLocalDescription(object : MySdpObserver() {
                    override fun onSetSuccess() {
                        super.onSetSuccess()
                        listener?.onTransferEventToSocket(
                            DataModel(
                                type = DataModelType.Answer,
                                sender = username,
                                target = target,
                                data = desc?.description
                            )
                        )
                    }
                }, desc)
            }
        }, mediaConstraint)
    }

    /**
     * Sets the remote session description.
     * This method sets the remote session description on the peer connection.
     * The remote session description is used to negotiate the connection with the remote peer.
     *
     * Dependencies:
     * - `peerConnection`: The peer connection used to set the remote session description.
     * - `MySdpObserver`: The sdp observer used to receive the remote session description.
     */
    fun onRemoteSessionReceived(sessionDescription: SessionDescription) {
        peerConnection?.setRemoteDescription(MySdpObserver(), sessionDescription)
    }

    /**
     * An ICE candidate is an address and port number that can be used to communicate with
     * the peer. ICE candidates are used to establish a connection between two peers.
     * The ICE candidate contains the IP address and port number of the peer.
     * The ICE candidate is used to pass the address and port number to the remote peer.
     */
    /**
     * Adds an ICE candidate to the peer connection.
     * This method is used to pass ICE candidates to the remote peer.
     *
     * Dependencies:
     * - `peerConnection`: The peer connection to which the ICE candidate is added.
     */
    fun addIceCandidateToPeer(iceCandidate: IceCandidate) {
        peerConnection?.addIceCandidate(iceCandidate)
    }

    /**
     * Sends an ICE candidate to the remote peer.
     * This method adds the given ICE candidate to the local peer connection and sends it
     * to the remote peer through a listener event.
     *
     * Dependencies:
     * - `peerConnection`: The peer connection where the ICE candidate is added locally.
     * - `listener`: The listener interface used to send the ICE candidate to the remote peer.
     * - `gson`: Used to serialize the ICE candidate to JSON format for transmission.
     * - `DataModel`: The data structure used to encapsulate the ICE candidate information.
     * - `DataModelType`: The type of data being sent, in this case, ICE candidates.
     */
    fun sendIceCandidate(target: String, iceCandidate: IceCandidate) {
        addIceCandidateToPeer(iceCandidate)
        listener?.onTransferEventToSocket(
            DataModel(
                type = DataModelType.IceCandidates,
                sender = username,
                target = target,
                data = gson.toJson(iceCandidate)
            )
        )
    }

    /**
     * Sends an SDP answer to the remote peer.
     * This method generates an SDP answer from the local peer connection and sends it
     * to the remote peer through a listener event.
     *
     * Dependencies:
     * - `peerConnection`: The peer connection where the SDP answer is generated.
     * - `listener`: The listener interface used to send the SDP answer to the remote peer.
     * - `gson`: Used to serialize the SDP answer to JSON format for transmission.
     * - `DataModel`: The data structure used to encapsulate the SDP answer information.
     * - `DataModelType`: The type of data being sent, in this case, an SDP answer.
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
     * Closes the peer connection.
     * This method disposes of the camera capturer, screen capturer, local media stream, and the peer connection.
     * It is used to close an active call.
     *
     * Dependencies:
     * - `videoCapturer`: The camera capturer used to capture video from the camera.
     * - `screenCapturer`: The screen capturer used to capture video from the screen.
     * - `localStream`: The local media stream used to transmit audio and video to the remote peer.
     * - `peerConnection`: The peer connection used to establish the call with the remote peer.
     */
    fun switchCamera() {
        videoCapturer.switchCamera(null)
    }

    /**
     * Switches the camera used for capturing video.
     * This method is used to switch between the front and back cameras.
     *
     * Dependencies:
     * - `videoCapturer`: The camera capturer used to capture video from the camera.
     */
    fun toggleAudio(shouldBeMuted: Boolean) {
        if (shouldBeMuted) {
            localStream?.removeTrack(localAudioTrack)
        } else {
            localStream?.addTrack(localAudioTrack)
        }
    }

    /**
     * Toggles the audio in the local stream.
     * This method is used to mute and unmute the audio in the local stream.
     *
     * Dependencies:
     * - `localStream`: The local media stream used to transmit audio and video to the remote peer.
     * - `localAudioTrack`: The audio track in the local stream.
     */
    fun toggleVideo(shouldBeMuted: Boolean) {
        try {
            if (shouldBeMuted) {
                stopCapturingCamera()
            } else {
                startCapturingCamera(localSurfaceView)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    //endregion

    //region Screen Capture
    fun setPermissionIntent(screenPermissionIntent: Intent) {
        this.permissionIntent = screenPermissionIntent
    }

    fun startScreenCapturing() {
        val displayMetrics = DisplayMetrics()
        val windowsManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowsManager.defaultDisplay.getMetrics(displayMetrics)

        val screenWidthPixels = displayMetrics.widthPixels
        val screenHeightPixels = displayMetrics.heightPixels

        val surfaceTextureHelper = SurfaceTextureHelper.create(
            Thread.currentThread().name, eglBaseContext
        )

        screenCapturer = createScreenCapturer()
        screenCapturer!!.initialize(
            surfaceTextureHelper, context, localScreenVideoSource.capturerObserver
        )
        screenCapturer!!.startCapture(screenWidthPixels, screenHeightPixels, 15)

        localScreenShareVideoTrack =
            peerConnectionFactory.createVideoTrack(localTrackId + "_video", localScreenVideoSource)
        localScreenShareVideoTrack?.addSink(localSurfaceView)
        localStream?.addTrack(localScreenShareVideoTrack)
        peerConnection?.addStream(localStream)
    }

    fun stopScreenCapturing() {
        screenCapturer?.stopCapture()
        screenCapturer?.dispose()
        localScreenShareVideoTrack?.removeSink(localSurfaceView)
        localSurfaceView.clearImage()
        localStream?.removeTrack(localScreenShareVideoTrack)
        localScreenShareVideoTrack?.dispose()

    }

    private fun createScreenCapturer(): VideoCapturer {
        return ScreenCapturerAndroid(permissionIntent,
            object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.d("permissions", "onStop: permission of screen casting is stopped")
            }
        })
    }

    interface WebRTCClientListener {
        fun onTransferEventToSocket(data: DataModel)
    }
    //endregion
}