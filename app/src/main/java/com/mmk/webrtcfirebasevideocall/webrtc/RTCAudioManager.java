package com.mmk.webrtcfirebasevideocall.webrtc;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.preference.PreferenceManager;
import android.util.Log;

import org.webrtc.ThreadUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Improved RTCAudioManager with proper resource management and bug fixes
 */
@SuppressLint("MissingPermission")
public class RTCAudioManager {
    private static final String TAG = "RTCAudioManager";
    private static final String SPEAKERPHONE_AUTO = "auto";
    private static final String SPEAKERPHONE_TRUE = "true";
    private static final String SPEAKERPHONE_FALSE = "false";

    private final Context context;
    private final AudioManager androidAudioManager;
    private final BluetoothManager bluetoothManager;
    private final SharedPreferences sharedPreferences;

    private AudioManagerEvents eventsListener;
    private AudioManagerState state = AudioManagerState.UNINITIALIZED;

    // Audio state tracking
    private int savedAudioMode = AudioManager.MODE_INVALID;
    private boolean savedIsSpeakerPhoneOn = false;
    private boolean savedIsMicrophoneMute = false;
    private boolean hasWiredHeadset = false;

    // Audio device management
    private AudioDevice defaultAudioDevice;
    private AudioDevice selectedAudioDevice = AudioDevice.NONE;
    private AudioDevice userSelectedAudioDevice = AudioDevice.NONE;
    private final Set<AudioDevice> availableAudioDevices = new HashSet<>();

    // Components
    private ProximitySensor proximitySensor;
    private final BroadcastReceiver wiredHeadsetReceiver = new WiredHeadsetReceiver();
    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;

    public static RTCAudioManager create(Context context) {
        ThreadUtils.checkIsOnMainThread();
        return new RTCAudioManager(context);
    }

    private RTCAudioManager(Context context) {
        this.context = context.getApplicationContext();
        this.androidAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.bluetoothManager = BluetoothManager.create(context, this);
        this.sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);

        // Initialize default audio device based on preferences
        String speakerphonePref = sharedPreferences.getString("speakerphone_preference", SPEAKERPHONE_AUTO);
        this.defaultAudioDevice = SPEAKERPHONE_FALSE.equals(speakerphonePref) ?
                AudioDevice.EARPIECE : AudioDevice.SPEAKER_PHONE;

