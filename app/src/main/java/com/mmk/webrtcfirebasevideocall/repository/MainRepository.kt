package com.mmk.webrtcfirebasevideocall.repository

import com.mmk.webrtcfirebasevideocall.firebaseclient.FirebaseClient
import javax.inject.Inject

class MainRepository @Inject constructor(
    private val firebaseClient: FirebaseClient,
) {
    fun login(username: String, password: String, isDone: (Boolean, String?) -> Unit) {
        firebaseClient.login(username, password, isDone)
    }
}