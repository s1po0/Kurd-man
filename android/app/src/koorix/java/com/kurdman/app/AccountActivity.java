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

import org.json.JSONObject;

/**
 * Koorix account screen: email/Google sign-in, plan + daily-usage
 * status, Premium activation-code redemption. All account rules are
 * enforced server-side (Supabase); this screen only displays them.
 */
public class AccountActivity extends AppCompatActivity {

    private static final int RC_GOOGLE_SIGN_IN = 4601;

    private ProgressBar progress;
    private View loginCard;
    private View accountCard;
    private EditText emailInput;
    private EditText passwordInput;
    private TextView authMessage;
    private TextView accountEmail;
    private TextView planStatus;
    private TextView usageStatus;
    private TextView accountMessage;
    private EditText codeInput;
    private Button signInButton;
    private Button signUpButton;
    private Button googleButton;
    private Button redeemButton;
    private Button buyButton;
    private Button signOutButton;

    private GoogleSignInClient googleClient;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);

        progress = findViewById(R.id.accountProgress);
        loginCard = findViewById(R.id.loginCard);
        accountCard = findViewById(R.id.accountCard);
        emailInput = findViewById(R.id.emailInput);
        passwordInput = findViewById(R.id.passwordInput);
        authMessage = findViewById(R.id.authMessage);
        accountEmail = findViewById(R.id.accountEmail);
        planStatus = findViewById(R.id.planStatus);
        usageStatus = findViewById(R.id.usageStatus);
        accountMessage = findViewById(R.id.accountMessage);
        codeInput = findViewById(R.id.codeInput);
        signInButton = findViewById(R.id.signInButton);
        signUpButton = findViewById(R.id.signUpButton);
        googleButton = findViewById(R.id.googleButton);
        redeemButton = findViewById(R.id.redeemButton);
        buyButton = findViewById(R.id.buyButton);
        signOutButton = findViewById(R.id.signOutButton);

        signInButton.setOnClickListener(v -> doEmailAuth(false));
        signUpButton.setOnClickListener(v -> doEmailAuth(true));
        googleButton.setOnClickListener(v -> doGoogleSignIn());
        redeemButton.setOnClickListener(v -> doRedeem());
        signOutButton.setOnClickListener(v -> doSignOut());
        findViewById(R.id.backToAppButton)
                .setOnClickListener(v -> finish());

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

        renderState();
    }

    // ---------- state rendering ----------

    private void renderState() {
        boolean signedIn = SupabaseAuth.isSignedIn(this);
        loginCard.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        accountCard.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        if (signedIn) {
            accountEmail.setText(SupabaseAuth.email(this));
            planStatus.setText(SupabaseAuth.cachedPremium(this)
                    ? "💎 Premium چالاکە" : "پلانی خۆڕایی");
            usageStatus.setText("پشکنینی دۆخی ئەمڕۆ…");
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
                    showAuthMessage("تکایە دووبارە بچۆرە ژوورەوە.");
                } else {
                    usageStatus.setText("⚠ سێرڤەر بەردەست نییە؛ "
                            + "دۆخی دوایین پشکنین پیشان دەدرێت.");
                }
                return;
            }
            boolean premium = result.optBoolean("premium", false);
            if (premium) {
                String until = result.isNull("premium_until") ? ""
                        : result.optString("premium_until", "");
                planStatus.setText("💎 Premium چالاکە"
                        + (until.length() >= 10
                        ? "\nتا بەرواری " + until.substring(0, 10) : ""));
                usageStatus.setText("وەرگێڕانی بێسنوور — سوپاس بۆ پشتگیریت! 🎉");
            } else {
                planStatus.setText("پلانی خۆڕایی");
                usageStatus.setText("ئەمڕۆ: "
                        + result.optInt("jobs_today", 0) + "/"
                        + result.optInt("free_job_limit",
                        BuildConfig.FREE_DAILY_JOBS)
                        + " وەرگێڕان • "
                        + result.optInt("pages_today", 0) + "/"
                        + result.optInt("free_page_limit",
                        BuildConfig.FREE_DAILY_PAGES)
                        + " لاپەڕە بەکار هاتووە\n"
                        + result.optInt("remaining_jobs", 0)
                        + " وەرگێڕانی خۆڕایی ماوە بۆ ئەمڕۆ.");
            }
        });
    }

    // ---------- actions ----------

    private void doEmailAuth(boolean signUp) {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (!email.contains("@") || email.length() < 5) {
            showAuthMessage("تکایە ئیمەیڵێکی دروست بنووسە.");
            return;
        }
        if (password.length() < 6) {
            showAuthMessage("وشەی نهێنی دەبێت لانی کەم ٦ پیت بێت.");
            return;
        }
        setBusy(true);
        SupabaseAuth.Callback cb = (result, error) -> {
            setBusy(false);
            if (result != null) {
                if (SupabaseAuth.isSignedIn(this)) {
                    Toast.makeText(this, "بەخێر بێیت بۆ Koorix 🎉",
                            Toast.LENGTH_SHORT).show();
                    renderState();
                } else if (signUp) {
                    showAuthMessage("هەژمارەکە ئامادەیە — "
                            + "ئێستا «چوونەژوورەوە» دابگرە.");
                } else {
                    showAuthMessage(friendly(error));
                }
            } else {
                showAuthMessage(friendly(error));
            }
        };
        if (signUp) {
            SupabaseAuth.signUp(this, email, password, cb);
        } else {
            SupabaseAuth.signIn(this, email, password, cb);
        }
    }

    private void doGoogleSignIn() {
        String webClientId;
        try {
            webClientId = getString(R.string.default_web_client_id).trim();
        } catch (Exception e) {
            webClientId = "";
        }
        if (webClientId.isEmpty() || webClientId.startsWith("REPLACE")) {
            showAuthMessage("چوونەژوورەوە بە Google بەم زوانە دێت — "
                    + "تکایە ئێستا بە ئیمەیڵ و وشەی نهێنی بچۆرە ژوورەوە.");
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
                showAuthMessage("Google هیچ ناسنامەیەکی نەگەڕاندەوە.");
                return;
            }
            setBusy(true);
            SupabaseAuth.signInWithGoogleIdToken(this, idToken,
                    (result, error) -> {
                        setBusy(false);
                        if (result != null
                                && SupabaseAuth.isSignedIn(this)) {
                            Toast.makeText(this, "بەخێر بێیت بۆ Koorix 🎉",
                                    Toast.LENGTH_SHORT).show();
                            renderState();
                        } else {
                            showAuthMessage(friendly(error));
                        }
                    });
        } catch (ApiException e) {
            showAuthMessage("چوونەژوورەوەی Google هەڵکنرا/سەرنەکەوت ("
                    + e.getStatusCode() + ").");
        }
    }

    private void doRedeem() {
        String code = codeInput.getText().toString().trim();
        if (code.length() < 6) {
            showAccountMessage("تکایە کۆدی چالاککردن بنووسە.", false);
            return;
        }
        setBusy(true);
        SupabaseAuth.redeemCode(this, code, (result, error) -> {
            setBusy(false);
            if (result != null && result.optBoolean("ok", false)) {
                String until = result.optString("premium_until", "");
                showAccountMessage("🎉 Premium چالاک کرا! "
                        + result.optInt("days_added", 0) + " ڕۆژ زیاد کرا"
                        + (until.length() >= 10
                        ? " — تا " + until.substring(0, 10) : "") + ".", true);
                codeInput.setText("");
                refreshStatus();
            } else {
                showAccountMessage("ئەم کۆدە دروست نییە یان پێشتر بەکار هاتووە.",
                        false);
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
        signInButton.setEnabled(!busy);
        signUpButton.setEnabled(!busy);
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
            return "هەڵەیەک ڕووی دا؛ تکایە دووبارە هەوڵ بدەرەوە.";
        }
        String e = error.toLowerCase(java.util.Locale.US);
        if (e.contains("invalid login credentials")
                || e.contains("invalid_credentials")) {
            return "ئیمەیڵ یان وشەی نهێنی هەڵەیە.";
        }
        if (e.contains("already registered") || e.contains("already exists")
                || e.contains("user_already_exists")) {
            return "ئەم ئیمەیڵە پێشتر هەژماری هەیە — «چوونەژوورەوە» دابگرە.";
        }
        if (e.contains("weak_password") || e.contains("password")) {
            return "وشەی نهێنی زۆر کورتە/لاوازە — لانی کەم ٦ پیت.";
        }
        if (e.startsWith("network")) {
            return "پەیوەندی ئینتەرنێت نییە؛ تکایە هەوڵ بدەرەوە.";
        }
        if (e.contains("provider") && e.contains("not enabled")) {
            return "چوونەژوورەوە بە Google هێشتا چالاک نەکراوە لەسەر سێرڤەر.";
        }
        return error;
    }
}
