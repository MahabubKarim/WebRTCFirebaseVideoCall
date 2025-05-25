package com.mmk.webrtcfirebasevideocall.webrtc

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.preference.PreferenceManager
import android.util.Log
import org.webrtc.ThreadUtils
import java.util.Collections

@SuppressLint("MissingPermission")
class RTCAudioManager private constructor(context: Context) {

    //region Enums & Interfaces

    enum class AudioDevice {
        SPEAKER_PHONE, WIRED_HEADSET, EARPIECE, BLUETOOTH, NONE
    }

    enum class AudioManagerState {
        UNINITIALIZED, RUNNING
    }

    interface AudioManagerEvents {
        fun onAudioDeviceChanged(selectedDevice: AudioDevice, availableDevices: Set<AudioDevice>)
    }

    //endregion

    //region Companion & Constants

    companion object {
        private const val TAG = "RTCAudioManager"
        private const val SPEAKERPHONE_AUTO = "auto"
        private const val SPEAKERPHONE_TRUE = "true"
        private const val SPEAKERPHONE_FALSE = "false"

        fun create(context: Context): RTCAudioManager {
            ThreadUtils.checkIsOnMainThread()
            return RTCAudioManager(context)
        }
    }

    //endregion

    //region Fields: Context and System Services

    private val context: Context = context.applicationContext
    private val androidAudioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager: BluetoothManager = BluetoothManager.create(context, this)
    private val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    //endregion

    //region Audio State

    private var state: AudioManagerState = AudioManagerState.UNINITIALIZED
    private var eventsListener: AudioManagerEvents? = null

    private var savedAudioMode: Int = AudioManager.MODE_INVALID
    private var savedIsSpeakerPhoneOn: Boolean = false
    private var savedIsMicrophoneMute: Boolean = false
    private var hasWiredHeadset: Boolean = false

    //endregion

    //region Audio Device Management

    private var defaultAudioDevice: AudioDevice
    private var selectedAudioDevice: AudioDevice = AudioDevice.NONE
    private var userSelectedAudioDevice: AudioDevice = AudioDevice.NONE
    private val availableAudioDevices: MutableSet<AudioDevice> = HashSet()

    //endregion

    //region Components

    private var proximitySensor: ProximitySensor? = null
    private val wiredHeadsetReceiver = WiredHeadsetReceiver()
    private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null

    //endregion

    //region Initialization

    init {
        val speakerphonePref = sharedPreferences.getString("speakerphone_preference", SPEAKERPHONE_AUTO)
        defaultAudioDevice = if (SPEAKERPHONE_FALSE == speakerphonePref) {
            AudioDevice.EARPIECE
        } else {
            AudioDevice.SPEAKER_PHONE
        }

        proximitySensor = ProximitySensor.create(context) { onProximitySensorChanged() }
    }

    //endregion

    //region Public API

    fun start(listener: AudioManagerEvents) {
        ThreadUtils.checkIsOnMainThread()
        if (state == AudioManagerState.RUNNING) {
            Log.w(TAG, "AudioManager is already running")
            return
        }

        this.eventsListener = listener
        this.state = AudioManagerState.RUNNING

        saveAudioState()
        setupAudioFocus()
        androidAudioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        androidAudioManager.isMicrophoneMute = false

        resetDeviceStates()
        bluetoothManager.start()
        registerReceivers()
        updateAudioDeviceState()
    }

    fun stop() {
        ThreadUtils.checkIsOnMainThread()
        if (state != AudioManagerState.RUNNING) {
            Log.w(TAG, "AudioManager is not running")
            return
        }

        state = AudioManagerState.UNINITIALIZED
        unregisterReceivers()
        bluetoothManager.stop()
        restoreAudioState()
        releaseAudioFocus()

        proximitySensor?.stop()
        proximitySensor = null
        eventsListener = null
    }

    fun release() {
        stop()
        bluetoothManager.release()
        availableAudioDevices.clear()
    }

