package com.tonairgapclient.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.budiyev.android.codescanner.CodeScanner
import com.budiyev.android.codescanner.CodeScannerView
import com.budiyev.android.codescanner.DecodeCallback
import com.budiyev.android.codescanner.ErrorCallback
import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import com.tonairgapclient.R
import com.tonairgapclient.blockchain.TonlibController
import com.tonairgapclient.blockchain.TonlibController.extractAddressFromInitBoc
import com.tonairgapclient.blockchain.TonlibController.extractDataFromTransferBoc
import com.tonairgapclient.utils.getRawBytes
import kotlinx.coroutines.runBlocking
import org.ton.block.Coins
import java.util.Arrays

class QRActivity : AppCompatActivity() {
    private lateinit var qrScanner: CodeScanner
    private fun switchToError(errorMessage: String) {
        val intent = Intent(this, OnErrorActivity::class.java)
        intent.putExtra("errorMessage", errorMessage)
        startActivity(intent)
    }

    private fun switchToDeployment(source: String, bytes: ByteArray) {
        val intent = Intent(this, SendTransactionActivity::class.java)
        intent.putExtra("isDeployment", true)
        intent.putExtra("source", source)
        intent.putExtra("transactionBytes", bytes)
        startActivity(intent)
    }

    private fun switchToTransfer(
        source: String,
        dest: String,
        comment: String?,
        amount: Coins,
        seqno: Long,
        bytes: ByteArray
    ) {
        val intent = Intent(this, SendTransactionActivity::class.java)
        intent.putExtra("isDeployment", false)
        intent.putExtra("source", source)
        intent.putExtra("dest", dest)
        intent.putExtra("comment", comment)
        intent.putExtra("amount", amount.amount.value.toByteArray())
        intent.putExtra("seqno", seqno)
        intent.putExtra("transactionBytes", bytes)
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.qr_activity)
        setupPermissionsAndScanner()
    }

    private fun setupCodeScanner() {
        if (this::qrScanner.isInitialized) {
            return
        }
        val scannerView = findViewById<CodeScannerView>(R.id.qr_scanner)
        qrScanner = CodeScanner(this, scannerView)
        qrScanner.formats = listOf(BarcodeFormat.QR_CODE)
        qrScanner.decodeCallback = DecodeCallback { scanResult: Result -> onDecoded(scanResult) }
        qrScanner.errorCallback = ErrorCallback { e: Throwable -> onDecodeError(e) }
    }

    private fun onDecoded(scanResult: Result) {
        Log.d("Debug", "onDecodedQr")
        runOnUiThread {
            val bytes = getRawBytes(scanResult)
            Log.d("Debug", "Decoded: " + Arrays.toString(bytes))
            if (bytes == null) {
                Log.d("Debug", "Empty bytes received")
                switchToError(getString(R.string.transaction_decoding_error))
                return@runOnUiThread
            }
            val transferData = extractDataFromTransferBoc(bytes)
            if (transferData != null) {
                Log.d("Debug", "Address received!: " + transferData.dest)
                Log.d("Debug", "Coins received!: " + transferData.amount)
                Log.d("Debug", "Source address received: " + transferData.source)
                Log.d("Debug", "Seqno received: " + transferData.seqno)
                Log.d("Debug", "Comment received: " + transferData.comment)
                switchToTransfer(
                    transferData.source, transferData.dest, transferData.comment,
                    transferData.amount, transferData.seqno, bytes
                )
                return@runOnUiThread
            }
            val address = extractAddressFromInitBoc(bytes)
            if (address != null) {
                Log.d("Debug", "Deployment address... : $address")
                switchToDeployment(address, bytes)
                return@runOnUiThread
            }
            Log.d("Debug", "Couldn't extract")
            switchToError(getString(R.string.transaction_decoding_error))
            Log.d("Debug", "Still sending")
            runBlocking {
                Log.d("Debug", "Success: " + TonlibController.sendBytes(bytes))
            }

        }
        Log.d("Debug", "Decoded1")
    }

    private fun onDecodeError(e: Throwable) {
        runOnUiThread {
            Log.d("Debug", "Scanning or decoding error: " + e.message)
            switchToError(getString(R.string.qr_scanner_error) + ": " + e.message)
        }
    }

    private fun setupPermissionsAndScanner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_REQUEST_CODE
            )
        } else {
            setupCodeScanner()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST_CODE) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    this@QRActivity,
                    getString(R.string.need_permission_text),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                setupCodeScanner()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (this::qrScanner.isInitialized) {
            qrScanner.startPreview()
        }
    }

    override fun onPause() {
        if (this::qrScanner.isInitialized) {
            qrScanner.releaseResources()
        }
        super.onPause()
    }

    companion object {
        private const val CAMERA_REQUEST_CODE = 179
    }
}