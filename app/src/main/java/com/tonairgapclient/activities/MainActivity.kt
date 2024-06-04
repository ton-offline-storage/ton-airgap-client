package com.tonairgapclient.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.tonairgapclient.R
import com.tonairgapclient.blockchain.TonlibController
import com.tonairgapclient.storage.AccountsKeeper

class MainActivity : AppCompatActivity() {
    private fun switchToQR() {
        val intent = Intent(this, QRActivity::class.java)
        startActivity(intent)
    }

    private fun switchToWallets() {
        val intent = Intent(this, WatchAccountsActivity::class.java)
        startActivity(intent)
    }

    private fun switchToError(errorMessage: String) {
        val intent = Intent(this, OnErrorActivity::class.java)
        intent.putExtra("errorMessage", errorMessage)
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d("Debug", "LOG")
        val switchToQRButton = findViewById<Button>(R.id.scan_qr_button)
        switchToQRButton.setOnClickListener { switchToQR() }
        val switchToWalletsButton = findViewById<Button>(R.id.watch_wallets_button)
        switchToWalletsButton.setOnClickListener { switchToWallets() }
        Log.d("Debug", "Started")
        AccountsKeeper.initKeeper(this.applicationContext)
        if (!TonlibController.initClient()) {
            switchToError(getString(R.string.liteclient_error))
            return
        }
        Log.d("Debug", "Done")
    }
}
