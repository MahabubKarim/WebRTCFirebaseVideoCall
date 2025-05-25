package com.mmk.webrtcfirebasevideocall.webrtc;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import org.webrtc.ThreadUtils;

import java.util.List;
import java.util.Set;

/**
 * Improved BluetoothManager with proper resource cleanup
 */
@SuppressLint("MissingPermission")
public class BluetoothManager {
    private static final String TAG = "BluetoothManager";
    private static final int BLUETOOTH_SCO_TIMEOUT_MS = 4000;
    private static final int MAX_SCO_CONNECTION_ATTEMPTS = 2;

    private final Context context;
    private final RTCAudioManager audioManager;
    private final AudioManager androidAudioManager;
    private final Handler handler;
    private final BluetoothProfile.ServiceListener serviceListener;
    private final BroadcastReceiver headsetReceiver;

    private int scoConnectionAttempts;
    private State state = State.UNINITIALIZED;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothHeadset bluetoothHeadset;
    private BluetoothDevice connectedDevice;
    private final Runnable timeoutRunnable = this::handleBluetoothTimeout;

    public static BluetoothManager create(Context context, RTCAudioManager audioManager) {
        return new BluetoothManager(context, audioManager);
    }

    private BluetoothManager(Context context, RTCAudioManager audioManager) {
        ThreadUtils.checkIsOnMainThread();
        this.context = context.getApplicationContext();
        this.audioManager = audioManager;
        this.androidAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.handler = new Handler(Looper.getMainLooper());
        this.serviceListener = new BluetoothServiceListener();
        this.headsetReceiver = new BluetoothHeadsetReceiver();
    }

    public State getState() {
        ThreadUtils.checkIsOnMainThread();
        return state;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        ThreadUtils.checkIsOnMainThread();

        if (!hasPermission()) {
            Log.w(TAG, "Bluetooth permission not granted");
            return;
        }

        if (state != State.UNINITIALIZED) {
            Log.w(TAG, "Already started, current state: " + state);
            return;
        }

        resetBluetoothState();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            Log.w(TAG, "Device doesn't support Bluetooth");
            return;
        }

        if (!androidAudioManager.isBluetoothScoAvailableOffCall()) {
            Log.w(TAG, "Bluetooth SCO not available off call");
            return;
        }

        logBluetoothInfo();

        if (!setupBluetoothProfileProxy()) {
            Log.e(TAG, "Failed to setup Bluetooth profile proxy");
            return;
        }

