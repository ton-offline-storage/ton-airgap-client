package com.tonairgapclient.activities

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.android.volley.RequestQueue
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.material.snackbar.Snackbar
import com.tonairgapclient.R
import com.tonairgapclient.blockchain.TonlibController
import com.tonairgapclient.blockchain.TonlibController.getAccountValues
import com.tonairgapclient.blockchain.TonlibController.getSeqno
import com.tonairgapclient.blockchain.TonlibController.sendBytes
import com.tonairgapclient.datamodels.AccountValues
import com.tonairgapclient.storage.AccountsKeeper
import com.tonairgapclient.utils.trimZeros
import kotlinx.coroutines.launch
import org.ton.bigint.toBigInt
import org.ton.block.Coins
import java.math.BigInteger


/*DONE:
//Deal with long transactions loading
//Add fee estimation
//identicon update lag
//Add resizing to address label in explorer!
//Background account updates
//deployment postpone
//Deal with rejected transaction issue
*/

//Asynchronous client init ?
//split thousands zeros ?
//interpret liteserver exceptions ?
//animated loadings
//add sp ?
//check all sizes
//preciser fee display ?
//new font, styles, dark theme
//seed pasting to offline
//display total balance

class SendTransactionActivity : AppCompatActivity() {
    private lateinit var transactionBytes: ByteArray
    private lateinit var sourceAddress: String
    private var isDeployment = false
    private lateinit var transferAmount: Coins
    private lateinit var feeValue: Coins
    private var transactionSeqno: Long = 0
    private lateinit var statusLabel: TextView
    private lateinit var feeLabel: TextView
    private lateinit var sendButton: Button
    private lateinit var postponeButton: Button
    private lateinit var clipboard: ClipboardManager
    private val popups = arrayOfNulls<PopupWindow>(3)
    private lateinit var postponePopup: PopupWindow
    private lateinit var comparePopup: PopupWindow
    private lateinit var feeRequestQueue: RequestQueue
    private object PopupType {
        const val ON_ERROR = 0
        const val ON_SUCCESS = 1
        const val ON_PREVALIDATION_FAIL = 2
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("Debugs", "Send transaction destroyed")
    }

