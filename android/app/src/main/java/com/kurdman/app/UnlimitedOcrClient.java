package com.kurdman.app;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Optional whole-block OCR through a user-run Unlimited-OCR server.
 *
 * <p>Baidu's Unlimited-OCR is a 3B-parameter vision-language model
 * (MIT, built on DeepSeek-OCR) for one-shot document parsing. Baidu
 * only ships GPU inference (Transformers/vLLM/SGLang with CUDA), and
 * even 4-bit quantized weights plus the vision projector are roughly
 * 2.8&nbsp;GB — far too heavy and slow to run inside the phone for
 * every manhwa page. So KurdMan does not embed it. Instead, when the
 * user runs the model on their own computer (SGLang/vLLM expose an
 * OpenAI-compatible API) and enters that address in Settings, weak
 * grouped blocks are sent there as ONE crop each — the whole bubble
 * at once — and the returned text is used only when it is clean.</p>
 *
 * <p>This is entirely optional and off by default; with no endpoint
 * configured the app never touches this class. Any failure here is
 * non-fatal: the caller keeps the on-device ML Kit reading.</p>
 */
final class UnlimitedOcrClient implements ManhwaRenderer.BlockOcr {
    private final String endpoint;
    private final String apiKey;
    private final String model;

    UnlimitedOcrClient(String endpoint, String apiKey, String model) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.trim().isEmpty()
                ? "Unlimited-OCR" : model.trim();
    }

    static boolean isConfigured(String endpoint) {
        if (endpoint == null) {
            return false;
        }
        String value = endpoint.trim();
        return value.startsWith("http://") || value.startsWith("https://");
    }

    @Override
    public String readBlock(Bitmap page, Rect box) throws Exception {
        if (page == null || box == null || !isConfigured(endpoint)) {
            return null;
        }
        Bitmap crop = cropBlock(page, box);
        if (crop == null) {
            return null;
        }
        try {
            String dataUrl = "data:image/jpeg;base64," + toJpegBase64(crop);
            String content = chatCompletion(dataUrl);
            return cleanDetOutput(content);
        } finally {
            crop.recycle();
        }
    }

    private Bitmap cropBlock(Bitmap page, Rect box) {
        int padX = Math.max(10, Math.round(box.width() * 0.12f));
        int padY = Math.max(10, Math.round(box.height() * 0.18f));
        Rect rect = new Rect(
                Math.max(0, box.left - padX),
                Math.max(0, box.top - padY),
                Math.min(page.getWidth(), box.right + padX),
                Math.min(page.getHeight(), box.bottom + padY));
        if (rect.width() < 16 || rect.height() < 12) {
            return null;
        }
        Bitmap crop = Bitmap.createBitmap(page, rect.left, rect.top,
                rect.width(), rect.height());
        // The model reads best around its 640–1024 working sizes; give
        // small bubbles a clean 2× upscale, cap very large boxes.
        float scale = 1f;
        if (crop.getWidth() < 512) {
            scale = 2f;
        }
        float maxSide = Math.max(crop.getWidth(), crop.getHeight()) * scale;
        if (maxSide > 1600f) {
            scale *= 1600f / maxSide;
        }
        if (scale != 1f) {
            Bitmap scaled = Bitmap.createScaledBitmap(crop,
                    Math.max(1, Math.round(crop.getWidth() * scale)),
                    Math.max(1, Math.round(crop.getHeight() * scale)),
                    true);
            crop.recycle();
            return scaled;
        }
        return crop;
    }

    private String toJpegBase64(Bitmap bitmap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)) {
            throw new IOException("Could not encode block crop");
        }
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private String chatCompletion(String imageDataUrl) throws Exception {
        JSONObject textPart = new JSONObject();
        textPart.put("type", "text");
        textPart.put("text", "document parsing.");
        JSONObject imageUrl = new JSONObject();
        imageUrl.put("url", imageDataUrl);
        JSONObject imagePart = new JSONObject();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", imageUrl);
        JSONArray content = new JSONArray();
        content.put(textPart);
        content.put(imagePart);
        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", content);
        JSONArray messages = new JSONArray();
        messages.put(message);
        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("temperature", 0);
        payload.put("max_tokens", 4096);

        String base = endpoint.endsWith("/")
                ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        URL url = new URL(base + "/v1/chat/completions");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(180000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        if (!apiKey.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        connection.setDoOutput(true);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String body = readAll(stream);
        if (code < 200 || code >= 300) {
            throw new IOException("Unlimited-OCR server HTTP " + code);
        }
        JSONObject root = new JSONObject(body);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return null;
        }
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        return msg == null ? null : msg.optString("content", null);
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    /**
     * Unlimited-OCR prefixes blocks with
     * {@code <|det|>type [x1,y1,x2,y2]<|/det|>} markers. Keep the text,
     * turn markers into line breaks, and drop any other special tokens.
     */
    static String cleanDetOutput(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw
                .replaceAll("<\\|det\\|>[^<]*<\\|/det\\|>", "\n")
                .replaceAll("<\\|[^|<>]*\\|>", " ")
                .replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{2,}", "\n")
                .trim();
        return text.isEmpty() ? null : text;
    }
}
