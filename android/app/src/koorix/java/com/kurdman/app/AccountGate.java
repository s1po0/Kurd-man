package com.kurdman.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Koorix public edition gate: translation jobs require a signed-in
 * account and either Premium or remaining free daily allowance. The
 * authoritative check is the Supabase RPC koorix_consume_job; when the
 * network is unreachable we fall back to the last known Premium state
 * or a local daily counter so the app still works offline.
 */
public final class AccountGate {

    private AccountGate() {
    }

    public static void enforceOnLaunch(Activity activity) {
        if (!SupabaseAuth.isSignedIn(activity)) {
            openAccount(activity);
        } else {
            // Warm the cached Premium state for offline fallbacks.
            SupabaseAuth.fetchStatus(activity, (result, error) -> {
            });
        }
    }

    /** Magic-link sign-in deep link (com.koorix.app://auth/callback#...). */
    public static boolean handleAuthDeepLink(Activity activity, Intent intent) {
        if (intent == null || intent.getData() == null) {
            return false;
        }
        boolean ok = SupabaseAuth.handleAuthDeepLink(activity, intent.getData());
        if (ok) {
            Toast.makeText(activity, R.string.kx_welcome_toast,
                    Toast.LENGTH_SHORT).show();
            SupabaseAuth.fetchStatus(activity, (result, error) -> {
            });
        }
        return ok;
    }

    public static void guardJobStart(Activity activity, int pages,
                                     Runnable onAllowed) {
        if (!SupabaseAuth.isSignedIn(activity)) {
            Toast.makeText(activity, R.string.kx_login_required,
                    Toast.LENGTH_LONG).show();
            openAccount(activity);
            return;
        }
        ProgressDialog dialog = new ProgressDialog(activity);
        dialog.setMessage(activity.getString(R.string.kx_checking_account));
        dialog.setCancelable(false);
        dialog.show();
        SupabaseAuth.consumeJob(activity, pages, (result, error) -> {
            dialog.dismiss();
            if (activity.isFinishing()) {
                return;
            }
            if (result != null) {
                if (result.optBoolean("allowed", false)) {
                    if (!result.optBoolean("premium", false)) {
                        Toast.makeText(activity,
                                activity.getString(R.string.kx_free_remaining,
                                        result.optInt("remaining_jobs", 0),
                                        result.optInt("free_job_limit",
                                                BuildConfig.FREE_DAILY_JOBS)),
                                Toast.LENGTH_SHORT).show();
                    }
                    if (onAllowed != null) {
                        onAllowed.run();
                    }
                } else {
                    showLimitDialog(activity, result);
                }
                return;
            }
            // Server unreachable (or session problem): offline fallback.
            if ("session_expired".equals(error)
                    || !SupabaseAuth.isSignedIn(activity)) {
                Toast.makeText(activity, R.string.kx_session_expired,
                        Toast.LENGTH_LONG).show();
                openAccount(activity);
                return;
            }
            if (SupabaseAuth.cachedPremium(activity)
                    || SupabaseAuth.localConsume(activity, pages)) {
                Toast.makeText(activity, R.string.kx_offline_mode,
                        Toast.LENGTH_SHORT).show();
                if (onAllowed != null) {
                    onAllowed.run();
                }
            } else {
                showLimitDialog(activity, null);
            }
        });
    }

    private static void showLimitDialog(Activity activity, JSONObject status) {
        int limit = status != null
                ? status.optInt("free_job_limit", BuildConfig.FREE_DAILY_JOBS)
                : BuildConfig.FREE_DAILY_JOBS;
        int pageLimit = status != null
                ? status.optInt("free_page_limit", BuildConfig.FREE_DAILY_PAGES)
                : BuildConfig.FREE_DAILY_PAGES;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.kx_limit_title)
                .setMessage(activity.getString(R.string.kx_limit_body,
                        limit, pageLimit))
                .setPositiveButton(R.string.kx_premium_btn,
                        (d, w) -> openAccount(activity))
                .setNegativeButton(R.string.kx_later, null)
                .show();
    }

    public static void openAccount(Activity activity) {
        activity.startActivity(new Intent(activity, AccountActivity.class));
    }
}
