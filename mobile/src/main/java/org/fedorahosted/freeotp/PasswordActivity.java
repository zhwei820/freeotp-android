package org.fedorahosted.freeotp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.Html;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;


import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatEditText;

import org.fedorahosted.freeotp.main.Activity;


public class PasswordActivity extends AppCompatActivity {
    private static final String LOGTAG = "PasswordActivity";

    private AppCompatEditText mPassword;
    private AppCompatEditText mConfirm;
    private MaterialButton mDone;
    private TextInputLayout mPasswordLayout;
    private TextInputLayout mConfirmLayout;
    private TokenPersistence mTokenBackup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            mTokenBackup = new TokenPersistence(getApplicationContext());
            if (mTokenBackup.isProvisioned()) {
                Intent myIntent = new Intent(this, Activity.class);
                startActivity(myIntent);
                finish();
            }
        } catch (Exception e) {
            Log.e(LOGTAG, "Exception", e);
        }
        // Check if we need to display OnBoarding
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (!sharedPreferences.getBoolean(OnBoardingActivity.COMPLETED_ONBOARDING, false)) {
            startActivity(new Intent(this, OnBoardingActivity.class));
        }

        setContentView(R.layout.activity_password);

        mPassword = findViewById(R.id.password);
        mConfirm = findViewById(R.id.confirm);
        mDone = findViewById(R.id.done);
        mPasswordLayout = findViewById(R.id.password_layout);
        mConfirmLayout = findViewById(R.id.confirm_layout);

        mDone.setEnabled(false);
        mDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String password = mPassword.getText().toString();

                try {
                    mTokenBackup.provision(password);
                } catch (Exception e){
                    Log.e(LOGTAG, "Exception", e);
                }

                Intent myIntent = new Intent(PasswordActivity.this, Activity.class);
                startActivity(myIntent);
                finish();
            }
        });

        mPassword.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                mConfirmLayout.setError(null);
                mPasswordLayout.setError(null);
                mDone.setEnabled(false);
                mConfirm.setText("");

                String password = editable.toString();
                if (password.isEmpty()) {
                    mConfirmLayout.setVisibility(View.INVISIBLE);
                    return;
                }

                // Require a mix of upper case, lower case, digit and special character.
                boolean valid = meetsComplexity(password);
                mPasswordLayout.setError(valid ? null : getString(R.string.password_complexity));
                mConfirmLayout.setVisibility(valid ? View.VISIBLE : View.INVISIBLE);
            }
        });

        mConfirm.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                String error = getResources().getString(R.string.password_match);
                String password = mPassword.getText().toString();
                String confirm = editable.toString();
                boolean match = confirm.equals(password);
                boolean zero = confirm.length() == 0;

                mConfirmLayout.setError(zero || match ? null : error);
                mDone.setEnabled(!zero && match && meetsComplexity(password));
            }
        });
    }

    // A password must contain at least one upper case letter, one lower case letter,
    // one digit and one special (non-alphanumeric) character.
    private static boolean meetsComplexity(String password) {
        boolean upper = false, lower = false, digit = false, special = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isUpperCase(c)) upper = true;
            else if (Character.isLowerCase(c)) lower = true;
            else if (Character.isDigit(c)) digit = true;
            else special = true;
        }
        return upper && lower && digit && special;
    }
}
