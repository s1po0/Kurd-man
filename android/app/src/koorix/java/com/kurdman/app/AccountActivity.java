package com.kurdman.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;

/**
 * Koorix account screen: passwordless sign-in (Google account on the
 * phone, or a numeric code / magic link emailed to the user's Gmail),
 * plan + daily-usage status, and Premium activation-code redemption.
 * All account rules are enforced server-side (Supabase); this screen
 * only displays them.
 */
public class AccountActivity extends AppCompatActivity {

    private static final int RC_GOOGLE_SIGN_IN = 4601;

    private ProgressBar progress;
    private View loginCard;
    private View accountCard;
    private View otpBox;
    private View phoneOtpBox;
    private EditText phoneInput;
    private EditText phoneCodeInput;
    private Button sendSmsButton;
    private Button verifyPhoneButton;
    private EditText emailInput;
    private EditText codeOtpInput;
    private TextView authMessage;
    private TextView accountEmail;
    private TextView planStatus;
    private TextView usageStatus;
    private TextView accountMessage;
    private EditText codeInput;
    private Button sendCodeButton;
    private Button verifyCodeButton;
    private Button googleButton;
    private Button redeemButton;
    private Button buyButton;
    private Button signOutButton;

    private GoogleSignInClient googleClient;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);

        // English is LTR; Sorani/Arabic are RTL.
        View root = findViewById(R.id.accountRoot);
        String lang = getResources().getConfiguration()
                .getLocales().get(0).getLanguage();
        root.setLayoutDirection("en".equals(lang)
                ? View.LAYOUT_DIRECTION_LTR : View.LAYOUT_DIRECTION_RTL);

        progress = findViewById(R.id.accountProgress);
        loginCard = findViewById(R.id.loginCard);
        accountCard = findViewById(R.id.accountCard);
        otpBox = findViewById(R.id.otpBox);
        emailInput = findViewById(R.id.emailInput);
        codeOtpInput = findViewById(R.id.codeOtpInput);
        authMessage = findViewById(R.id.authMessage);
        accountEmail = findViewById(R.id.accountEmail);
        planStatus = findViewById(R.id.planStatus);
        usageStatus = findViewById(R.id.usageStatus);
        accountMessage = findViewById(R.id.accountMessage);
        codeInput = findViewById(R.id.codeInput);
        phoneOtpBox = findViewById(R.id.phoneOtpBox);
        phoneInput = findViewById(R.id.phoneInput);
        phoneCodeInput = findViewById(R.id.phoneCodeInput);
        sendSmsButton = findViewById(R.id.sendSmsButton);
        verifyPhoneButton = findViewById(R.id.verifyPhoneButton);
        sendCodeButton = findViewById(R.id.sendCodeButton);
        verifyCodeButton = findViewById(R.id.verifyCodeButton);
        googleButton = findViewById(R.id.googleButton);
        redeemButton = findViewById(R.id.redeemButton);
        buyButton = findViewById(R.id.buyButton);
        signOutButton = findViewById(R.id.signOutButton);

        sendCodeButton.setOnClickListener(v -> doSendCode());
        sendSmsButton.setOnClickListener(v -> doSendPhoneCode());
        verifyPhoneButton.setOnClickListener(v -> doVerifyPhoneCode());
        String savedPhone = SupabaseAuth.pendingPhone(this);
        if (!savedPhone.isEmpty()) {
            phoneInput.setText(savedPhone);
        }
        verifyCodeButton.setOnClickListener(v -> doVerifyCode());
        googleButton.setOnClickListener(v -> doGoogleSignIn());
        redeemButton.setOnClickListener(v -> doRedeem());
        signOutButton.setOnClickListener(v -> doSignOut());
        findViewById(R.id.backToAppButton)
                .setOnClickListener(v -> finish());

        String saved = SupabaseAuth.pendingEmail(this);
        if (!saved.isEmpty() && !SupabaseAuth.isSignedIn(this)) {
            emailInput.setText(saved);
            otpBox.setVisibility(View.VISIBLE);
        }

        String buyUrl = getString(R.string.koorix_buy_url).trim();
        if (buyUrl.isEmpty()) {
            buyButton.setVisibility(View.GONE);
        } else {
            buyButton.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse(buyUrl)));
                } catch (Exception e) {
                    Toast.makeText(this, buyUrl, Toast.LENGTH_LONG).show();
                }
            });
        }

        if (handleDeepLink(getIntent())) {
            goToMain();
            return;
        }
        renderState();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (handleDeepLink(intent)) {
            goToMain();
        }
    }

    private boolean handleDeepLink(@Nullable Intent intent) {
        if (intent == null || intent.getData() == null) {
            return false;
        }
        boolean ok = SupabaseAuth.handleAuthDeepLink(this, intent.getData());
        if (ok) {
            Toast.makeText(this, R.string.kx_welcome_toast,
                    Toast.LENGTH_SHORT).show();
            SupabaseAuth.fetchStatus(this, (result, error) -> {
            });
        }
        return ok;
    }

    private void goToMain() {
        Intent main = new Intent(this, MainActivity.class);
        main.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(main);
        finish();
    }

    // ---------- state rendering ----------

    private void renderState() {
        boolean signedIn = SupabaseAuth.isSignedIn(this);
        loginCard.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        accountCard.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        if (signedIn) {
            accountEmail.setText(SupabaseAuth.email(this));
            planStatus.setText(SupabaseAuth.cachedPremium(this)
                    ? getString(R.string.kx_premium_active)
                    : getString(R.string.kx_free_plan));
            usageStatus.setText(R.string.kx_checking_status);
            refreshStatus();
        }
    }

    private void refreshStatus() {
        setBusy(true);
        SupabaseAuth.fetchStatus(this, (result, error) -> {
            setBusy(false);
            if (result == null) {
                if ("session_expired".equals(error)
                        || !SupabaseAuth.isSignedIn(this)) {
                    renderState();
                    showAuthMessage(getString(R.string.kx_session_expired));
                } else {
                    usageStatus.setText(R.string.kx_status_unavailable);
                }
                return;
            }
            if (result.optBoolean("premium", false)) {
                String until = result.isNull("premium_until") ? ""
                        : result.optString("premium_until", "");
                planStatus.setText(until.length() >= 10
                        ? getString(R.string.kx_premium_until,
                        until.substring(0, 10))
                        : getString(R.string.kx_premium_active));
                usageStatus.setText(R.string.kx_unlimited_thanks);
            } else {
                planStatus.setText(R.string.kx_free_plan);
                usageStatus.setText(getString(R.string.kx_usage_today,
                        result.optInt("jobs_today", 0),
                        result.optInt("free_job_limit",
                                BuildConfig.FREE_DAILY_JOBS),
                        result.optInt("pages_today", 0),
                        result.optInt("free_page_limit",
                                BuildConfig.FREE_DAILY_PAGES),
                        result.optInt("remaining_jobs", 0)));
            }
        });
    }

    // ---------- actions ----------

    private void doSendCode() {
        String email = emailInput.getText().toString().trim();
        if (!email.contains("@") || email.length() < 5) {
            showAuthMessage(getString(R.string.kx_err_email));
            return;
        }
        setBusy(true);
        SupabaseAuth.sendEmailOtp(this, email, (result, error) -> {
            setBusy(false);
            if (result != null) {
                otpBox.setVisibility(View.VISIBLE);
                codeOtpInput.requestFocus();
                showAuthMessage(getString(R.string.kx_code_sent, email));
            } else {
                showAuthMessage(friendly(error));
            }
        });
    }

    private void doVerifyCode() {
        String email = emailInput.getText().toString().trim();
        String token = codeOtpInput.getText().toString().trim();
        if (!email.contains("@")) {
            showAuthMessage(getString(R.string.kx_err_email));
            return;
        }
        if (token.length() < 6) {
            showAuthMessage(getString(R.string.kx_err_code_short));
            return;
        }
        setBusy(true);
        SupabaseAuth.verifyEmailOtp(this, email, token, (result, error) -> {
            setBusy(false);
            if (result != null && SupabaseAuth.isSignedIn(this)) {
                Toast.makeText(this, R.string.kx_welcome_toast,
                        Toast.LENGTH_SHORT).show();
                renderState();
            } else {
                showAuthMessage(error != null && error.startsWith("network")
                        ? getString(R.string.kx_err_network)
                        : getString(R.string.kx_err_otp_invalid));
            }
        });
    }

    /** Normalizes to E.164; numbers starting with 0 get +964 (Iraq). */
    private static String normalizePhone(String raw) {
        if (raw == null) {
            return "";
        }
        String p = raw.trim().replace(" ", "").replace("-", "")
                .replace("(", "").replace(")", "");
        if (p.startsWith("00")) {
            p = "+" + p.substring(2);
        }
        if (p.startsWith("+")) {
            return p;
        }
        if (p.startsWith("964") && p.length() >= 13) {
            return "+" + p;
        }
        if (p.startsWith("0")) {
            return "+964" + p.substring(1);
        }
        return "+" + p;
    }

    private void doSendPhoneCode() {
        String phone = normalizePhone(phoneInput.getText().toString());
        String digits = phone.replace("+", "");
        if (digits.length() < 10 || !digits.matches("[0-9]+")) {
            showAuthMessage(getString(R.string.kx_err_phone));
            return;
        }
        setBusy(true);
        SupabaseAuth.sendPhoneOtp(this, phone, (result, error) -> {
            setBusy(false);
            if (result != null) {
                phoneOtpBox.setVisibility(View.VISIBLE);
                phoneCodeInput.requestFocus();
                showAuthMessage(getString(
                        R.string.kx_phone_code_sent, phone));
            } else {
                showAuthMessage(friendly(error));
            }
        });
    }

    private void doVerifyPhoneCode() {
        String phone = normalizePhone(phoneInput.getText().toString());
        String token = phoneCodeInput.getText().toString().trim();
        if (phone.replace("+", "").length() < 10) {
            showAuthMessage(getString(R.string.kx_err_phone));
            return;
        }
        if (token.length() < 6) {
            showAuthMessage(getString(R.string.kx_err_code_short));
            return;
        }
        setBusy(true);
        SupabaseAuth.verifyPhoneOtp(this, phone, token, (result, error) -> {
            setBusy(false);
            if (result != null && SupabaseAuth.isSignedIn(this)) {
                Toast.makeText(this, R.string.kx_welcome_toast,
                        Toast.LENGTH_SHORT).show();
                renderState();
            } else {
                showAuthMessage(error != null && error.startsWith("network")
                        ? getString(R.string.kx_err_network)
                        : getString(R.string.kx_err_otp_invalid));
            }
        });
    }

    private void doGoogleSignIn() {
        String webClientId;
        try {
            webClientId = getString(R.string.default_web_client_id).trim();
        } catch (Exception e) {
            webClientId = "";
        }
        if (webClientId.isEmpty() || webClientId.startsWith("REPLACE")) {
            showAuthMessage(getString(R.string.kx_google_soon));
            return;
        }
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(
                GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId)
                .requestEmail()
                .build();
        googleClient = GoogleSignIn.getClient(this, gso);
        startActivityForResult(googleClient.getSignInIntent(),
                RC_GOOGLE_SIGN_IN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_GOOGLE_SIGN_IN) {
            return;
        }
        Task<GoogleSignInAccount> task =
                GoogleSignIn.getSignedInAccountFromIntent(data);
        try {
            GoogleSignInAccount account = task.getResult(ApiException.class);
            String idToken = account != null ? account.getIdToken() : null;
            if (idToken == null || idToken.isEmpty()) {
                showAuthMessage(getString(R.string.kx_err_google_no_token));
                return;
            }
            setBusy(true);
            SupabaseAuth.signInWithGoogleIdToken(this, idToken,
                    (result, error) -> {
                        setBusy(false);
                        if (result != null
                                && SupabaseAuth.isSignedIn(this)) {
                            Toast.makeText(this, R.string.kx_welcome_toast,
                                    Toast.LENGTH_SHORT).show();
                            renderState();
                        } else {
                            showAuthMessage(friendly(error));
                        }
                    });
        } catch (ApiException e) {
            showAuthMessage(getString(R.string.kx_err_google_failed,
                    e.getStatusCode()));
        }
    }

    private void doRedeem() {
        String code = codeInput.getText().toString().trim();
        if (code.length() < 6) {
            showAccountMessage(getString(R.string.kx_err_enter_code), false);
            return;
        }
        setBusy(true);
        SupabaseAuth.redeemCode(this, code, (result, error) -> {
            setBusy(false);
            if (result != null && result.optBoolean("ok", false)) {
                String until = result.optString("premium_until", "");
                String suffix = until.length() >= 10
                        ? getString(R.string.kx_redeem_until,
                        until.substring(0, 10)) : "";
                showAccountMessage(getString(R.string.kx_redeem_ok,
                        result.optInt("days_added", 0), suffix), true);
                codeInput.setText("");
                refreshStatus();
            } else {
                showAccountMessage(
                        getString(R.string.kx_err_invalid_code), false);
            }
        });
    }

    private void doSignOut() {
        setBusy(true);
        if (googleClient != null) {
            googleClient.signOut();
        }
        SupabaseAuth.signOut(this, (result, error) -> {
            setBusy(false);
            renderState();
        });
    }

    // ---------- helpers ----------

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        sendCodeButton.setEnabled(!busy);
        sendSmsButton.setEnabled(!busy);
        verifyPhoneButton.setEnabled(!busy);
        verifyCodeButton.setEnabled(!busy);
        googleButton.setEnabled(!busy);
        redeemButton.setEnabled(!busy);
        signOutButton.setEnabled(!busy);
        buyButton.setEnabled(!busy);
    }

    private void showAuthMessage(String msg) {
        authMessage.setText(msg);
        authMessage.setVisibility(View.VISIBLE);
    }

    private void showAccountMessage(String msg, boolean success) {
        accountMessage.setText(msg);
        accountMessage.setTextColor(getColor(success
                ? R.color.success : R.color.warning));
        accountMessage.setVisibility(View.VISIBLE);
    }

    private String friendly(String error) {
        if (error == null) {
            return getString(R.string.kx_err_generic);
        }
        String e = error.toLowerCase(java.util.Locale.US);
        if (e.startsWith("network")) {
            return getString(R.string.kx_err_network);
        }
        if (e.contains("sms") || e.contains("phone")) {
            return getString(R.string.kx_err_sms_off);
        }
        if (e.contains("provider") && e.contains("not enabled")) {
            return getString(R.string.kx_err_provider_disabled);
        }
        if (e.contains("rate") || e.contains("over_email_send")) {
            return getString(R.string.kx_err_generic);
        }
        return error;
    }
}
