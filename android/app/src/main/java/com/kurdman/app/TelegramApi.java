package com.kurdman.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Minimal Telegram Bot API client. The token is supplied by the app settings only. */
public class TelegramApi {
    private final String baseUrl;

    public TelegramApi(String token) {
        this.baseUrl = "https://api.telegram.org/bot" + token.trim();
    }

    public JSONObject getMe() throws Exception {
        return postJson("getMe", new JSONObject());
    }

    public JSONObject getChat(String chatId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("chat_id", chatId);
        return postJson("getChat", body);
    }

    public JSONObject sendChatAction(String chatId, String action) throws Exception {
        JSONObject body = new JSONObject();
        body.put("chat_id", chatId);
        body.put("action", action);
        return postJson("sendChatAction", body);
    }

    public JSONObject sendMessage(String chatId, String text) throws Exception {
        JSONObject body = new JSONObject();
        body.put("chat_id", chatId);
        body.put("text", text);
        return postJson("sendMessage", body);
    }

    public JSONObject sendDocument(String chatId, File file, String caption) throws Exception {
        if (file == null || !file.exists()) {
            throw new IOException("Document file does not exist");
        }
        if (file.length() <= 0) {
            throw new IOException("Document file is empty");
        }
        if (file.length() > 49L * 1024L * 1024L) {
            throw new IOException("Bad Request: file is too big ("
                    + file.length() + " bytes)");
        }
        String safeCaption = caption == null ? "" : caption.trim();
        if (safeCaption.length() > 1024) {
            safeCaption = safeCaption.substring(0, 1024);
        }

        String boundary = "KurdMan-" + UUID.randomUUID().toString();

        // Build everything except the file bytes up front so the request can
        // use a fixed Content-Length (like curl) instead of chunked
        // transfer-encoding, which some servers/proxies handle poorly.
        ByteArrayOutputStream prefixBuffer = new ByteArrayOutputStream();
        DataOutputStream prefix = new DataOutputStream(prefixBuffer);
        writeField(prefix, boundary, "chat_id", chatId);
        if (!safeCaption.isEmpty()) {
            writeField(prefix, boundary, "caption", safeCaption);
        }
        writeFileHeader(prefix, boundary, "document", file);
        prefix.flush();
        byte[] suffix = ("\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.UTF_8);
        long contentLength = prefixBuffer.size() + file.length() + suffix.length;

        HttpURLConnection conn = open("sendDocument");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setUseCaches(false);
        conn.setReadTimeout(300000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Connection", "Keep-Alive");
        conn.setFixedLengthStreamingMode(contentLength);

        OutputStream out = conn.getOutputStream();
        try {
            prefixBuffer.writeTo(out);
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            } finally {
                in.close();
            }
            out.write(suffix);
            out.flush();
        } finally {
            out.close();
        }
        return readResult(conn, "sendDocument");
    }

    private JSONObject postJson(String method, JSONObject body) throws Exception {
        HttpURLConnection conn = open(method);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setUseCaches(false);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        OutputStream out = conn.getOutputStream();
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
        return readResult(conn, method);
    }

    private HttpURLConnection open(String method) throws IOException {
        URL url = new URL(baseUrl + "/" + method);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(90000);
        conn.setRequestProperty("User-Agent", "KurdMan-Android/1.0");
        return conn;
    }

    private JSONObject readResult(HttpURLConnection conn, String method) throws Exception {
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String text = readAll(in);
        if (text == null || text.trim().isEmpty()) {
            throw new IOException(method + " HTTP " + code + " (empty response)");
        }
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (Exception parseError) {
            String preview = text.length() > 200 ? text.substring(0, 200) : text;
            throw new IOException(method + " HTTP " + code + ": " + preview);
        }
        if (!root.optBoolean("ok")) {
            throw new IOException(method + " HTTP " + code + ": "
                    + root.optString("description", "Telegram error"));
        }
        Object result = root.opt("result");
        if (result instanceof JSONObject) {
            return (JSONObject) result;
        }
        JSONObject wrapped = new JSONObject();
        wrapped.put("result", result);
        return wrapped;
    }

    private String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private void writeField(DataOutputStream out, String boundary, String name, String value)
            throws IOException {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    private void writeFileHeader(DataOutputStream out, String boundary, String name, File file)
            throws IOException {
        String contentType = URLConnection.guessContentTypeFromName(file.getName());
        if (contentType == null) {
            contentType = "application/octet-stream";
        }
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"; filename=\""
                + file.getName() + "\"\r\n");
        out.writeBytes("Content-Type: " + contentType + "\r\n\r\n");
    }
}
