package com.mmk.webrtcfirebasevideocall.webrtc

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.*
import android.util.Log
import org.webrtc.ThreadUtils

@SuppressLint("MissingPermission")
class BluetoothManager private constructor(
    private val context: Context,
    private val audioManager: RTCAudioManager
) {

    //region Companion Object
    companion object {
        private const val TAG = "BluetoothManager"
        private const val BLUETOOTH_SCO_TIMEOUT_MS = 4000
        private const val MAX_SCO_CONNECTION_ATTEMPTS = 2

        fun create(context: Context, audioManager: RTCAudioManager): BluetoothManager {
            return BluetoothManager(context.applicationContext, audioManager)
        }
    }
    //endregion

    //region Enum
    enum class State {
        UNINITIALIZED,
        ERROR,
        HEADSET_UNAVAILABLE,
        HEADSET_AVAILABLE,
        SCO_DISCONNECTING,
        SCO_CONNECTING,
        SCO_CONNECTED
    }
    //endregion

    //region Member Variables
    private val androidAudioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var scoConnectionAttempts = 0
    var state: State = State.UNINITIALIZED
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHeadset: BluetoothHeadset? = null
    private var connectedDevice: BluetoothDevice? = null
    private val timeoutRunnable = Runnable { handleBluetoothTimeout() }
    //endregion

    //region Listeners

    // Listens for Bluetooth headset service connection and disconnection
    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HEADSET || state == State.UNINITIALIZED) return
            bluetoothHeadset = proxy as BluetoothHeadset
            audioManager.updateAudioDeviceState()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HEADSET || state == State.UNINITIALIZED) return
            stopScoAudio()
            bluetoothHeadset = null
            connectedDevice = null
            state = State.HEADSET_UNAVAILABLE
            audioManager.updateAudioDeviceState()
        }
    }

    // Receives Bluetooth headset connection and audio state changes
    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (state == State.UNINITIALIZED) return

            when (intent.action) {
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> handleConnectionStateChange(intent)
                BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> handleAudioStateChange(intent)
            }
        }

        // Handles headset connection or disconnection
        private fun handleConnectionStateChange(intent: Intent) {
            when (intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED)) {
                BluetoothHeadset.STATE_CONNECTED -> {
                    scoConnectionAttempts = 0
                    audioManager.updateAudioDeviceState()
                }
                BluetoothHeadset.STATE_DISCONNECTED -> {
                    stopScoAudio()
                    audioManager.updateAudioDeviceState()
                }
            }
        }

        // Handles SCO audio connection or disconnection
        private fun handleAudioStateChange(intent: Intent) {
            when (intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_AUDIO_DISCONNECTED)) {
                BluetoothHeadset.STATE_AUDIO_CONNECTED -> {
                    cancelTimer()
                    if (state == State.SCO_CONNECTING) {
                        state = State.SCO_CONNECTED
                        scoConnectionAttempts = 0
                        audioManager.updateAudioDeviceState()
                    }
                }
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> {
                    if (!isInitialStickyBroadcast) {
                        audioManager.updateAudioDeviceState()
                    }
                }
            }
        }
    }
    //endregion

    //region Lifecycle Methods

    /**
     * Initializes and starts the BluetoothManager. Registers receivers and checks permissions.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        ThreadUtils.checkIsOnMainThread()

        if (!hasPermission()) {
            Log.w(TAG, "Bluetooth permission not granted")
            return
        }

        if (state != State.UNINITIALIZED) {
            Log.w(TAG, "Already started, current state: $state")
            return
        }

        resetBluetoothState()
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter().also {
            if (it == null) {
                Log.w(TAG, "Device doesn't support Bluetooth")
                return
            }

            if (!androidAudioManager.isBluetoothScoAvailableOffCall) {
                Log.w(TAG, "Bluetooth SCO not available off call")
                return
            }

            logBluetoothInfo(it)

            if (!it.getProfileProxy(context, serviceListener, BluetoothProfile.HEADSET)) {
                Log.e(TAG, "Failed to setup Bluetooth profile proxy")
                return
            }
        }

        registerBluetoothReceivers()
        state = State.HEADSET_UNAVAILABLE
    }

    /**
     * Stops Bluetooth SCO and unregisters resources.
     */
    fun stop() {
        ThreadUtils.checkIsOnMainThread()
        if (state == State.UNINITIALIZED) return

        stopScoAudio()
        cleanupBluetoothResources()
        state = State.UNINITIALIZED
    }

    /**
     * Fully releases resources and cleans up.
     */
    fun release() {
        ThreadUtils.checkIsOnMainThread()
        stop()
        handler.removeCallbacksAndMessages(null)
        connectedDevice = null
        bluetoothAdapter = null
        bluetoothHeadset = null
    }
    //endregion

    //region Public API Methods

    /**
     * Attempts to start SCO audio connection.
     * @return true if the request was initiated, false otherwise.
     */
    fun startScoAudio(): Boolean {
        ThreadUtils.checkIsOnMainThread()

        if (scoConnectionAttempts >= MAX_SCO_CONNECTION_ATTEMPTS) {
            Log.w(TAG, "Max SCO connection attempts reached")
            return false
        }

        if (state != State.HEADSET_AVAILABLE) {
            Log.w(TAG, "Cannot start SCO, wrong state: $state")
            return false
        }

        state = State.SCO_CONNECTING
        androidAudioManager.startBluetoothSco()
        androidAudioManager.isBluetoothScoOn = true
        scoConnectionAttempts++
        startTimer()
        return true
    }

    /**
     * Stops SCO audio if it is currently active or connecting.
     */
    fun stopScoAudio() {
        ThreadUtils.checkIsOnMainThread()
        if (state != State.SCO_CONNECTING && state != State.SCO_CONNECTED) return

        cancelTimer()
        androidAudioManager.stopBluetoothSco()
        androidAudioManager.isBluetoothScoOn = false
        state = State.SCO_DISCONNECTING
    }

    /**
     * Updates the currently connected Bluetooth device and state.
     */
    fun updateDevice() {
        ThreadUtils.checkIsOnMainThread()
        if (state == State.UNINITIALIZED || bluetoothHeadset == null) return

        bluetoothHeadset?.connectedDevices?.let { devices ->
            if (devices.isEmpty()) {
                connectedDevice = null
                state = State.HEADSET_UNAVAILABLE
            } else {
                connectedDevice = devices[0]
                state = State.HEADSET_AVAILABLE
            }
            audioManager.updateAudioDeviceState()
        }
    }
    //endregion

    //region Private Methods

    private fun resetBluetoothState() {
        bluetoothHeadset = null
        connectedDevice = null
        scoConnectionAttempts = 0
    }

    private fun hasPermission() = context.checkPermission(
        android.Manifest.permission.BLUETOOTH,
        Process.myPid(),
        Process.myUid()
    ) == PackageManager.PERMISSION_GRANTED

    private fun logBluetoothInfo(adapter: BluetoothAdapter) {
        Log.d(TAG, "Bluetooth adapter: $adapter")
        Log.d(TAG, "Paired devices count: ${adapter.bondedDevices.size}")
    }

    private fun registerBluetoothReceivers() {
        try {
            context.registerReceiver(headsetReceiver, IntentFilter().apply {
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Bluetooth receiver", e)
        }
    }

    private fun unregisterBluetoothReceivers() {
        try {
            context.unregisterReceiver(headsetReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister Bluetooth receiver", e)
        }
    }

    private fun cleanupBluetoothResources() {
        unregisterBluetoothReceivers()
        cancelTimer()

        bluetoothAdapter?.let { adapter ->
            bluetoothHeadset?.let { headset ->
                try {
                    adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset)
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing Bluetooth profile proxy", e)
                }
            }
        }
        bluetoothHeadset = null
    }

    private fun startTimer() {
        handler.postDelayed(timeoutRunnable, BLUETOOTH_SCO_TIMEOUT_MS.toLong())
    }

    private fun cancelTimer() {
        handler.removeCallbacks(timeoutRunnable)
    }

    private fun handleBluetoothTimeout() {
        ThreadUtils.checkIsOnMainThread()
        if (state == State.UNINITIALIZED || bluetoothHeadset == null || state != State.SCO_CONNECTING) return

        val isConnected = bluetoothHeadset?.connectedDevices?.firstOrNull()?.let { device ->
            bluetoothHeadset?.isAudioConnected(device) == true
        } ?: false

        if (isConnected) {
            state = State.SCO_CONNECTED
            scoConnectionAttempts = 0
        } else {
            stopScoAudio()
        }
        audioManager.updateAudioDeviceState()
    }
    //endregion
}
