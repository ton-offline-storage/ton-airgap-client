package com.tonairgapclient.activities

import android.content.Intent
import android.os.Bundle
import android.os.StrictMode
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.tonairgapclient.R
import com.tonairgapclient.blockchain.TonlibController
import com.tonairgapclient.storage.AccountsKeeper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.ton.api.liteclient.config.LiteClientConfigGlobal
import org.ton.block.AddrStd
import org.ton.lite.client.LiteClient
import java.net.URL

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
        /*val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        val jsonFormat = Json {ignoreUnknownKeys = true}
        val config = jsonFormat.decodeFromString<LiteClientConfigGlobal>(
            URL("https://ton.org/global-config.json").readText()
        )

        //val address = "UQBZf6LoZG0KSONnGs1ljXgVe_KhxOspnT1NAwV0rWMy1mw9"
        //val address = "UQBWl0HakAranxsLaDPcQGJtExFYZ8g7NR4D-JdvaV4cAPoc"
        //val address = "UQCtiv7PrMJImWiF2L5oJCgPnzp-VML2CAt5cbn1VsKAxOVB"
        //val address = "UQCTd6HSN6kMQfYU5fel7BykpGh7ARzWunj2zmB9fhgGAzv_"
        val address = "UQDaokYMTQSpFFjbe9Pht2AH6AwD2PhnOGmSFsJH5WsNhQas"
        val liteClient = LiteClient(Dispatchers.IO, config)
        Log.d("Debug", "inited")
        runBlocking {
            val result = liteClient.getAccountState(AddrStd(address))
            Log.d("Debug", "got state")
            liteClient.getTransactions(result.address, result.lastTransactionId!!, 2)
        }*/

        Log.d("Debug", "Done")
    }
}