    fun setDefaultAudioDevice(device: AudioDevice) {
        ThreadUtils.checkIsOnMainThread()
        defaultAudioDevice = when (device) {
            AudioDevice.SPEAKER_PHONE -> device
            AudioDevice.EARPIECE -> if (hasEarpiece()) device else AudioDevice.SPEAKER_PHONE
            else -> {
                Log.w(TAG, "Invalid default audio device: $device")
                return
            }
        }
        updateAudioDeviceState()
    }

    fun selectAudioDevice(device: AudioDevice) {
        ThreadUtils.checkIsOnMainThread()
        if (!availableAudioDevices.contains(device)) {
            Log.w(TAG, "Device not available: $device")
            return
        }
        userSelectedAudioDevice = device
        updateAudioDeviceState()
    }

    fun getAudioDevices(): Set<AudioDevice> {
        ThreadUtils.checkIsOnMainThread()
        return Collections.unmodifiableSet(HashSet(availableAudioDevices))
    }

    fun getSelectedAudioDevice(): AudioDevice {
        ThreadUtils.checkIsOnMainThread()
        return selectedAudioDevice
    }

    fun updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread()
        updateBluetoothState()

        val newDevices: MutableSet<AudioDevice> = HashSet()
        if (isBluetoothAvailable()) newDevices.add(AudioDevice.BLUETOOTH)
        if (hasWiredHeadset) {
            newDevices.add(AudioDevice.WIRED_HEADSET)
        } else {
            newDevices.add(AudioDevice.SPEAKER_PHONE)
            if (hasEarpiece()) newDevices.add(AudioDevice.EARPIECE)
        }

        val devicesChanged = availableAudioDevices != newDevices
        availableAudioDevices.clear()
        availableAudioDevices.addAll(newDevices)

        adjustUserSelection()
        manageBluetoothAudio()

