package com.mmk.webrtcfirebasevideocall.repository

import android.content.Intent
import com.google.gson.Gson
import com.mmk.webrtcfirebasevideocall.firebaseclient.FirebaseClient
import com.mmk.webrtcfirebasevideocall.utils.DataModel
import com.mmk.webrtcfirebasevideocall.utils.DataModelType.Answer
import com.mmk.webrtcfirebasevideocall.utils.DataModelType.EndCall
import com.mmk.webrtcfirebasevideocall.utils.DataModelType.IceCandidates
import com.mmk.webrtcfirebasevideocall.utils.DataModelType.Offer
import com.mmk.webrtcfirebasevideocall.utils.DataModelType.StartAudioCall
import com.mmk.webrtcfirebasevideocall.utils.DataModelType.StartVideoCall
import com.mmk.webrtcfirebasevideocall.utils.UserStatus
import com.mmk.webrtcfirebasevideocall.webrtc.MyPeerObserver
import com.mmk.webrtcfirebasevideocall.webrtc.WebRTCClient
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MainRepository @Inject constructor(
    private val firebaseClient: FirebaseClient,
    private val webRTCClient: WebRTCClient,
    private val gson: Gson
) : WebRTCClient.WebRTCClientListener {
    private var target: String? = null
    var mainRepositoryListener: MainRepositoryListener? = null

    private var remoteView: SurfaceViewRenderer? = null

    fun login(username: String, password: String, isDone: (Boolean, String?) -> Unit) {
        firebaseClient.login(username, password, isDone)
    }

    fun observeUsersStatus(status: (List<Pair<String, String>>) -> Unit) {
        firebaseClient.observeUsersStatus(status)
    }

    fun initFirebase() {
        firebaseClient.subscribeForLatestEvent(object : FirebaseClient.FirebaseClientListener {
            override fun onLatestEventReceived(dataModel: DataModel) {
                mainRepositoryListener?.onLatestEventReceived(dataModel)
                when (dataModel.type) {
                    Offer -> {
                        webRTCClient.onRemoteSessionReceived(
                            SessionDescription(
                                SessionDescription.Type.OFFER, dataModel.data.toString()
                            )
                        )
                        webRTCClient.answer(target!!)
                    }

                    Answer -> {
                        webRTCClient.onRemoteSessionReceived(
                            SessionDescription(
                                SessionDescription.Type.ANSWER, dataModel.data.toString()
                            )
                        )
                    }

                    IceCandidates -> {
                        val candidate: IceCandidate? = try {
                            gson.fromJson(dataModel.data.toString(), IceCandidate::class.java)
                        } catch (e: Exception) {
                            null
                        }
                        candidate?.let {
                            webRTCClient.addIceCandidateToPeer(it)
                        }
                    }

                    EndCall -> {
                        mainRepositoryListener?.endCall()
                    }

                    else -> Unit
                }
            }

        })
    }

    fun sendConnectionRequest(target: String, isVideoCall: Boolean, success: (Boolean) -> Unit) {
        firebaseClient.sendMessageToOtherClient(
            DataModel(
                type = if (isVideoCall) StartVideoCall else StartAudioCall, target = target
            ), success
        )
    }

    fun setTarget(target: String) {
        this.target = target
    }

    fun initWebrtcClient(username: String) {
        webRTCClient.webRTCClientListener = this
        webRTCClient.initializeWebrtcClient(username, object : MyPeerObserver() {

            override fun onAddStream(p0: MediaStream?) {
                super.onAddStream(p0)
                try {
                    p0?.videoTracks?.get(0)?.addSink(remoteView)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

            }

            /**
            * How ICE Candidate Being Called
            * WebRTC (native) → MyPeerObserver.onIceCandidate(...) → 
            * MainRepository.onIceCandidate(...) → 
            * WebRTCClient.sendIceCandidate(...) → 
            * listener.onTransferEventToSocket(...) → 
            * FirebaseClient.sendMessageToOtherClient(...)
            */
            override fun onIceCandidate(p0: IceCandidate?) {
                super.onIceCandidate(p0)
                p0?.let {
                    webRTCClient.sendIceCandidate(target!!, it)
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                super.onConnectionChange(newState)
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                    // 1. change my status to in call
                    changeMyStatus(UserStatus.IN_CALL)
                    // 2. clear latest event inside my user section in firebase database
                    firebaseClient.clearLatestEvent()
                }
            }
        })
    }

    fun initLocalSurfaceView(view: SurfaceViewRenderer, isVideoCall: Boolean) {
        webRTCClient.initLocalSurfaceView(view, isVideoCall)
    }

    fun initRemoteSurfaceView(view: SurfaceViewRenderer) {
        webRTCClient.initRemoteSurfaceView(view)
        this.remoteView = view
    }

    fun startCall() {
        webRTCClient.call(target!!)
    }

    fun endCall() {
        webRTCClient.closeConnection()
        changeMyStatus(UserStatus.ONLINE)
    }

    fun sendEndCall() {
        onTransferEventToSocket(
            DataModel(
                type = EndCall, target = target!!
            )
        )
    }

    private fun changeMyStatus(status: UserStatus) {
        firebaseClient.changeMyStatus(status)
    }

    fun toggleAudio(shouldBeMuted: Boolean) {
        webRTCClient.toggleAudio(shouldBeMuted)
    }

    fun toggleVideo(shouldBeMuted: Boolean) {
        webRTCClient.toggleVideo(shouldBeMuted)
    }

    fun switchCamera() {
        webRTCClient.switchCamera()
    }

    override fun onTransferEventToSocket(data: DataModel) {
        firebaseClient.sendMessageToOtherClient(data) {}
    }

    fun setScreenCaptureIntent(screenPermissionIntent: Intent) {
        webRTCClient.setPermissionIntent(screenPermissionIntent)
    }

    fun toggleScreenShare(isStarting: Boolean) {
        if (isStarting) {
            webRTCClient.startScreenCapturing()
        } else {
            webRTCClient.stopScreenCapturing()
        }
    }

    fun logOff(function: () -> Unit) = firebaseClient.logOff(function)

    interface MainRepositoryListener {
        fun onLatestEventReceived(event: DataModel)
        fun endCall()
    }
}