        registerBluetoothReceivers();
        state = State.HEADSET_UNAVAILABLE;
    }

    public void stop() {
        ThreadUtils.checkIsOnMainThread();

        if (state == State.UNINITIALIZED) {
            return;
        }

        stopScoAudio();
        cleanupBluetoothResources();
        state = State.UNINITIALIZED;
    }

    /**
     * New method: Completely releases all Bluetooth resources
     */
    public void release() {
        ThreadUtils.checkIsOnMainThread();
        stop();

        // Additional cleanup if needed
        handler.removeCallbacksAndMessages(null);

        // Clear all references
        connectedDevice = null;
        bluetoothAdapter = null;
        bluetoothHeadset = null;
    }

    public boolean startScoAudio() {
        ThreadUtils.checkIsOnMainThread();

        if (scoConnectionAttempts >= MAX_SCO_CONNECTION_ATTEMPTS) {
            Log.w(TAG, "Max SCO connection attempts reached");
            return false;
        }

        if (state != State.HEADSET_AVAILABLE) {
            Log.w(TAG, "Cannot start SCO, wrong state: " + state);
            return false;
        }

        state = State.SCO_CONNECTING;
        androidAudioManager.startBluetoothSco();
        androidAudioManager.setBluetoothScoOn(true);
        scoConnectionAttempts++;
        startTimer();
        return true;
    }

    public void stopScoAudio() {
        ThreadUtils.checkIsOnMainThread();

        if (state != State.SCO_CONNECTING && state != State.SCO_CONNECTED) {
            return;
        }

        cancelTimer();
        androidAudioManager.stopBluetoothSco();
        androidAudioManager.setBluetoothScoOn(false);
        state = State.SCO_DISCONNECTING;
    }

    public void updateDevice() {
        ThreadUtils.checkIsOnMainThread();

        if (state == State.UNINITIALIZED || bluetoothHeadset == null) {
            return;
        }

        List<BluetoothDevice> devices = bluetoothHeadset.getConnectedDevices();
        if (devices.isEmpty()) {
            connectedDevice = null;
            state = State.HEADSET_UNAVAILABLE;
        } else {
            connectedDevice = devices.get(0);
            state = State.HEADSET_AVAILABLE;
        }
        audioManager.updateAudioDeviceState();
    }

    private void resetBluetoothState() {
        bluetoothHeadset = null;
        connectedDevice = null;
        scoConnectionAttempts = 0;
    }

    private boolean hasPermission() {
        return context.checkPermission(
                android.Manifest.permission.BLUETOOTH,
                Process.myPid(),
                Process.myUid()
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private void logBluetoothInfo() {
        if (bluetoothAdapter == null) return;

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        Log.d(TAG, "Bluetooth adapter: " + bluetoothAdapter.toString());
        Log.d(TAG, "Paired devices count: " + pairedDevices.size());
    }

    private boolean setupBluetoothProfileProxy() {
        return bluetoothAdapter.getProfileProxy(context, serviceListener, BluetoothProfile.HEADSET);
    }

    private void registerBluetoothReceivers() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
            context.registerReceiver(headsetReceiver, filter);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register Bluetooth receiver", e);
        }
    }

    private void unregisterBluetoothReceivers() {
        try {
            context.unregisterReceiver(headsetReceiver);
        } catch (Exception e) {
            Log.e(TAG, "Failed to unregister Bluetooth receiver", e);
        }
    }

    private void cleanupBluetoothResources() {
        unregisterBluetoothReceivers();
        cancelTimer();

        if (bluetoothHeadset != null && bluetoothAdapter != null) {
            try {
                bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset);
            } catch (Exception e) {
                Log.e(TAG, "Error closing Bluetooth profile proxy", e);
            }
            bluetoothHeadset = null;
        }
    }

    private void startTimer() {
        handler.postDelayed(timeoutRunnable, BLUETOOTH_SCO_TIMEOUT_MS);
    }

    private void cancelTimer() {
        handler.removeCallbacks(timeoutRunnable);
    }

    private void handleBluetoothTimeout() {
        ThreadUtils.checkIsOnMainThread();

        if (state == State.UNINITIALIZED || bluetoothHeadset == null || state != State.SCO_CONNECTING) {
            return;
        }

        boolean scoConnected = false;
        List<BluetoothDevice> devices = bluetoothHeadset.getConnectedDevices();

        if (!devices.isEmpty()) {
            connectedDevice = devices.get(0);
            scoConnected = bluetoothHeadset.isAudioConnected(connectedDevice);
        }

        if (scoConnected) {
            state = State.SCO_CONNECTED;
            scoConnectionAttempts = 0;
        } else {
            stopScoAudio();
        }

        audioManager.updateAudioDeviceState();
    }

    private boolean isScoOn() {
        return androidAudioManager.isBluetoothScoOn();
    }

    public enum State {
        UNINITIALIZED,
        ERROR,
        HEADSET_UNAVAILABLE,
        HEADSET_AVAILABLE,
        SCO_DISCONNECTING,
        SCO_CONNECTING,
        SCO_CONNECTED
    }

    private class BluetoothServiceListener implements BluetoothProfile.ServiceListener {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HEADSET || state == State.UNINITIALIZED) {
                return;
            }

            bluetoothHeadset = (BluetoothHeadset) proxy;
            audioManager.updateAudioDeviceState();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile != BluetoothProfile.HEADSET || state == State.UNINITIALIZED) {
                return;
            }

            stopScoAudio();
            bluetoothHeadset = null;
            connectedDevice = null;
            state = State.HEADSET_UNAVAILABLE;
            audioManager.updateAudioDeviceState();
        }
    }

    private class BluetoothHeadsetReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (state == State.UNINITIALIZED) {
                return;
            }

            String action = intent.getAction();
            if (BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                handleConnectionStateChange(intent);
            } else if (BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED.equals(action)) {
                handleAudioStateChange(intent);
            }
        }

        private void handleConnectionStateChange(Intent intent) {
            int state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED);

            if (state == BluetoothHeadset.STATE_CONNECTED) {
                scoConnectionAttempts = 0;
                audioManager.updateAudioDeviceState();
            } else if (state == BluetoothHeadset.STATE_DISCONNECTED) {
                stopScoAudio();
                audioManager.updateAudioDeviceState();
            }
        }

        private void handleAudioStateChange(Intent intent) {
            int state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_AUDIO_DISCONNECTED);

            if (state == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
                cancelTimer();
                if (BluetoothManager.this.state == State.SCO_CONNECTING) {
                    BluetoothManager.this.state = State.SCO_CONNECTED;
                    scoConnectionAttempts = 0;
                    audioManager.updateAudioDeviceState();
                }
            } else if (state == BluetoothHeadset.STATE_AUDIO_DISCONNECTED && !isInitialStickyBroadcast()) {
                audioManager.updateAudioDeviceState();
            }
        }
    }
}