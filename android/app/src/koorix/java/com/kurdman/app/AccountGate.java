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

    public static void guardJobStart(Activity activity, int pages,
                                     Runnable onAllowed) {
        if (!SupabaseAuth.isSignedIn(activity)) {
            Toast.makeText(activity,
                    "بۆ وەرگێڕان سەرەتا بچۆرە ژوورەوە بە هەژمارەکەت.",
                    Toast.LENGTH_LONG).show();
            openAccount(activity);
            return;
        }
        ProgressDialog dialog = new ProgressDialog(activity);
        dialog.setMessage("پشکنینی هەژمار…");
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
                        int remaining = result.optInt("remaining_jobs", 0);
                        Toast.makeText(activity,
                                "وەرگێڕانی خۆڕایی ئەمڕۆ: " + remaining
                                        + " جار ماوە لە "
                                        + result.optInt("free_job_limit",
                                        BuildConfig.FREE_DAILY_JOBS),
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
                Toast.makeText(activity,
                        "هەژمارەکەت پێویستی بە چوونەژوورەوەیەکی نوێیە.",
                        Toast.LENGTH_LONG).show();
                openAccount(activity);
                return;
            }
            if (SupabaseAuth.cachedPremium(activity)
                    || SupabaseAuth.localConsume(activity, pages)) {
                Toast.makeText(activity,
                        "سێرڤەر بەردەست نییە — بە شێوازی ئۆفلاین بەردەوام دەبێت.",
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
                .setTitle("سنووری خۆڕایی ئەمڕۆ تەواو بوو 💎")
                .setMessage("ئەمڕۆ " + limit + " وەرگێڕانی خۆڕایی و تا "
                        + pageLimit + " لاپەڕەت بەکار هێنا.\n\n"
                        + "بە Premium دەتوانیت بەبێ سنوور وەرگێڕان بکەیت. "
                        + "کۆدی چالاککردن لە بەشی «هەژمارەکەم» دابنێ.")
                .setPositiveButton("💎 Premium", (d, w) -> openAccount(activity))
                .setNegativeButton("دواتر", null)
                .show();
    }

    public static void openAccount(Activity activity) {
        activity.startActivity(new Intent(activity, AccountActivity.class));
    }
}
