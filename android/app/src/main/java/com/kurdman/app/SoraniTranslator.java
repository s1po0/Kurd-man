package com.kurdman.app;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Free, no-API-key translation to Kurdish Sorani (ckb), matching the
 * translate.googleapis.com endpoint used by the Python pipeline.
 */
public class SoraniTranslator {
    private static final Map<String, String> GLOSSARY = new HashMap<>();

    static {
        GLOSSARY.put("who are you?!", "تۆ کێیت؟!");
        GLOSSARY.put("who are you?", "تۆ کێیت؟");
        GLOSSARY.put("what's that?", "ئەوە چییە؟");
        GLOSSARY.put("poison evasion orb?", "گۆی خۆلادان لە ژەهر؟");
        GLOSSARY.put("why are points being used?", "بۆچی خاڵەکان بەکاردەهێنرێن؟");
    }

    private final Map<String, String> cache = new HashMap<>();

    public String translate(String text, String sourceLanguage) throws Exception {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return "";
        }

        String glossaryHit = GLOSSARY.get(normalized.toLowerCase(Locale.ROOT));
        if (glossaryHit != null) {
            return glossaryHit;
        }

        String key = sourceLanguage + "\n" + normalized;
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        String translated;
        if (normalized.length() <= 1200) {
            translated = translateChunk(normalized, sourceLanguage);
        } else {
            StringBuilder joined = new StringBuilder();
            for (String part : splitLongText(normalized, 1200)) {
                if (joined.length() > 0) {
                    joined.append(' ');
                }
                joined.append(translateChunk(part, sourceLanguage));
                pauseBriefly();
            }
            translated = joined.toString();
        }

        translated = normalize(translated);
        if (translated.isEmpty()) {
            throw new IOException("Empty translation response");
        }
        cache.put(key, translated);
        return translated;
    }

    private String translateChunk(String text, String sourceLanguage) throws Exception {
        String url = "https://translate.googleapis.com/translate_a/single?client=gtx"
                + "&sl=" + URLEncoder.encode(sourceLanguage, StandardCharsets.UTF_8.name())
                + "&tl=ckb&dt=t&q="
                + URLEncoder.encode(text, StandardCharsets.UTF_8.name());

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) KurdMan/1.0");
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(in);
        if (code < 200 || code >= 300) {
            throw new IOException("Translation HTTP " + code);
        }

        JSONArray root = new JSONArray(body);
        JSONArray chunks = root.optJSONArray(0);
        if (chunks == null) {
            throw new IOException("Unexpected translation response");
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < chunks.length(); i++) {
            JSONArray chunk = chunks.optJSONArray(i);
            if (chunk != null) {
                result.append(chunk.optString(0, ""));
            }
        }
        return result.toString();
    }

    private List<String> splitLongText(String text, int maxLength) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxLength);
            if (end < text.length()) {
                int boundary = -1;
                for (int i = end - 1; i > start + (maxLength / 2); i--) {
                    if (".!?。！？".indexOf(text.charAt(i)) >= 0) {
                        boundary = i + 1;
                        break;
                    }
                }
                if (boundary == -1) {
                    boundary = text.lastIndexOf(' ', end);
                }
                if (boundary > start) {
                    end = boundary;
                }
            }
            String part = text.substring(start, end).trim();
            if (!part.isEmpty()) {
                parts.add(part);
            }
            start = end;
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
        }
        return parts;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\s+", " ").trim();
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

    private void pauseBriefly() throws IOException {
        try {
            Thread.sleep(80);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Translation interrupted", e);
        }
    }
}
