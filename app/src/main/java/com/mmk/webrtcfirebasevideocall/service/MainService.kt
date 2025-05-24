package com.mmk.webrtcfirebasevideocall.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mmk.webrtcfirebasevideocall.R
import com.mmk.webrtcfirebasevideocall.repository.MainRepository
import dagger.hilt.android.AndroidEntryPoint
import com.mmk.webrtcfirebasevideocall.service.MainServiceActions.*
import com.mmk.webrtcfirebasevideocall.utils.DataModel
import com.mmk.webrtcfirebasevideocall.utils.DataModelType.*
import com.mmk.webrtcfirebasevideocall.utils.isValid
import com.mmk.webrtcfirebasevideocall.webrtc.RTCAudioManager
import org.webrtc.SurfaceViewRenderer
import javax.inject.Inject


@AndroidEntryPoint
class MainService : Service(), MainRepository.MainRepositoryListener {

    private val TAG = "MainService"

    private var isServiceRunning = false
    private var username: String? = null
    private var isPreviousCallStateVideo = true

    private lateinit var rtcAudioManager: RTCAudioManager
    companion object {
        var callReceiveListener: CallReceiveListener? = null
        var endCallListener:EndCallListener?=null
        var localSurfaceView: SurfaceViewRenderer?=null
        var remoteSurfaceView: SurfaceViewRenderer?=null
        var screenPermissionIntent : Intent?=null
    }

    @Inject lateinit var mainRepository: MainRepository

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        rtcAudioManager = RTCAudioManager.create(this)
        rtcAudioManager.setDefaultAudioDevice(RTCAudioManager.AudioDevice.SPEAKER_PHONE)
        notificationManager = getSystemService(
            NotificationManager::class.java
        )
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { incomingIntent ->
            when (incomingIntent.action) {
                START_SERVICE.name -> handleStartService(incomingIntent)
                SETUP_VIEWS.name -> handleSetupViewsAndStartCall(incomingIntent)
                END_CALL.name -> handleEndCall()
                SWITCH_CAMERA.name -> handleSwitchCamera()
                TOGGLE_AUDIO.name -> handleToggleAudio(incomingIntent)
                TOGGLE_VIDEO.name -> handleToggleVideo(incomingIntent)
                TOGGLE_AUDIO_DEVICE.name -> handleToggleAudioDevice(incomingIntent)
                TOGGLE_SCREEN_SHARE.name -> handleToggleScreenShare(incomingIntent)
                STOP_SERVICE.name -> handleStopService()
                else -> Unit
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        rtcAudioManager.release()
        localSurfaceView = null
        remoteSurfaceView = null
        super.onDestroy()
    }

    private fun handleSetupViewsAndStartCall(incomingIntent: Intent) {
        val isCaller = incomingIntent.getBooleanExtra("isCaller",false)
        val isVideoCall = incomingIntent.getBooleanExtra("isVideoCall",true)
        val target = incomingIntent.getStringExtra("target")
        this.isPreviousCallStateVideo = isVideoCall
        mainRepository.setTarget(target!!)
        // initialize our widgets and start streaming our video and audio source
        // and get prepared for call
        mainRepository.initLocalSurfaceView(localSurfaceView!!, isVideoCall)
        mainRepository.initRemoteSurfaceView(remoteSurfaceView!!)

        if (!isCaller){ // if we are not the caller, we start the call to callee
            //start the video call
            mainRepository.startCall()
        }
    }

    private fun handleStartService(incomingIntent: Intent) {
        //start our foreground service
        if (!isServiceRunning) {
            isServiceRunning = true
            username = incomingIntent.getStringExtra("username")
            startServiceWithNotification()

            //setup my clients
            mainRepository.mainRepositoryListener = this
            mainRepository.initFirebase()
            mainRepository.initWebrtcClient(username!!)
        }
    }

    private fun handleEndCall() {
        //1. we have to send a signal to other peer that call is ended
        mainRepository.sendEndCall()
        //2.end out call process and restart our webrtc client
        endCallAndRestartRepository()
    }

    private fun endCallAndRestartRepository(){
        mainRepository.endCall()
        endCallListener?.onCallEnded()
        mainRepository.initWebrtcClient(username!!)
    }

    private fun handleSwitchCamera() {
        mainRepository.switchCamera()
    }

    private fun handleToggleAudio(incomingIntent: Intent) {
        val shouldBeMuted = incomingIntent.getBooleanExtra("shouldBeMuted",true)
        mainRepository.toggleAudio(shouldBeMuted)
    }

    private fun handleToggleVideo(incomingIntent: Intent) {
        val shouldBeMuted = incomingIntent.getBooleanExtra("shouldBeMuted",true)
        this.isPreviousCallStateVideo = !shouldBeMuted
        mainRepository.toggleVideo(shouldBeMuted)
    }

    private fun handleToggleAudioDevice(incomingIntent: Intent) {
        val type = when(incomingIntent.getStringExtra("type")){
            RTCAudioManager.AudioDevice.EARPIECE.name -> RTCAudioManager.AudioDevice.EARPIECE
            RTCAudioManager.AudioDevice.SPEAKER_PHONE.name -> RTCAudioManager.AudioDevice.SPEAKER_PHONE
            else -> null
        }
        type?.let {
            rtcAudioManager.setDefaultAudioDevice(it)
            rtcAudioManager.selectAudioDevice(it)
            Log.d(TAG, "handleToggleAudioDevice: $it")
        }
    }

    private fun handleToggleScreenShare(incomingIntent: Intent) {
        val isStarting = incomingIntent.getBooleanExtra("isStarting",true)
        if (isStarting){
            // we should start screen share
            // but we have to keep it in mind that we first should remove the camera streaming first
            if (isPreviousCallStateVideo){
                mainRepository.toggleVideo(true)
            }
            mainRepository.setScreenCaptureIntent(screenPermissionIntent!!)
            mainRepository.toggleScreenShare(true)

        }else{
            //we should stop screen share and check if camera streaming was on so we should make it on back again
            mainRepository.toggleScreenShare(false)
            if (isPreviousCallStateVideo){
                mainRepository.toggleVideo(false)
            }
        }
    }

    private fun handleStopService() {
        mainRepository.endCall()
        mainRepository.logOff {
            isServiceRunning = false
            stopSelf()
        }
    }

    private fun startServiceWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(
                "channel1", "foreground", NotificationManager.IMPORTANCE_HIGH
            )

            val intent = Intent(this,MainServiceReceiver::class.java).apply {
                action = "ACTION_EXIT"
            }
            val pendingIntent : PendingIntent =
                PendingIntent.getBroadcast(this,0 ,intent,PendingIntent.FLAG_IMMUTABLE)

            notificationManager.createNotificationChannel(notificationChannel)
            val notification = NotificationCompat.Builder(
                this, "channel1"
            ).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Call Service")
                .setContentText("Running in background")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(R.drawable.ic_end_call,"Exit",pendingIntent)
            startForeground(1, notification.build())
        }
    }

    override fun onLatestEventReceived(data: DataModel) {
        if (data.isValid()) {
            when (data.type) {
                StartVideoCall,
                StartAudioCall -> {
                    callReceiveListener?.onCallReceived(data)
                }
                else -> Unit
            }
        }
    }

    override fun endCall() {
        //we are receiving end call signal from remote peer
        endCallAndRestartRepository()
    }

    interface CallReceiveListener {
        fun onCallReceived(model: DataModel)
    }

    interface EndCallListener {
        fun onCallEnded()
    }
}