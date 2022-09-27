package com.tonairgapclient;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

public class OnErrorActivity extends AppCompatActivity {
    private void switchToMain(View view) {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_on_error);
        findViewById(R.id.popup_button).setOnClickListener(this::switchToMain);
        TextView errorLabel = findViewById(R.id.error_message_label);
        try {
            errorLabel.setText(getIntent().getStringExtra("errorMessage"));
        } catch (Exception e) {
        }

    }
}