    override fun onPause() {
        super.onPause()
        Log.d("Debugs", "Send transaction paused")
    }
    override fun onResume() {
        super.onResume()
        Log.d("Debugs", "Send transaction resumed")
    }
    private fun switchToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
    }
    private fun switchToError(errorMessage: String) {
        val intent = Intent(this, OnErrorActivity::class.java)
        intent.putExtra("errorMessage", errorMessage)
        startActivity(intent)
    }
    private fun prevalidateTransaction(view: View) {
        Log.d("Debug", "Prevalidating")
        sendButton.isEnabled = false
        if(isDeployment) postponeButton.isEnabled = false
        statusLabel.text = getString(R.string.prevalidating)
        statusLabel.visibility = View.VISIBLE
        lifecycleScope.launch {
            val status = getAccountValues(sourceAddress)
            if (status == null) {
                showSentPopup(
                    view,
                    PopupType.ON_PREVALIDATION_FAIL,
                    getString(R.string.prevalidation_error)
                )
                return@launch
            }
            val accountIndex = AccountsKeeper.findAccount(sourceAddress)
            if(accountIndex != null) {
                AccountsKeeper.updateBalance(accountIndex, status.balance)
                AccountsKeeper.store(this@SendTransactionActivity.applicationContext)
            }
            if (isDeployment) {
                if (status.state !== AccountValues.State.UNINIT) {
                    showSentPopup(
                        view, PopupType.ON_PREVALIDATION_FAIL,
                        getString(R.string.account_init_error) + " " + getString(R.string.invalid_transaction)
                    )
                    return@launch
                }
                if (status.balance.amount.value <= BigInteger.ZERO) {
                    showSentPopup(
                        view, PopupType.ON_PREVALIDATION_FAIL,
                        getString(R.string.zero_balance) + " " + getString(R.string.invalid_transaction)
                    )
                    return@launch
                }
                if (this@SendTransactionActivity::feeValue.isInitialized && status.balance.amount.value < feeValue.amount.value) {
                    showSentPopup(
                        view, PopupType.ON_PREVALIDATION_FAIL,
                        getString(R.string.lower_than_fee)
                    )
                    return@launch
                }
            } else {
                if (status.state === AccountValues.State.UNINIT) {
                    showSentPopup(
                        view, PopupType.ON_PREVALIDATION_FAIL,
                        getString(R.string.account_uninit_error) + " " + getString(R.string.invalid_transaction)
                    )
                    return@launch
                }
                if (status.balance.amount.value < transferAmount.amount.value) {
                    showSentPopup(
                        view, PopupType.ON_PREVALIDATION_FAIL,
                        getString(R.string.low_balance) + " " + getString(R.string.invalid_transaction)
                    )
                    return@launch
                }
                if (this@SendTransactionActivity::feeValue.isInitialized &&
                    status.balance.amount.value < feeValue.amount.value + transferAmount.amount.value) {
                    showSentPopup(
                        view, PopupType.ON_PREVALIDATION_FAIL,
                        getString(R.string.lower_than_fee)
                    )
                    return@launch
                }
                val walletSeqno = getSeqno(sourceAddress)
                if (walletSeqno == null) {
                    showSentPopup(
                        view,
                        PopupType.ON_PREVALIDATION_FAIL,
                        getString(R.string.prevalidation_error)
                    )
                    return@launch
                }
                if(accountIndex != null) {
                    AccountsKeeper.updateSeqno(accountIndex, walletSeqno)
                    AccountsKeeper.store(this@SendTransactionActivity.applicationContext)
                }
                Log.d("Debug", "Real seqno: $walletSeqno")
                if (walletSeqno != transactionSeqno) {
                    showSentPopup(
                        view, PopupType.ON_PREVALIDATION_FAIL,
                        getString(R.string.seqno_differs) + " " + getString(R.string.invalid_transaction)
                    )
                    return@launch
                }
            }
            sendTransaction(view)
        }
    }
    private suspend fun sendTransaction(view: View) {
        Log.d("Debug", "Sending...")
        sendButton.isEnabled = false
        if(isDeployment) postponeButton.isEnabled = false
        statusLabel.text = getString(R.string.sending)
        statusLabel.visibility = View.VISIBLE
        val type = if (sendBytes(transactionBytes)) PopupType.ON_SUCCESS else PopupType.ON_ERROR
        Log.d("Debug", "Sent Bytes: $type")
        showSentPopup(view, type)
        Log.d("Debug", if (type == 1) "Sent Successfully" else "Sending error")
    }
    private fun postponeDeployment() {
        Log.d("Debug", "Postponing deployment...")
        val accountIndex = AccountsKeeper.findAccount(sourceAddress)!!
        AccountsKeeper.needDeployment(accountIndex, transactionBytes)
        AccountsKeeper.launch { AccountsKeeper.store(this@SendTransactionActivity.applicationContext) }
        AccountsKeeper.startDeployer(this.applicationContext)
        switchToMain()
    }
    private fun setupPopupCommon(resource: Int, addDismiss: Boolean = true): PopupWindow {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val popupView = inflater.inflate(resource, findViewById(R.id.popup_root), false)
        val window = PopupWindow(
            popupView,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        if(addDismiss) {
            popupView.setOnTouchListener { v: View, _: MotionEvent? ->
                v.performClick()
                window.dismiss()
                true
            }
        }
        return window
    }
    private fun setupSentPopup(type: Int) {
        popups[type] = setupPopupCommon(R.layout.sending_result_popup, type != PopupType.ON_SUCCESS)
        val popupView = popups[type]?.contentView ?: return
        val popupLabel = popupView.findViewById<TextView>(R.id.popup_label)
        val popupButton = popupView.findViewById<Button>(R.id.popup_button)
        when (type) {
            PopupType.ON_ERROR -> {
                popupLabel.setText(R.string.sending_error)
                popupButton.setOnClickListener { switchToMain() }
            }

            PopupType.ON_SUCCESS -> {
                popupLabel.setText(R.string.transaction_success)
                popupButton.setOnClickListener { switchToMain() }
            }
            PopupType.ON_PREVALIDATION_FAIL -> {
                popupButton.setText(R.string.send_anyway)
                popupButton.setOnClickListener { view: View ->
                    popups[type]!!.dismiss()
                    lifecycleScope.launch { sendTransaction(view) }
                }
            }
        }
    }
    private fun setupComparePopup() {
        comparePopup = setupPopupCommon(R.layout.address_compare_popup)
    }
    private fun setupPostponePopup() {
        postponePopup = setupPopupCommon(R.layout.postpone_popup)
        val okPostponeButton: Button = postponePopup.contentView.findViewById(R.id.ok_postpone_button)
        okPostponeButton.setOnClickListener{postponeDeployment()}
    }
    private fun showSentPopup(view: View, type: Int, message: String? = null) {
        if (message != null) {
            val popupLabel = popups[type]!!.contentView.findViewById<TextView>(R.id.popup_label)
            popupLabel.text = message
        }
        statusLabel.visibility = View.INVISIBLE
        popups[type]!!.showAtLocation(view, Gravity.CENTER, 0, 0)
        sendButton.isEnabled = true
        if(isDeployment) postponeButton.isEnabled = true
    }
    private fun showComparePopup(view: View, txnAddress: SpannableString, clipboardAddress: String) {
        val txnAddressLabel = comparePopup.contentView.findViewById<TextView>(R.id.txn_address)
        val clipboardAddressLabel = comparePopup.contentView.findViewById<TextView>(R.id.clipboard_address)
        txnAddressLabel.text = txnAddress
        clipboardAddressLabel.text = clipboardAddress
        runOnUiThread {
            comparePopup.showAtLocation(view, Gravity.CENTER, 0, 0)
        }
    }
    private fun showPostponePopup(view: View) {
        runOnUiThread {
            postponePopup.showAtLocation(view, Gravity.CENTER, 0, 0)
        }
    }
    private fun compareAddress(view: View) {
        if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription!!.hasMimeType(
                ClipDescription.MIMETYPE_TEXT_PLAIN
            )
        ) {
            val item = clipboard.primaryClip!!.getItemAt(0)
            val clipAddress = item.text.toString()
            if(TonlibController.validateAddress(clipAddress)) {
                val addressLabel = findViewById<TextView>(R.id.transfer_address_label)
                val highlightedAddress = SpannableString(addressLabel.text.toString())
                highlightedAddress.forEachIndexed { index, c ->
                    if(c != clipAddress.getOrNull(index)) {
                        highlightedAddress.setSpan(BackgroundColorSpan(ContextCompat.getColor(this,
                            R.color.transparent_red
                        )), index, index + 1, 0)
                    }
                }
                Log.d("Debug", addressLabel.text.toString())
                Log.d("Debug", item.text.toString())
                if (clipAddress == addressLabel.text.toString()) {
                    showCompareSnackBar(true)
                } else {
                    showComparePopup(view, highlightedAddress, clipAddress)
                }
            } else {
                showCompareSnackBar(false)
            }
        } else {
           showCompareSnackBar(false)
        }
    }
    private fun showCompareSnackBar(success: Boolean) {
        val customSnackView: View = layoutInflater.inflate(if(success) R.layout.address_match_snack
            else R.layout.clipboard_trash_snack, findViewById(R.id.root_layout), false)
        val snackbar = Snackbar.make(findViewById(R.id.upper_snack_place), "", Snackbar.LENGTH_SHORT)
        val snackbarLayout: Snackbar.SnackbarLayout = snackbar.view as? Snackbar.SnackbarLayout ?: return
        snackbarLayout.setBackgroundColor(Color.TRANSPARENT)
        snackbarLayout.addView(customSnackView, 0)
        snackbar.show()
    }
    private fun makeFeeRequest(bytes: ByteArray, isDeployment: Boolean) {
        val requestObj = TonlibController.makeFeeRequestObj(bytes, isDeployment)
        if(requestObj == null) {
            feeLabel.text = getString(R.string.estimate_fee_fail)
            Log.d("Debug", "Couldn\'t extract fee data")
            return
        }
        Log.d("Debug", "request start, json: $requestObj")
        feeRequestQueue.add(object : JsonObjectRequest(
            Method.POST, TonlibController.TONCENTER, requestObj,
            { response ->
                Log.d("Debug", "Toncenter response: $response")
                if(response.getBoolean("ok")) {
                    val fees = response.getJSONObject("result").getJSONObject("source_fees")
                    feeValue = Coins(fees.getLong("in_fwd_fee").toBigInt() +
                    fees.getLong("storage_fee").toBigInt() +
                    fees.getLong("gas_fee").toBigInt() +
                    fees.getLong("fwd_fee").toBigInt())
                    feeLabel.text = trimZeros(feeValue.toString())
                    Log.d("Debug", "Fee is $feeValue")
                } else {
                    feeLabel.text = getString(R.string.estimate_fee_fail)
                    Log.d("Debug", response.getJSONObject("error").toString())
                }
            },
            { error ->
                feeLabel.text = getString(R.string.estimate_fee_fail)
                Log.d("Debug", "Toncenter request error: $error " + error.stackTraceToString())
            }) {
                override fun getHeaders(): MutableMap<String, String> {
                    return mutableMapOf("Content-Type" to "application/json")
                }
            }
        )
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("Debugs", "Send transaction created")
        feeRequestQueue = Volley.newRequestQueue(this)
        clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        sourceAddress = intent.getStringExtra("source") ?:
            return switchToError("Couldn't obtain source address")
        transactionBytes = intent.getByteArrayExtra("transactionBytes") ?:
            return switchToError("Couldn't obtain transaction bytes")
        isDeployment = intent.getBooleanExtra("isDeployment", false)
        if (isDeployment) {
            setContentView(R.layout.activity_send_deployment)
            feeLabel = findViewById(R.id.fee_label)
            makeFeeRequest(transactionBytes, isDeployment)
            val addressLabel = findViewById<TextView>(R.id.deployment_address_label)
            addressLabel.text = sourceAddress
            if(AccountsKeeper.findAccount(sourceAddress) == null) {
                AccountsKeeper.addAccount(sourceAddress, this.applicationContext)
                lifecycleScope.launch {
                    AccountsKeeper.store(this@SendTransactionActivity.applicationContext)
                }
            }
            postponeButton = findViewById(R.id.postpone_button)
            postponeButton.setOnClickListener(this::showPostponePopup)
            setupPostponePopup()
        } else {
            Log.d("Debug", "has transfer")
            setContentView(R.layout.activity_send_transfer)
            feeLabel = findViewById(R.id.fee_label)
            makeFeeRequest(transactionBytes, isDeployment)
            val addressLabel = findViewById<TextView>(R.id.transfer_address_label)
            val amountLabel = findViewById<TextView>(R.id.transfer_amount_label)
            val commentLabel = findViewById<TextView>(R.id.comment_label)
            val commentContentLabel = findViewById<TextView>(R.id.comment_content_label)
            if (intent.getStringExtra("comment") != null) {
                commentContentLabel.text = intent.getStringExtra("comment")
                commentLabel.visibility = View.VISIBLE
                commentContentLabel.visibility = View.VISIBLE
            }
            addressLabel.text = intent.getStringExtra("dest")
            transferAmount = Coins(BigInteger(intent.getByteArrayExtra("amount")))
            amountLabel.text = getString(R.string.ton_amount, trimZeros(transferAmount.toString()))
            transactionSeqno = intent.getLongExtra("seqno", 0)
            val compareButton = findViewById<Button>(R.id.compare_clipboard_button)
            compareButton.setOnClickListener(this::compareAddress)
            Log.d("Debug", "end of transfer")
        }
        statusLabel = findViewById(R.id.status_label)
        val backArrowButton = findViewById<ImageButton>(R.id.back_arrow)
        backArrowButton.setOnClickListener{ switchToMain() }
        sendButton = findViewById(R.id.send_transaction_button)
        sendButton.setOnClickListener(this::prevalidateTransaction)
        setupSentPopup(PopupType.ON_ERROR)
        setupSentPopup(PopupType.ON_SUCCESS)
        setupSentPopup(PopupType.ON_PREVALIDATION_FAIL)
        setupComparePopup()
        Log.d("Debug", "EOF create")
    }
}