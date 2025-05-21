package com.mmk.webrtcfirebasevideocall.ui

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.mmk.webrtcfirebasevideocall.adapter.MainRecyclerViewAdapter
import com.mmk.webrtcfirebasevideocall.databinding.ActivityMainBinding
import com.mmk.webrtcfirebasevideocall.repository.MainRepository
import com.mmk.webrtcfirebasevideocall.service.MainServiceRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), MainRecyclerViewAdapter.Listener {
    private val TAG = "MainActivity"

    private var username: String? = null
    private var mainAdapter: MainRecyclerViewAdapter? = null
    @Inject lateinit var mainRepository: MainRepository
    @Inject lateinit var mainServiceRepository: MainServiceRepository

    private lateinit var views: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityMainBinding.inflate(layoutInflater)
        setContentView(views.root)
        init()
    }

    private fun init() {
        username = intent.getStringExtra("username")
        if (username == null) finish()
        //1. observe other users status
        subscribeObservers()
        //2. start foreground service to listen negotiations and calls.
        startMyService()
    }

    private fun subscribeObservers() {
        setupRecyclerView()
        // MainService.listener = this
        mainRepository.observeUsersStatus {
            Log.d(TAG, "subscribeObservers: $it")
             mainAdapter?.updateList(it)
        }
    }

    private fun startMyService() {
        mainServiceRepository.startService(username!!)
    }

    private fun setupRecyclerView() {
        mainAdapter = MainRecyclerViewAdapter(this)
        val layoutManager = LinearLayoutManager(this)
        views.mainRecyclerView.apply {
            setLayoutManager(layoutManager)
            adapter = mainAdapter
        }
    }

    override fun onVideoCallClicked(username: String) {}

    override fun onAudioCallClicked(username: String) {}

}