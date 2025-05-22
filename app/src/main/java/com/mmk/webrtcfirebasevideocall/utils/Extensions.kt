package com.mmk.webrtcfirebasevideocall.utils

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.mmk.webrtcfirebasevideocall.R
import com.permissionx.guolindev.PermissionX

fun AppCompatActivity.getCameraAndMicPermission(success: () -> Unit) {
    PermissionX.init(this).permissions(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        ).request { allGranted, _, _ ->
            if (allGranted) {
                success()
            } else {
                Toast.makeText(
                    this, getString(R.string.camera_and_mic_required), Toast.LENGTH_SHORT
                ).show()
            }
        }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
fun AppCompatActivity.getMediaProjectionPermission(success: () -> Unit) {
    PermissionX.init(this).permissions(
            Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
        ).request { allGranted, _, _ ->
            if (allGranted) {
                success()
            } else {
                Toast.makeText(this, "Permissions are required", Toast.LENGTH_SHORT).show()
            }
        }
}

fun Int.convertToHumanTime(): String {
    val seconds = this % 60
    val minutes = this / 60
    val secondsString = if (seconds < 10) "0$seconds" else "$seconds"
    val minutesString = if (minutes < 10) "0$minutes" else "$minutes"
    return "$minutesString:$secondsString"
}