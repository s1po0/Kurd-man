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
        if (file.length() > 49L * 1024L * 1024L) {
            throw new IOException("Document is larger than Telegram's bot upload limit");
        }

        HttpURLConnection conn = open("sendDocument");
        String boundary = "KurdMan-" + UUID.randomUUID().toString();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setChunkedStreamingMode(64 * 1024);

        DataOutputStream out = new DataOutputStream(conn.getOutputStream());
        try {
            writeField(out, boundary, "chat_id", chatId);
            if (caption != null && !caption.trim().isEmpty()) {
                writeField(out, boundary, "caption", caption);
            }
            writeFile(out, boundary, "document", file);
            out.writeBytes("--" + boundary + "--\r\n");
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
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
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
        JSONObject root = new JSONObject(text);
        if (!root.optBoolean("ok")) {
            throw new IOException(method + ": " + root.optString("description", "Telegram error " + code));
        }
        Object result = root.get("result");
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

    private void writeFile(DataOutputStream out, String boundary, String name, File file)
            throws IOException {
        String contentType = URLConnection.guessContentTypeFromName(file.getName());
        if (contentType == null) {
            contentType = "application/octet-stream";
        }
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"; filename=\""
                + file.getName() + "\"\r\n");
        out.writeBytes("Content-Type: " + contentType + "\r\n\r\n");

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
        out.writeBytes("\r\n");
    }
}
