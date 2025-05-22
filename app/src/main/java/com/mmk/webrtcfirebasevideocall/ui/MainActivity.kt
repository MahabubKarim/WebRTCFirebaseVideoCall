package com.mmk.webrtcfirebasevideocall.ui

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.mmk.webrtcfirebasevideocall.adapter.MainRecyclerViewAdapter
import com.mmk.webrtcfirebasevideocall.databinding.ActivityMainBinding
import com.mmk.webrtcfirebasevideocall.repository.MainRepository
import com.mmk.webrtcfirebasevideocall.service.MainService
import com.mmk.webrtcfirebasevideocall.service.MainServiceRepository
import com.mmk.webrtcfirebasevideocall.utils.DataModel
import com.mmk.webrtcfirebasevideocall.utils.DataModelType
import com.mmk.webrtcfirebasevideocall.utils.getCameraAndMicPermission
import com.mmk.webrtcfirebasevideocall.utils.getMediaProjectionAndCaptureAudioOutputPermission
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject


@AndroidEntryPoint
class MainActivity : AppCompatActivity(), MainRecyclerViewAdapter.Listener,
    MainService.CallReceiveListener {
    private val TAG = "MainActivity"
    private val REQUEST_CODE_MEDIA_PROJECTION: Int = 1
    @Inject lateinit var mainRepository: MainRepository
    @Inject lateinit var mainServiceRepository: MainServiceRepository
    private var username: String? = null
    private var mainAdapter: MainRecyclerViewAdapter? = null
    private lateinit var views: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityMainBinding.inflate(layoutInflater)
        setContentView(views.root)
        init()
    }

    /**
     * Called when the activity is starting.
     * Initializes the views and the adapter for the RecyclerView,
     * and subscribes to the MainRepository to observe the status of other users.
     * Also starts the MainService in foreground mode.
     * The MainService is responsible for listening to negotiation messages
     * (such as ICE candidates, SDP offers/answers) and for making and receiving video calls.
     */
    private fun init() {
        username = intent.getStringExtra("username")
        if (username == null) finish()
        // observe other users status
        subscribeObservers()
        // start foreground service to listen negotiations and calls.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            getMediaProjectionAndCaptureAudioOutputPermission {
                val mediaProjectionManager =
                    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                startActivityForResult(intent, REQUEST_CODE_MEDIA_PROJECTION)
                //startMyService()
            }
        } else {
            startMyService()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION && resultCode == RESULT_OK
            && data != null) {
            // Save this intent somewhere accessible (e.g. in your Service companion)
            MainService.screenPermissionIntent = data
            startMyService()
        }
    }

    /**
     * Subscribes to the MainRepository to observe the status of other users
     * and updates the RecyclerView to display the list of users.
     */
    private fun subscribeObservers() {
        setupRecyclerView()
        MainService.listener = this
        mainRepository.observeUsersStatus {
            Log.d(TAG, "subscribeObservers: $it")
            mainAdapter?.updateList(it)
        }
    }

    /**
     * Starts the MainService in foreground mode. This service is responsible for
     * listening to negotiation messages (such as ICE candidates, SDP offers/answers)
     * and for making and receiving video calls.
     * @param username The username of the user who is making the call.
     */
    private fun startMyService() {
        mainServiceRepository.startService(username!!)
    }

    /**
     * Set up the recyclerView with its adapter and layout manager.
     * This is the recyclerView that will show the list of other users
     * in the app, and whether they are online or not.
     */
    private fun setupRecyclerView() {
        mainAdapter = MainRecyclerViewAdapter(this)
        val layoutManager = LinearLayoutManager(this)
        views.mainRecyclerView.apply {
            setLayoutManager(layoutManager)
            adapter = mainAdapter
        }
    }

    /**
     * Called when the user clicks on a username in the main RecyclerView to make a video call.
     * Requests the camera and microphone permissions, and if granted, sends a connection request
     * for a video call to the specified username. If the connection is successful, starts the CallActivity.
     * @param username The target username to call.
     */
    override fun onVideoCallClicked(username: String) {
        // Check if the camera and microphone permissions have been granted
        getCameraAndMicPermission {
            // Send a connection request for a video call to the specified username
            mainRepository.sendConnectionRequest(username, true) {
                if (it) {
                    // If the connection is successful, start the CallActivity Pass the target
                    // username, call type (video call), and caller status (isCaller)
                    startActivity(Intent(this, CallActivity::class.java).apply {
                        putExtra("target", username)
                        putExtra("isVideoCall", true)
                        putExtra("isCaller", true)
                    })
                }
            }
        }
    }

    /**
     * Called when the user clicks on a username in the main RecyclerView to make an audio call.
     * Requests the microphone permission, and if granted, sends a connection request for an
     * audio call to the specified username. If the connection is successful, starts the CallActivity.
     * @param username The target username to call.
     */
    override fun onAudioCallClicked(username: String) {
        getCameraAndMicPermission {
            // Send a connection request for a audio call to the specified username
            mainRepository.sendConnectionRequest(username, false) {
                if (it){
                    // If the connection is successful, start the CallActivity Pass the target
                    // username, call type (Audio call), and caller status (isCaller)
                    startActivity(Intent(this,CallActivity::class.java).apply {
                        putExtra("target",username)
                        putExtra("isVideoCall",false)
                        putExtra("isCaller",true)
                    })
                }
            }
        }
    }

    /**
     * Called when the MainRepository receives a call from another user.
     * Updates the UI to show an incoming call, and sets the accept button's
     * OnClickListener to request the camera and microphone permissions.
     * If the permissions are granted, accept the call by sending an answer
     * to the sender, and start the CallActivity.
     * @param dataModel The DataModel containing the call details.
     */
    override fun onCallReceived(dataModel: DataModel) {
        runOnUiThread {
            views.apply {
                val isVideoCall = dataModel.type == DataModelType.StartVideoCall
                val isVideoCallText = if (isVideoCall) "Video" else "Audio"
                incomingCallTitleTv.text = "${dataModel.sender} is $isVideoCallText Calling you"
                incomingCallLayout.isVisible = true
                acceptButton.setOnClickListener {
                    getCameraAndMicPermission {
                        incomingCallLayout.isVisible = false
                        //create an intent to go to video call activity
                        startActivity(Intent(this@MainActivity,CallActivity::class.java).apply {
                            putExtra("target",dataModel.sender)
                            putExtra("isVideoCall",isVideoCall)
                            putExtra("isCaller",false)
                        })
                    }
                }
                declineButton.setOnClickListener {
                    incomingCallLayout.isVisible = false
                }
            }
        }
    }

}