package com.mmk.webrtcfirebasevideocall.utils

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

open class MyEventListener : ValueEventListener {
    override fun onDataChange(p0: DataSnapshot) {
    }

    override fun onCancelled(error: DatabaseError) {
        Log.e("FIREBASE", "onCancelled called: ${error.message}")
    }
}