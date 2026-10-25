package com.kurdman.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Koorix account client: talks to Supabase Auth + PostgREST RPC over
 * plain HTTPS (no SDK). The session lives in SharedPreferences; only
 * the publishable key is embedded — all data access is controlled by
 * Row Level Security and SECURITY DEFINER functions on the server
 * (koorix_my_status / koorix_consume_job / koorix_redeem_code), so a
 * modified client cannot grant itself Premium.
 */
final class SupabaseAuth {

    interface Callback {
        /** @param result parsed JSON body on success, null on failure */
        void onResult(JSONObject result, String error);
    }

    private static final String PREFS = "koorix_account";
    private static final String K_ACCESS = "access_token";
    private static final String K_REFRESH = "refresh_token";
    private static final String K_EXPIRES = "expires_at_ms";
    private static final String K_EMAIL = "email";
    private static final String K_PENDING_EMAIL = "pending_email";
    private static final String K_PREMIUM_UNTIL = "premium_until_cache";
    private static final String K_LOCAL_DAY = "local_day";
    private static final String K_LOCAL_JOBS = "local_jobs";
    private static final String K_LOCAL_PAGES = "local_pages";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SupabaseAuth() {
    }

    // ---------- session state ----------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isSignedIn(Context ctx) {
        return !prefs(ctx).getString(K_ACCESS, "").isEmpty()
                && !prefs(ctx).getString(K_REFRESH, "").isEmpty();
    }

    static String email(Context ctx) {
        return prefs(ctx).getString(K_EMAIL, "");
    }

    static void clearSession(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    private static void saveSession(Context ctx, JSONObject body)
            throws JSONException {
        long expiresInSec = body.optLong("expires_in", 3600L);
        JSONObject user = body.optJSONObject("user");
        prefs(ctx).edit()
                .putString(K_ACCESS, body.getString("access_token"))
                .putString(K_REFRESH, body.getString("refresh_token"))
                .putLong(K_EXPIRES,
                        System.currentTimeMillis() + expiresInSec * 1000L)
                .putString(K_EMAIL, user != null
                        ? user.optString("email", email(ctx)) : email(ctx))
                .apply();
    }

    /** Premium known from the last successful server status check. */
    static boolean cachedPremium(Context ctx) {
        String until = prefs(ctx).getString(K_PREMIUM_UNTIL, "");
        if (until.isEmpty() || "null".equals(until)) {
            return false;
        }
        // premium_until is ISO-8601 UTC; lexicographic compare against
        // the current UTC timestamp in the same format is sufficient.
        SimpleDateFormat fmt = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String now = fmt.format(new Date());
        return until.compareTo(now) > 0;
    }

    static void cacheStatus(Context ctx, JSONObject status) {
        if (status == null) {
            return;
        }
        String until = status.isNull("premium_until") ? ""
                : status.optString("premium_until", "");
        prefs(ctx).edit().putString(K_PREMIUM_UNTIL, until).apply();
    }

    /**
     * Offline fallback counter: when the server cannot be reached, a
     * free user may still spend today's local allowance. The server
     * remains authoritative whenever it answers.
     */
    static boolean localConsume(Context ctx, int pages) {
        SharedPreferences p = prefs(ctx);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new Date());
        int jobs = today.equals(p.getString(K_LOCAL_DAY, ""))
                ? p.getInt(K_LOCAL_JOBS, 0) : 0;
        int usedPages = today.equals(p.getString(K_LOCAL_DAY, ""))
                ? p.getInt(K_LOCAL_PAGES, 0) : 0;
        if (jobs >= BuildConfig.FREE_DAILY_JOBS
                || usedPages + Math.max(1, pages) > BuildConfig.FREE_DAILY_PAGES) {
            return false;
        }
        p.edit().putString(K_LOCAL_DAY, today)
                .putInt(K_LOCAL_JOBS, jobs + 1)
                .putInt(K_LOCAL_PAGES, usedPages + Math.max(1, pages))
                .apply();
        return true;
    }

    // ---------- auth + rpc calls ----------

    /**
     * Sends a sign-in email with both a magic link (deep-links back
     * into the app) and — once the dashboard email template includes
     * {{ .Token }} — a numeric code the user can type.
     */
    static void sendEmailOtp(Context ctx, String email, Callback cb) {
        prefs(ctx).edit().putString(K_PENDING_EMAIL, email).apply();
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("create_user", true);
        } catch (JSONException ignored) {
        }
        post(ctx, "/auth/v1/otp?redirect_to="
                        + "com.koorix.app%3A%2F%2Fauth%2Fcallback",
                body, false, cb);
    }

    /** Verifies the numeric code from the sign-in email. */
    static void verifyEmailOtp(Context ctx, String email, String token,
                               Callback cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("type", "email");
            body.put("email", email);
            body.put("token", token.trim());
        } catch (JSONException ignored) {
        }
        post(ctx, "/auth/v1/verify", body, false, sessionSaver(ctx, cb));
    }

    /**
     * Handles the magic-link deep link
     * (com.koorix.app://auth/callback#access_token=...&amp;refresh_token=...).
     * Returns true when a session was stored.
     */
    static boolean handleAuthDeepLink(Context ctx, android.net.Uri uri) {
        if (uri == null || uri.getFragment() == null) {
            return false;
        }
        String access = null;
        String refresh = null;
        long expiresIn = 3600L;
        for (String part : uri.getFragment().split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = part.substring(0, eq);
            String v = android.net.Uri.decode(part.substring(eq + 1));
            if ("access_token".equals(k)) {
                access = v;
            } else if ("refresh_token".equals(k)) {
                refresh = v;
            } else if ("expires_in".equals(k)) {
                try {
                    expiresIn = Long.parseLong(v);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (access == null || refresh == null) {
            return false;
        }
        String pendingEmail = prefs(ctx).getString(K_PENDING_EMAIL, "");
        prefs(ctx).edit()
                .putString(K_ACCESS, access)
                .putString(K_REFRESH, refresh)
                .putLong(K_EXPIRES,
                        System.currentTimeMillis() + expiresIn * 1000L)
                .putString(K_EMAIL, pendingEmail)
                .apply();
        return true;
    }

    static String pendingEmail(Context ctx) {
        return prefs(ctx).getString(K_PENDING_EMAIL, "");
    }

    static void signInWithGoogleIdToken(Context ctx, String idToken,
                                        Callback cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("provider", "google");
            body.put("id_token", idToken);
        } catch (JSONException ignored) {
        }
        post(ctx, "/auth/v1/token?grant_type=id_token", body, false,
                sessionSaver(ctx, cb));
    }

    private static Callback sessionSaver(Context ctx, Callback cb) {
        return (result, error) -> {
            if (result != null) {
                try {
                    saveSession(ctx, result);
                } catch (JSONException e) {
                    cb.onResult(null, e.getMessage());
                    return;
                }
            }
            cb.onResult(result, error);
        };
    }

    static void signOut(Context ctx, Callback cb) {
        post(ctx, "/auth/v1/logout", new JSONObject(), true,
                (result, error) -> {
                    clearSession(ctx);
                    cb.onResult(new JSONObject(), null);
                });
    }

    static void fetchStatus(Context ctx, Callback cb) {
        rpc(ctx, "koorix_my_status", new JSONObject(), (result, error) -> {
            if (result != null) {
                cacheStatus(ctx, result);
            }
            cb.onResult(result, error);
        });
    }

    static void consumeJob(Context ctx, int pages, Callback cb) {
        JSONObject params = new JSONObject();
        try {
            params.put("p_pages", Math.max(1, pages));
        } catch (JSONException ignored) {
        }
        rpc(ctx, "koorix_consume_job", params, (result, error) -> {
            if (result != null) {
                cacheStatus(ctx, result);
            }
            cb.onResult(result, error);
        });
    }

    static void redeemCode(Context ctx, String code, Callback cb) {
        JSONObject params = new JSONObject();
        try {
            params.put("p_code", code);
        } catch (JSONException ignored) {
        }
        rpc(ctx, "koorix_redeem_code", params, cb);
    }

    // ---------- transport ----------

    private static void rpc(Context ctx, String fn, JSONObject params,
                            Callback cb) {
        ensureFreshToken(ctx, ok -> {
            if (!ok) {
                deliver(cb, null, "session_expired");
                return;
            }
            post(ctx, "/rest/v1/rpc/" + fn, params, true, cb);
        });
    }

    private interface BoolCallback {
        void onResult(boolean ok);
    }

    private static void ensureFreshToken(Context ctx, BoolCallback cb) {
        long expires = prefs(ctx).getLong(K_EXPIRES, 0L);
        if (System.currentTimeMillis() < expires - 60_000L) {
            cb.onResult(true);
            return;
        }
        String refresh = prefs(ctx).getString(K_REFRESH, "");
        if (refresh.isEmpty()) {
            cb.onResult(false);
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("refresh_token", refresh);
        } catch (JSONException ignored) {
        }
        post(ctx, "/auth/v1/token?grant_type=refresh_token", body, false,
                (result, error) -> {
                    if (result == null) {
                        // A rejected refresh (revoked/rotated elsewhere)
                        // means the stored session is dead.
                        if (error != null && !error.startsWith("network")) {
                            clearSession(ctx);
                        }
                        cb.onResult(false);
                        return;
                    }
                    try {
                        saveSession(ctx, result);
                        cb.onResult(true);
                    } catch (JSONException e) {
                        cb.onResult(false);
                    }
                });
    }

    private static void post(Context ctx, String path, JSONObject body,
                             boolean withUserToken, Callback cb) {
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(BuildConfig.SUPABASE_URL + path);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(20_000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("apikey", BuildConfig.SUPABASE_KEY);
                String bearer = withUserToken
                        ? prefs(app).getString(K_ACCESS, "")
                        : BuildConfig.SUPABASE_KEY;
                if (!bearer.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + bearer);
                }
                byte[] payload = body.toString()
                        .getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                }
                int code = conn.getResponseCode();
                InputStream in = code >= 200 && code < 300
                        ? conn.getInputStream() : conn.getErrorStream();
                String text = readAll(in);
                if (code >= 200 && code < 300) {
                    JSONObject json = text.isEmpty()
                            ? new JSONObject() : new JSONObject(text);
                    deliver(cb, json, null);
                } else {
                    deliver(cb, null, extractError(text, code));
                }
            } catch (Exception e) {
                deliver(cb, null, "network: " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String extractError(String text, int code) {
        try {
            JSONObject json = new JSONObject(text);
            String msg = json.optString("msg",
                    json.optString("message",
                            json.optString("error_description",
                                    json.optString("error", ""))));
            String errorCode = json.optString("error_code", "");
            if (!errorCode.isEmpty()) {
                return errorCode + ": " + msg;
            }
            return msg.isEmpty() ? ("http_" + code) : msg;
        } catch (JSONException e) {
            return "http_" + code;
        }
    }

    private static void deliver(Callback cb, JSONObject result, String error) {
        MAIN.post(() -> cb.onResult(result, error));
    }
}
