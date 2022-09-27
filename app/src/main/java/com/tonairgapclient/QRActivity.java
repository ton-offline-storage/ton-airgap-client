package com.tonairgapclient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.budiyev.android.codescanner.CodeScanner;
import com.budiyev.android.codescanner.CodeScannerView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;

import org.jetbrains.annotations.TestOnly;
import org.ton.block.AccountInfo;
import org.ton.block.Coins;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import kotlin.Pair;
import kotlin.Triple;
import kotlin.UInt;
import kotlin.ULong;


public class QRActivity extends AppCompatActivity {
    private static final int CAMERA_REQUEST_CODE = 179;
    private CodeScanner qrScanner;
    private void switchToError(String errorMessage) {
        Intent intent = new Intent(this, OnErrorActivity.class);
        intent.putExtra("errorMessage", errorMessage);
        startActivity(intent);
    }
    private void switchToDeployment(String source, byte[] bytes) {
        Intent intent = new Intent(this, SendTransactionActivity.class);
        intent.putExtra("isDeployment", true);
        intent.putExtra("source", source);
        intent.putExtra("transactionBytes", bytes);
        startActivity(intent);
    }
    private void switchToTransfer(String source, String dest, Coins amount, int seqno, byte[] bytes) {
        Intent intent = new Intent(this, SendTransactionActivity.class);
        intent.putExtra("isDeployment", false);
        intent.putExtra("source", source);
        intent.putExtra("dest", dest);
        intent.putExtra("amount", amount.getAmount().getValue().toByteArray());
        intent.putExtra("seqno", seqno);
        intent.putExtra("transactionBytes", bytes);
        startActivity(intent);
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.qr_activity);
        if(!TonlibController.INSTANCE.initClient()) {
            switchToError(getString(R.string.liteclient_error));
            return;
        }
        /*AccountInfo info = TonlibController.INSTANCE.getAccountInfo("EQD7F4ijAuXG9u4Lp6OnvQ7F8m9rYs6917Ayp80Oxmz8312N");
        if(info != null) {
            Log.d("Debug", "Not NULL!");
            Log.d("Debug", String.valueOf(info.getStorage()));
        } else {
            Log.d("Debug", "NULL!");
        }*/
        /*Log.d("Debug", "State: " + String.valueOf(status.getState()));
        Log.d("Debug", "Balance: " + status.getBalance().toString());
        Log.d("Debug", "Seqno: " + String.valueOf(status.getSeqno()));*/
        setupPermissionsAndScanner();
    }
    private void setupCodeScanner() {
        if(!Objects.isNull(qrScanner)) {
            return;
        }
        CodeScannerView scannerView = findViewById(R.id.qr_scanner);
        qrScanner = new CodeScanner(this, scannerView);
        qrScanner.setFormats(Collections.singletonList(BarcodeFormat.QR_CODE));
        qrScanner.setDecodeCallback(this::onDecoded);
        qrScanner.setErrorCallback(this::onDecodeError);
    }
    public void onDecoded(@NonNull final Result scanResult) {
        Log.d("Debug", "onDecodedQr");
        runOnUiThread(() -> {
            byte[] bytes = getRawBytes(scanResult);
            Log.d("Debug", "Decoded: " + Arrays.toString(bytes));
            if(bytes == null) {
                switchToError(getString(R.string.transaction_decoding_error));
                return;
            }
            TransferData transferData = TonlibController.INSTANCE.extractDataFromTransferBoc(bytes);
            if(transferData != null) {
                Log.d("Debug", "Address received!: " + transferData.getDest());
                Log.d("Debug", "Coins received!: " + transferData.getAmount());
                Log.d("Debug", "Source address received: " + transferData.getSource());
                Log.d("Debug", "Seqno received: " + transferData.getSeqno());
                switchToTransfer(transferData.getSource(), transferData.getDest(),
                        transferData.getAmount(), transferData.getSeqno(), bytes);
                return;
            }
            String address = TonlibController.INSTANCE.extractAddressFromInitBoc(bytes);
            if(address != null) {
                Log.d("Debug", "Deployment address... : " + address);
                switchToDeployment(address, bytes);
                return;
            }
            switchToError(getString(R.string.transaction_decoding_error));
        });
        Log.d("Debug", "Decoded1");
    }
    public void onDecodeError(Throwable e) {
        runOnUiThread(() -> {
            Log.d("Debug", "Scanning or decoding error: " + e.getMessage());
            switchToError(getString(R.string.qr_scanner_error) + ": " + e.getMessage());
        });
    }

    private void setupPermissionsAndScanner() {
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST_CODE);
        } else {
            setupCodeScanner();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == CAMERA_REQUEST_CODE) {
            if(grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(QRActivity.this, getString(R.string.need_permission_text), Toast.LENGTH_LONG).show();
            } else {
                setupCodeScanner();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(!Objects.isNull(qrScanner)) {
            qrScanner.startPreview();
        }
    }
    @Override
    protected void onPause() {
        if(!Objects.isNull(qrScanner)) {
            qrScanner.releaseResources();
        }
        super.onPause();
    }

    private byte[] getRawBytes(@NonNull final Result result) {
        List<byte[]> segments;
        try {
            segments = (List<byte[]>)result.getResultMetadata().get(ResultMetadataType.BYTE_SEGMENTS);
        } catch (Exception e) {
            return null;
        }
        if(segments == null) {
            return null;
        }
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        for(byte[] segment : segments) {
            try {
                byteStream.write(segment);
            } catch (Exception e) {
                return null;
            }
        }
        byte[] bytes = byteStream.toByteArray();
        return bytes.length >= result.getText().length() ? bytes : null;
    }
}