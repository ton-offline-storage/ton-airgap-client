package com.tonairgapclient.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tonairgapclient.R

class OnErrorActivity : AppCompatActivity() {
    private fun switchToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_on_error)
        findViewById<View>(R.id.popup_button).setOnClickListener { switchToMain() }
        val errorLabel = findViewById<TextView>(R.id.error_message_label)
        try {
            errorLabel.text = intent.getStringExtra("errorMessage")
        } catch (_: Exception) {
        }
    }
}