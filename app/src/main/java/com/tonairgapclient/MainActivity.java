package com.tonairgapclient;

import androidx.appcompat.app.AppCompatActivity;


import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

public class MainActivity extends AppCompatActivity {
    private void switchToQR(View view) {
        Intent intent = new Intent(this, QRActivity.class);
        startActivity(intent);
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button switchToQRButton = findViewById(R.id.scan_qr_button);
        switchToQRButton.setOnClickListener(this::switchToQR);
    }
}