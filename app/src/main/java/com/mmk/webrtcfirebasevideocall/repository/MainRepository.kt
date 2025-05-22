package com.mmk.webrtcfirebasevideocall.repository

import com.mmk.webrtcfirebasevideocall.firebaseclient.FirebaseClient
import com.mmk.webrtcfirebasevideocall.utils.DataModel
import com.mmk.webrtcfirebasevideocall.utils.DataModelType.*
import javax.inject.Inject

class MainRepository @Inject constructor(
    private val firebaseClient: FirebaseClient,
) {
    var listener: Listener? = null

    fun login(username: String, password: String, isDone: (Boolean, String?) -> Unit) {
        firebaseClient.login(username, password, isDone)
    }

    fun observeUsersStatus(status: (List<Pair<String, String>>) -> Unit) {
        firebaseClient.observeUsersStatus(status)
    }

    fun initFirebase() {
        firebaseClient.subscribeForLatestEvent(object : FirebaseClient.Listener {
            override fun onLatestEventReceived(event: DataModel) {
                listener?.onLatestEventReceived(event)

                when(event.type) {
                    StartAudioCall -> {}
                    StartVideoCall -> {}
                    Offer -> {}
                    Answer -> {}
                    IceCandidates -> {}
                    EndCall -> {}
                }
            }
        })
    }

    interface Listener {
        fun onLatestEventReceived(event: DataModel)
    }
}