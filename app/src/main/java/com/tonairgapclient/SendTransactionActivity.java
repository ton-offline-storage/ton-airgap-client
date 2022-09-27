package com.tonairgapclient;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import org.ton.block.Coins;
import org.ton.block.VarUInteger;

import java.math.BigInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SendTransactionActivity extends AppCompatActivity {
    private byte[] transactionBytes;
    private String sourceAddress;
    private boolean isDeployment;
    private Coins transferAmount;
    private int transactionSeqno;
    private TextView statusLabel;
    private Button sendButton;
    private final PopupWindow[] popups = new PopupWindow[3];
    private static class PopupType {
        private static final int ON_ERROR = 0;
        private static final int ON_SUCCESS = 1;
        private static final int ON_PREVALIDATION_FAIL = 2;
    }
    private final ExecutorService service = Executors.newSingleThreadExecutor();
    private void switchToMain(View view) {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
    }
    private void prevalidateTransaction(View view) {
        Log.d("Debug", "Prevalidating");
        sendButton.setEnabled(false);
        statusLabel.setText(getString(R.string.prevalidating));
        statusLabel.setVisibility(View.VISIBLE);
        service.execute(() -> {
            AccountStatus status = TonlibController.INSTANCE.getAccountStatus(sourceAddress);
            if(status == null) {
                showPopup(view, PopupType.ON_PREVALIDATION_FAIL, getString(R.string.prevalidation_error));
                return;
            }
            if(isDeployment) {
                if (status.getState() != AccountStatus.State.UNINIT) {
                    showPopup(view, PopupType.ON_PREVALIDATION_FAIL,
                            getString(R.string.account_init_error) + " " + getString(R.string.invalid_transaction));
                    return;
                }
                if (status.getBalance().getAmount().toInt() == 0) {
                    showPopup(view, PopupType.ON_PREVALIDATION_FAIL,
                            getString(R.string.zero_balance) + " " + getString(R.string.invalid_transaction));
                    return;
                }
            } else {
                if (status.getState() == AccountStatus.State.UNINIT) {
                    showPopup(view, PopupType.ON_PREVALIDATION_FAIL,
                            getString(R.string.account_uninit_error) + " " + getString(R.string.invalid_transaction));
                    return;
                }
                if(status.getBalance().getAmount().getValue().compareTo(transferAmount.getAmount().getValue()) < 0) {
                    showPopup(view, PopupType.ON_PREVALIDATION_FAIL,
                            getString(R.string.low_balance) + " " + getString(R.string.invalid_transaction));
                    return;
                }
                Integer walletSeqno = TonlibController.INSTANCE.getSeqno(sourceAddress);
                if(walletSeqno == null) {
                    showPopup(view, PopupType.ON_PREVALIDATION_FAIL, getString(R.string.prevalidation_error));
                    return;
                }
                Log.d("Debug", "Real seqno: " + walletSeqno);
                if(walletSeqno != transactionSeqno) {
                    showPopup(view, PopupType.ON_PREVALIDATION_FAIL,
                            getString(R.string.seqno_differs) + " " + getString(R.string.invalid_transaction));
                    return;
                }
            }
            sendTransaction(view);
        });
    }
    private void sendTransaction(View view) {
        Log.d("Debug", "Sending...");
        runOnUiThread(() -> {
            sendButton.setEnabled(false);
            statusLabel.setText(getString(R.string.sending));
            statusLabel.setVisibility(View.VISIBLE);
        });
        service.execute(() -> {
            int type = TonlibController.INSTANCE.sendBytes(transactionBytes) ?
                    PopupType.ON_SUCCESS : PopupType.ON_ERROR;
            Log.d("Debug", "Sent Bytes: " + type);
            showPopup(view, type);
            Log.d("Debug", type == 1 ? "Sent Successfully" : "Sending error");
        });
    }
    private void showPopup(View view, int type) {
        showPopup(view, type, null);
    }
    private void showPopup(View view, int type, String message) {
        if(message != null) {
            TextView popupLabel = popups[type].getContentView().findViewById(R.id.popup_label);
            popupLabel.setText(message);
        }
        runOnUiThread(() -> {
            statusLabel.setVisibility(View.INVISIBLE);
            popups[type].showAtLocation(view, Gravity.CENTER, 0, 0);
            sendButton.setEnabled(true);
        });
    }
    private void setupPopup(int type) {
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        View popupView = inflater.inflate(R.layout.sending_result_popup, null);
        popups[type] = new PopupWindow(popupView,
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        if(type != PopupType.ON_SUCCESS) {
            popupView.setOnTouchListener((View v, MotionEvent event) -> {
                v.performClick();
                popups[type].dismiss();
                return true;
            });
        }
        TextView popupLabel = popupView.findViewById(R.id.popup_label);
        Button popupButton = popupView.findViewById(R.id.popup_button);
        switch (type) {
            case PopupType.ON_ERROR:
                popupLabel.setText(R.string.sending_error);
                popupButton.setOnClickListener(this::switchToMain);
                break;
            case PopupType.ON_SUCCESS:
                popupLabel.setText(R.string.transaction_success);
                popupButton.setOnClickListener(this::switchToMain);
                break;
            case PopupType.ON_PREVALIDATION_FAIL:
                popupButton.setText(R.string.send_anyway);
                popupButton.setOnClickListener((View view) -> {
                    popups[type].dismiss();
                    sendTransaction(view);
                });
                break;
        }
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sourceAddress = getIntent().getStringExtra("source");
        transactionBytes = getIntent().getByteArrayExtra("transactionBytes");
        isDeployment = getIntent().getBooleanExtra("isDeployment", false);
        if(isDeployment) {
            setContentView(R.layout.activity_send_deployment);
            TextView addressLabel = findViewById(R.id.deployment_address_label);
            addressLabel.setText(sourceAddress);
        } else {
            setContentView(R.layout.activity_send_transfer);
            TextView addressLabel = findViewById(R.id.transfer_address_label);
            TextView amountLabel = findViewById(R.id.transfer_amount_label);
            addressLabel.setText(getIntent().getStringExtra("dest"));
            transferAmount = new Coins(new VarUInteger(new BigInteger(getIntent().getByteArrayExtra("amount"))));
            amountLabel.setText(getString(R.string.ton_amount, transferAmount.toString()));
            transactionSeqno = getIntent().getIntExtra("seqno", 0);
        }

        statusLabel = findViewById(R.id.status_label);

        ImageButton backArrowButton = findViewById(R.id.back_arrow);
        backArrowButton.setOnClickListener(this::switchToMain);

        sendButton = findViewById(R.id.send_transaction_button);
        sendButton.setOnClickListener(this::prevalidateTransaction);

        setupPopup(PopupType.ON_ERROR);
        setupPopup(PopupType.ON_SUCCESS);
        setupPopup(PopupType.ON_PREVALIDATION_FAIL);
    }
}