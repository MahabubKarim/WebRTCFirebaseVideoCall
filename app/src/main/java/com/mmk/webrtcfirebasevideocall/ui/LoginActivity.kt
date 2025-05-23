package com.mmk.webrtcfirebasevideocall.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase
import com.mmk.webrtcfirebasevideocall.R
import com.mmk.webrtcfirebasevideocall.databinding.ActivityLoginBinding
import com.mmk.webrtcfirebasevideocall.repository.MainRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {
    private lateinit var views: ActivityLoginBinding
    @Inject lateinit var mainRepository: MainRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        views = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(views.root)
        views.btn.setOnClickListener {onClick()}

        // Testing Firebase Database connection
        /*FirebaseDatabase
            .getInstance("https://webrtcfirebasevideocall-3751e-default-rtdb.asia-southeast1.firebasedatabase.app/")
            .getReference("debugTest")
            .setValue("Hello")
            .addOnSuccessListener {
                Log.d("FIREBASE", "Write successful!")
                Toast.makeText(this, "Write successful!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Log.e("FIREBASE", "Failed: ${it.message}")
                Toast.makeText(this, "${it.message}", Toast.LENGTH_SHORT).show()
            }*/
    }

    private fun onClick() {
        try {
            val username = views.usernameEt.text.toString()
            val password = views.passwordEt.text.toString()
            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter both username and password", Toast.LENGTH_SHORT).show()
                return
            }
            mainRepository.login(username, password) { isDone, reason ->
                if (!isDone) {
                    Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
                } else {
                    //start moving to our main activity
                    startActivity(
                        Intent(
                            this,
                            MainActivity::class.java
                        ).apply {
                            putExtra("username", username)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Something went wrong, please try again",
                Toast.LENGTH_SHORT).show()
        }
    }
}