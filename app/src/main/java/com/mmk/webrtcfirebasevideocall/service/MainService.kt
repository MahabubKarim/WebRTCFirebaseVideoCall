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
import org.webrtc.SurfaceViewRenderer
import javax.inject.Inject


@AndroidEntryPoint
class MainService : Service(), MainRepository.Listener {

    private val TAG = "MainService"

    private var isServiceRunning = false
    private var username: String? = null
    private var isPreviousCallStateVideo = true

    companion object {
        var listener: CallReceiveListener? = null
        //var endCallListener:EndCallListener?=null
        var localSurfaceView: SurfaceViewRenderer?=null
        var remoteSurfaceView: SurfaceViewRenderer?=null
        var screenPermissionIntent : Intent?=null
    }

    @Inject lateinit var mainRepository: MainRepository

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
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
                SETUP_VIEWS.name -> {
                    handleSetupViews(incomingIntent)
                }
                /*END_CALL.name -> handleEndCall()
                SWITCH_CAMERA.name -> handleSwitchCamera()
                TOGGLE_AUDIO.name -> handleToggleAudio(incomingIntent)
                TOGGLE_VIDEO.name -> handleToggleVideo(incomingIntent)
                TOGGLE_AUDIO_DEVICE.name -> handleToggleAudioDevice(incomingIntent)
                TOGGLE_SCREEN_SHARE.name -> handleToggleScreenShare(incomingIntent)
                STOP_SERVICE.name -> handleStopService()*/
                else -> Unit
            }
        }
        return START_STICKY
    }

    private fun handleSetupViews(incomingIntent: Intent) {
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
            mainRepository.listener = this
            mainRepository.initFirebase()
            mainRepository.initWebrtcClient(username!!)
        }
    }

    private fun startServiceWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(
                "channel1", "foreground", NotificationManager.IMPORTANCE_HIGH
            )

            /*val intent = Intent(this,MainServiceReceiver::class.java).apply {
                action = "ACTION_EXIT"
            }
            val pendingIntent : PendingIntent =
                PendingIntent.getBroadcast(this,0 ,intent,PendingIntent.FLAG_IMMUTABLE)*/

            notificationManager.createNotificationChannel(notificationChannel)
            val notification = NotificationCompat.Builder(
                this, "channel1"
            ).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Call Service")
                .setContentText("Running in background")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                //.addAction(R.drawable.ic_end_call,"Exit",pendingIntent)
            startForeground(1, notification.build())
        }
    }

    override fun onLatestEventReceived(event: DataModel) {
        if(event.isValid()) {
            when (event.type) {
                StartAudioCall -> {
                    listener?.onCallReceived(event)
                }
                StartVideoCall -> {
                    listener?.onCallReceived(event)
                }
                Offer -> {}
                Answer -> {}
                IceCandidates -> {}
                EndCall -> {}
            }
        }
        Log.d(TAG, "onLatestEventReceived: $event")
    }

    override fun onCallEnded() {

    }

    interface CallReceiveListener {
        fun onCallReceived(model: DataModel)
    }

}