        // Initialize proximity sensor
        this.proximitySensor = ProximitySensor.create(context, this::onProximitySensorChanged);
    }

    public void start(AudioManagerEvents listener) {
        ThreadUtils.checkIsOnMainThread();

        if (state == AudioManagerState.RUNNING) {
            Log.w(TAG, "AudioManager is already running");
            return;
        }

        this.eventsListener = listener;
        this.state = AudioManagerState.RUNNING;

        // Save current audio state
        saveAudioState();

        // Setup audio focus
        setupAudioFocus();

        // Configure audio mode for VoIP
        androidAudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        androidAudioManager.setMicrophoneMute(false);

        // Initialize devices
        resetDeviceStates();
        bluetoothManager.start();

        // Register receivers
        registerReceivers();

        // Initial device selection
        updateAudioDeviceState();
    }

    public void stop() {
        ThreadUtils.checkIsOnMainThread();

        if (state != AudioManagerState.RUNNING) {
            Log.w(TAG, "AudioManager is not running");
            return;
        }

        state = AudioManagerState.UNINITIALIZED;

        // Unregister receivers
        unregisterReceivers();

        // Stop Bluetooth
        bluetoothManager.stop();

        // Restore audio state
        restoreAudioState();

        // Release audio focus
        releaseAudioFocus();

        // Clean up proximity sensor
        if (proximitySensor != null) {
            proximitySensor.stop();
            proximitySensor = null;
        }

        eventsListener = null;
    }

    public void release() {
        stop();
        if (bluetoothManager != null) {
            bluetoothManager.release();
        }
        availableAudioDevices.clear();
    }

    public void setDefaultAudioDevice(AudioDevice device) {
        ThreadUtils.checkIsOnMainThread();

        switch (device) {
            case SPEAKER_PHONE:
                defaultAudioDevice = device;
                break;
            case EARPIECE:
                defaultAudioDevice = hasEarpiece() ? device : AudioDevice.SPEAKER_PHONE;
                break;
            default:
                Log.w(TAG, "Invalid default audio device: " + device);
                return;
        }
        updateAudioDeviceState();
    }

    public void selectAudioDevice(AudioDevice device) {
        ThreadUtils.checkIsOnMainThread();

        if (!availableAudioDevices.contains(device)) {
            Log.w(TAG, "Device not available: " + device);
            return;
        }

        userSelectedAudioDevice = device;
        updateAudioDeviceState();
    }

    public Set<AudioDevice> getAudioDevices() {
        ThreadUtils.checkIsOnMainThread();
        return Collections.unmodifiableSet(new HashSet<>(availableAudioDevices));
    }

    public AudioDevice getSelectedAudioDevice() {
        ThreadUtils.checkIsOnMainThread();
        return selectedAudioDevice;
    }

    private void saveAudioState() {
        savedAudioMode = androidAudioManager.getMode();
        savedIsSpeakerPhoneOn = androidAudioManager.isSpeakerphoneOn();
        savedIsMicrophoneMute = androidAudioManager.isMicrophoneMute();
        hasWiredHeadset = checkWiredHeadset();
    }

    private void restoreAudioState() {
        androidAudioManager.setSpeakerphoneOn(savedIsSpeakerPhoneOn);
        androidAudioManager.setMicrophoneMute(savedIsMicrophoneMute);
        androidAudioManager.setMode(AudioManager.MODE_NORMAL);
    }

    private void setupAudioFocus() {
        audioFocusChangeListener = focusChange -> {
            String focusState;
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_GAIN:
                    focusState = "GAIN";
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                    focusState = "LOSS";
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    focusState = "LOSS_TRANSIENT";
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    focusState = "LOSS_TRANSIENT_CAN_DUCK";
                    break;
                default:
                    focusState = "UNKNOWN";
            }
            Log.d(TAG, "Audio focus changed: " + focusState);
        };

        int result = androidAudioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        );

        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Audio focus request failed");
        }
    }

    private void releaseAudioFocus() {
        if (audioFocusChangeListener != null) {
            androidAudioManager.abandonAudioFocus(audioFocusChangeListener);
            audioFocusChangeListener = null;
        }
    }

    private void resetDeviceStates() {
        userSelectedAudioDevice = AudioDevice.NONE;
        selectedAudioDevice = AudioDevice.NONE;
        availableAudioDevices.clear();
    }

    private void registerReceivers() {
        try {
            context.registerReceiver(wiredHeadsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
        } catch (Exception e) {
            Log.e(TAG, "Failed to register receiver", e);
        }
    }

    private void unregisterReceivers() {
        try {
            context.unregisterReceiver(wiredHeadsetReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Failed to unregister receiver", e);
        }
    }

    private void onProximitySensorChanged() {
        if (!SPEAKERPHONE_AUTO.equals(sharedPreferences.getString("speakerphone_preference", SPEAKERPHONE_AUTO))) {
            return;
        }

        if (availableAudioDevices.size() == 2 &&
                availableAudioDevices.contains(AudioDevice.EARPIECE) &&
                availableAudioDevices.contains(AudioDevice.SPEAKER_PHONE)) {

            setAudioDeviceInternal(proximitySensor.sensorReportsNearState() ?
                    AudioDevice.EARPIECE : AudioDevice.SPEAKER_PHONE);
        }
    }

    private void setAudioDeviceInternal(AudioDevice device) {
        switch (device) {
            case SPEAKER_PHONE:
                setSpeakerphoneOn(true);
                break;
            case EARPIECE:
            case BLUETOOTH:
            case WIRED_HEADSET:
                setSpeakerphoneOn(false);
                break;
            default:
                return;
        }

        if (selectedAudioDevice != device) {
            selectedAudioDevice = device;
            notifyAudioDeviceChanged();
        }
    }

    private void setSpeakerphoneOn(boolean on) {
        if (androidAudioManager.isSpeakerphoneOn() != on) {
            androidAudioManager.setSpeakerphoneOn(on);
        }
    }

    private boolean hasEarpiece() {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    @SuppressLint("WrongConstant")
    private boolean checkWiredHeadset() {
        AudioDeviceInfo[] devices = androidAudioManager.getDevices(AudioManager.GET_DEVICES_ALL);
        for (AudioDeviceInfo device : devices) {
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                return true;
            }
        }
        return false;
    }

    public void updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread();

        // Update Bluetooth state
        updateBluetoothState();

        // Determine available devices
        Set<AudioDevice> newDevices = new HashSet<>();

        // Add Bluetooth if available
        if (isBluetoothAvailable()) {
            newDevices.add(AudioDevice.BLUETOOTH);
        }

        // Handle wired headset
        if (hasWiredHeadset) {
            newDevices.add(AudioDevice.WIRED_HEADSET);
        } else {
            newDevices.add(AudioDevice.SPEAKER_PHONE);
            if (hasEarpiece()) {
                newDevices.add(AudioDevice.EARPIECE);
            }
        }

        // Update device list and user selection
        boolean devicesChanged = !availableAudioDevices.equals(newDevices);
        availableAudioDevices.clear();
        availableAudioDevices.addAll(newDevices);

        adjustUserSelection();

        // Manage Bluetooth audio
        manageBluetoothAudio();

        // Select appropriate audio device
        AudioDevice newDevice = selectAppropriateDevice();

        // Apply changes if needed
        if (devicesChanged || newDevice != selectedAudioDevice) {
            setAudioDeviceInternal(newDevice);
        }
    }

    private void updateBluetoothState() {
        BluetoothManager.State btState = bluetoothManager.getState();
        if (btState == BluetoothManager.State.HEADSET_AVAILABLE ||
                btState == BluetoothManager.State.HEADSET_UNAVAILABLE ||
                btState == BluetoothManager.State.SCO_DISCONNECTING) {
            bluetoothManager.updateDevice();
        }
    }

    private boolean isBluetoothAvailable() {
        BluetoothManager.State state = bluetoothManager.getState();
        return state == BluetoothManager.State.SCO_CONNECTED ||
                state == BluetoothManager.State.SCO_CONNECTING ||
                state == BluetoothManager.State.HEADSET_AVAILABLE;
    }

    private void adjustUserSelection() {
        BluetoothManager.State btState = bluetoothManager.getState();

        // Reset BT selection if not available
        if (btState == BluetoothManager.State.HEADSET_UNAVAILABLE &&
                userSelectedAudioDevice == AudioDevice.BLUETOOTH) {
            userSelectedAudioDevice = AudioDevice.NONE;
        }

        // Adjust for wired headset changes
        if (hasWiredHeadset && userSelectedAudioDevice == AudioDevice.SPEAKER_PHONE) {
            userSelectedAudioDevice = AudioDevice.WIRED_HEADSET;
        } else if (!hasWiredHeadset && userSelectedAudioDevice == AudioDevice.WIRED_HEADSET) {
            userSelectedAudioDevice = AudioDevice.SPEAKER_PHONE;
        }
    }

    private void manageBluetoothAudio() {
        boolean shouldStart = bluetoothManager.getState() == BluetoothManager.State.HEADSET_AVAILABLE &&
                (userSelectedAudioDevice == AudioDevice.NONE ||
                        userSelectedAudioDevice == AudioDevice.BLUETOOTH);

        boolean shouldStop = (bluetoothManager.getState() == BluetoothManager.State.SCO_CONNECTED ||
                bluetoothManager.getState() == BluetoothManager.State.SCO_CONNECTING) &&
                (userSelectedAudioDevice != AudioDevice.NONE &&
                        userSelectedAudioDevice != AudioDevice.BLUETOOTH);

        if (shouldStop) {
            bluetoothManager.stopScoAudio();
            bluetoothManager.updateDevice();
        }

        if (shouldStart && !shouldStop) {
            if (!bluetoothManager.startScoAudio()) {
                availableAudioDevices.remove(AudioDevice.BLUETOOTH);
            }
        }
    }

    private AudioDevice selectAppropriateDevice() {
        if (bluetoothManager.getState() == BluetoothManager.State.SCO_CONNECTED) {
            return AudioDevice.BLUETOOTH;
        } else if (hasWiredHeadset) {
            return AudioDevice.WIRED_HEADSET;
        } else {
            return defaultAudioDevice;
        }
    }

    private void notifyAudioDeviceChanged() {
        if (eventsListener != null) {
            eventsListener.onAudioDeviceChanged(selectedAudioDevice, availableAudioDevices);
        }
    }

    public enum AudioDevice {
        SPEAKER_PHONE, WIRED_HEADSET, EARPIECE, BLUETOOTH, NONE
    }

    public enum AudioManagerState {
        UNINITIALIZED, RUNNING
    }

    public interface AudioManagerEvents {
        void onAudioDeviceChanged(AudioDevice selectedDevice, Set<AudioDevice> availableDevices);
    }

    private class WiredHeadsetReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_HEADSET_PLUG.equals(intent.getAction())) {
                int state = intent.getIntExtra("state", 0);
                hasWiredHeadset = (state == 1);
                updateAudioDeviceState();
            }
        }
    }
}