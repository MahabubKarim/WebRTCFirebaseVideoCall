package com.mmk.webrtcfirebasevideocall.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mmk.webrtcfirebasevideocall.R
import com.mmk.webrtcfirebasevideocall.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var views: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        views = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(R.layout.activity_main)
        init()
    }

    private fun init() {
        views.apply {
            btn.setOnClickListener {
                mainRepository.login(
                    usernameEt.text.toString(), passwordEt.text.toString()
                ) { isDone, reason ->
                    if (!isDone) {
                        Toast.makeText(this@LoginActivity, reason, Toast.LENGTH_SHORT).show()
                    } else {
                        //start moving to our main activity
                        startActivity(Intent(this@LoginActivity, MainActivity::class.java).apply {
                            putExtra("username", usernameEt.text.toString())
                        })
                    }
                }
            }
        }
    }
}