        val newDevice = selectAppropriateDevice()
        if (devicesChanged || newDevice != selectedAudioDevice) {
            setAudioDeviceInternal(newDevice)
        }
    }

    //endregion

    //region Internal: Audio State

    private fun saveAudioState() {
        savedAudioMode = androidAudioManager.mode
        savedIsSpeakerPhoneOn = androidAudioManager.isSpeakerphoneOn
        savedIsMicrophoneMute = androidAudioManager.isMicrophoneMute
        hasWiredHeadset = checkWiredHeadset()
    }

    private fun restoreAudioState() {
        androidAudioManager.isSpeakerphoneOn = savedIsSpeakerPhoneOn
        androidAudioManager.isMicrophoneMute = savedIsMicrophoneMute
        androidAudioManager.mode = AudioManager.MODE_NORMAL
    }

    //endregion

    //region Internal: Focus & Sensor

    private fun setupAudioFocus() {
        audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            val focusState = when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
                AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
                else -> "UNKNOWN"
            }
            Log.d(TAG, "Audio focus changed: $focusState")
        }

        val result = androidAudioManager.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )

        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Audio focus request failed")
        }
    }

    private fun releaseAudioFocus() {
        audioFocusChangeListener?.let {
            androidAudioManager.abandonAudioFocus(it)
            audioFocusChangeListener = null
        }
    }

    //endregion

    //region Internal: Device Detection & Management

    private fun resetDeviceStates() {
        userSelectedAudioDevice = AudioDevice.NONE
        selectedAudioDevice = AudioDevice.NONE
        availableAudioDevices.clear()
    }

    private fun registerReceivers() {
        try {
            context.registerReceiver(wiredHeadsetReceiver, IntentFilter(Intent.ACTION_HEADSET_PLUG))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receiver", e)
        }
    }

    private fun unregisterReceivers() {
        try {
            context.unregisterReceiver(wiredHeadsetReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister receiver", e)
        }
    }

    private fun onProximitySensorChanged() {
        if (sharedPreferences.getString("speakerphone_preference", SPEAKERPHONE_AUTO) != SPEAKERPHONE_AUTO) return

        if (availableAudioDevices.contains(AudioDevice.EARPIECE) &&
            availableAudioDevices.contains(AudioDevice.SPEAKER_PHONE) &&
            availableAudioDevices.size == 2) {

            setAudioDeviceInternal(
                if (proximitySensor?.sensorReportsNearState() == true)
                    AudioDevice.EARPIECE
                else
                    AudioDevice.SPEAKER_PHONE
            )
        }
    }

    private fun setAudioDeviceInternal(device: AudioDevice) {
        when (device) {
            AudioDevice.SPEAKER_PHONE -> setSpeakerphoneOn(true)
            AudioDevice.EARPIECE, AudioDevice.BLUETOOTH, AudioDevice.WIRED_HEADSET -> setSpeakerphoneOn(false)
            else -> return
        }

        if (selectedAudioDevice != device) {
            selectedAudioDevice = device
            notifyAudioDeviceChanged()
        }
    }

    private fun setSpeakerphoneOn(on: Boolean) {
        if (androidAudioManager.isSpeakerphoneOn != on) {
            androidAudioManager.isSpeakerphoneOn = on
        }
    }

    private fun notifyAudioDeviceChanged() {
        eventsListener?.onAudioDeviceChanged(selectedAudioDevice, availableAudioDevices)
    }

    private fun hasEarpiece(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }

    @SuppressLint("WrongConstant")
    private fun checkWiredHeadset(): Boolean {
        val devices = androidAudioManager.getDevices(AudioManager.GET_DEVICES_ALL)
        return devices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
    }

    private fun updateBluetoothState() {
        when (bluetoothManager.state) {
            BluetoothManager.State.HEADSET_AVAILABLE,
            BluetoothManager.State.HEADSET_UNAVAILABLE,
            BluetoothManager.State.SCO_DISCONNECTING -> bluetoothManager.updateDevice()
            else -> {}
        }
    }

    private fun isBluetoothAvailable(): Boolean {
        return bluetoothManager.state in listOf(
            BluetoothManager.State.SCO_CONNECTED,
            BluetoothManager.State.SCO_CONNECTING,
            BluetoothManager.State.HEADSET_AVAILABLE
        )
    }

    private fun adjustUserSelection() {
        if (bluetoothManager.state == BluetoothManager.State.HEADSET_UNAVAILABLE &&
            userSelectedAudioDevice == AudioDevice.BLUETOOTH) {
            userSelectedAudioDevice = AudioDevice.NONE
        }

        if (hasWiredHeadset && userSelectedAudioDevice == AudioDevice.SPEAKER_PHONE) {
            userSelectedAudioDevice = AudioDevice.WIRED_HEADSET
        } else if (!hasWiredHeadset && userSelectedAudioDevice == AudioDevice.WIRED_HEADSET) {
            userSelectedAudioDevice = AudioDevice.SPEAKER_PHONE
        }
    }

    private fun manageBluetoothAudio() {
        val shouldStart = bluetoothManager.state == BluetoothManager.State.HEADSET_AVAILABLE &&
                (userSelectedAudioDevice == AudioDevice.NONE || userSelectedAudioDevice == AudioDevice.BLUETOOTH)

        val shouldStop = bluetoothManager.state in listOf(
            BluetoothManager.State.SCO_CONNECTED, BluetoothManager.State.SCO_CONNECTING
        ) && userSelectedAudioDevice !in listOf(AudioDevice.NONE, AudioDevice.BLUETOOTH)

        if (shouldStop) {
            bluetoothManager.stopScoAudio()
            bluetoothManager.updateDevice()
        }

        if (shouldStart && !shouldStop) {
            if (!bluetoothManager.startScoAudio()) {
                availableAudioDevices.remove(AudioDevice.BLUETOOTH)
            }
        }
    }

    private fun selectAppropriateDevice(): AudioDevice {
        return when {
            bluetoothManager.state == BluetoothManager.State.SCO_CONNECTED -> AudioDevice.BLUETOOTH
            hasWiredHeadset -> AudioDevice.WIRED_HEADSET
            else -> defaultAudioDevice
        }
    }

    //endregion

    //region Inner Classes

    private inner class WiredHeadsetReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_HEADSET_PLUG) {
                val state = intent.getIntExtra("state", 0)
                hasWiredHeadset = state == 1
                updateAudioDeviceState()
            }
        }
    }

    //endregion
}
