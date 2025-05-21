package com.mmk.webrtcfirebasevideocall.firebaseclient

import com.google.firebase.database.DatabaseReference
import com.google.gson.Gson
import javax.inject.Inject

class FirebaseClient @Inject constructor(
    private val dbRef:DatabaseReference,
    private val gson:Gson
) {
    fun login(username: String, password: String, done: (Boolean, String?) -> Unit) {
            TODO("Not yet implemented")
    }
}