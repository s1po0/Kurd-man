package com.kurdman.app;

import org.json.JSONArray;
import org.json.JSONObject;

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
 * Free, no-API-key translation to Kurdish Sorani (ckb).
 *
 * <p>The first route is the same translate.googleapis.com endpoint used by
 * the Python pipeline. Some carriers/routers fail to resolve that one host
 * ("Unable to resolve host ... No address associated with hostname"), so the
 * translator automatically falls back to other Google hosts and finally to
 * MyMemory's anonymous API. The route that works is remembered for the rest
 * of the current translation job, which is especially useful for CBZs.</p>
 */
public class SoraniTranslator {
    private static final int ROUTE_GOOGLEAPIS = 0;
    private static final int ROUTE_GOOGLE_WEB = 1;
    private static final int ROUTE_GOOGLE_CLIENTS5 = 2;
    private static final int ROUTE_MYMEMORY = 3;
    private static final int ROUTE_COUNT = 4;

    private static final Map<String, String> GLOSSARY = new HashMap<>();

    static {
        GLOSSARY.put("who are you?!", "تۆ کێیت؟!");
        GLOSSARY.put("who are you?", "تۆ کێیت؟");
        GLOSSARY.put("what's that?", "ئەوە چییە؟");
        GLOSSARY.put("poison evasion orb?", "گۆی خۆلادان لە ژەهر؟");
        GLOSSARY.put("why are points being used?", "بۆچی خاڵەکان بەکاردەهێنرێن؟");
    }

    private final Map<String, String> cache = new HashMap<>();
    private int preferredRoute = ROUTE_GOOGLEAPIS;

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
        Exception lastError = null;
        for (int offset = 0; offset < ROUTE_COUNT; offset++) {
            int route = (preferredRoute + offset) % ROUTE_COUNT;
            try {
                String translated = translateViaRoute(route, text, sourceLanguage);
                if (!normalize(translated).isEmpty()) {
                    preferredRoute = route;
                    return translated;
                }
                throw new IOException("Empty translation response from " + routeName(route));
            } catch (Exception e) {
                lastError = e;
                if (offset + 1 < ROUTE_COUNT) {
                    pauseBriefly();
                }
            }
        }
        throw lastError == null
                ? new IOException("All translation routes failed")
                : lastError;
    }

    private String translateViaRoute(int route, String text, String sourceLanguage)
            throws Exception {
        if (route == ROUTE_MYMEMORY) {
            // MyMemory's anonymous /get endpoint accepts at most 500 UTF-8
            // bytes in q. Manhwa segments are normally short, but split long
            // chunks defensively so this fallback remains usable.
            StringBuilder joined = new StringBuilder();
            for (String part : splitByUtf8Bytes(text, 450)) {
                if (joined.length() > 0) {
                    joined.append(' ');
                }
                joined.append(requestRoute(route, part, sourceLanguage));
            }
            return joined.toString();
        }
        return requestRoute(route, text, sourceLanguage);
    }

    private String requestRoute(int route, String text, String sourceLanguage)
            throws Exception {
        String encodedSource = URLEncoder.encode(
                sourceLanguage, StandardCharsets.UTF_8.name());
        String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8.name());
        String url;
        if (route == ROUTE_GOOGLEAPIS) {
            url = "https://translate.googleapis.com/translate_a/single?client=gtx"
                    + "&sl=" + encodedSource + "&tl=ckb&dt=t&q=" + encodedText;
        } else if (route == ROUTE_GOOGLE_WEB) {
            url = "https://translate.google.com/translate_a/single?client=gtx"
                    + "&sl=" + encodedSource + "&tl=ckb&dt=t&q=" + encodedText;
        } else if (route == ROUTE_GOOGLE_CLIENTS5) {
            url = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex"
                    + "&sl=" + encodedSource + "&tl=ckb&q=" + encodedText;
        } else {
            String pair = ("ko".equals(sourceLanguage) ? "ko-KR" : "en-US") + "|ckb-IQ";
            url = "https://api.mymemory.translated.net/get?q=" + encodedText
                    + "&langpair=" + URLEncoder.encode(pair, StandardCharsets.UTF_8.name())
                    + "&mt=1";
        }

        String body = fetchText(url, routeName(route));
        return route == ROUTE_MYMEMORY
                ? parseMyMemoryTranslation(body)
                : parseGoogleTranslation(body);
    }

    private String fetchText(String url, String routeName) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 KurdMan/1.2");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");

            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(in);
            if (code < 200 || code >= 300) {
                throw new IOException("Translation HTTP " + code + " from " + routeName);
            }
            if (body.trim().isEmpty()) {
                throw new IOException("Empty translation response from " + routeName);
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    private String parseGoogleTranslation(String body) throws Exception {
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            JSONObject root = new JSONObject(trimmed);
            JSONArray sentences = root.optJSONArray("sentences");
            if (sentences != null) {
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < sentences.length(); i++) {
                    JSONObject sentence = sentences.optJSONObject(i);
                    if (sentence != null) {
                        result.append(sentence.optString("trans", ""));
                    }
                }
                if (result.length() > 0) {
                    return result.toString();
                }
            }

            JSONArray alternativeGroups = root.optJSONArray("alternative_translations");
            if (alternativeGroups != null && alternativeGroups.length() > 0) {
                JSONObject firstGroup = alternativeGroups.optJSONObject(0);
                JSONArray alternatives = firstGroup == null
                        ? null : firstGroup.optJSONArray("alternative");
                if (alternatives != null && alternatives.length() > 0) {
                    JSONObject first = alternatives.optJSONObject(0);
                    if (first != null) {
                        String value = first.optString("word_postproc", "");
                        return value.isEmpty() ? first.optString("word", "") : value;
                    }
                }
            }
            throw new IOException("Unexpected Google translation response");
        }

        JSONArray root = new JSONArray(trimmed);
        Object first = root.opt(0);
        if (first instanceof String) {
            return (String) first;
        }
        if (!(first instanceof JSONArray)) {
            throw new IOException("Unexpected Google translation response");
        }

        JSONArray firstArray = (JSONArray) first;
        if (firstArray.length() > 0 && firstArray.opt(0) instanceof String) {
            // clients5 can answer as [[translatedText, detectedLanguage]].
            return firstArray.optString(0, "");
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < firstArray.length(); i++) {
            JSONArray chunk = firstArray.optJSONArray(i);
            if (chunk != null) {
                result.append(chunk.optString(0, ""));
            }
        }
        return result.toString();
    }

    private String parseMyMemoryTranslation(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONObject data = root.optJSONObject("responseData");
        if (data == null) {
            throw new IOException("Unexpected MyMemory translation response");
        }
        String translated = data.optString("translatedText", "");
        String clean = normalize(translated);
        String status = root.optString("responseStatus", "");
        if (!"200".equals(status) || root.optBoolean("quotaFinished", false)
                || clean.isEmpty() || isMyMemoryError(clean)) {
            String details = root.optString("responseDetails", "");
            throw new IOException("MyMemory translation failed"
                    + (details.isEmpty() ? "" : ": " + details));
        }
        return translated;
    }

    private boolean isMyMemoryError(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("invalid")
                || lower.contains("no query specified")
                || lower.contains("query length limit")
                || lower.contains("quota")
                || lower.contains("target language");
    }

    private String routeName(int route) {
        switch (route) {
            case ROUTE_GOOGLE_WEB:
                return "translate.google.com";
            case ROUTE_GOOGLE_CLIENTS5:
                return "clients5.google.com";
            case ROUTE_MYMEMORY:
                return "api.mymemory.translated.net";
            case ROUTE_GOOGLEAPIS:
            default:
                return "translate.googleapis.com";
        }
    }

    private List<String> splitByUtf8Bytes(String text, int maxBytes) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = start;
            int bytes = 0;
            int lastSpaceEnd = -1;
            while (end < text.length()) {
                int codePoint = text.codePointAt(end);
                int charCount = Character.charCount(codePoint);
                int codePointBytes = text.substring(end, end + charCount)
                        .getBytes(StandardCharsets.UTF_8).length;
                if (bytes + codePointBytes > maxBytes) {
                    break;
                }
                bytes += codePointBytes;
                end += charCount;
                if (codePoint == ' ') {
                    lastSpaceEnd = end;
                }
            }
            if (end == start) {
                end = start + Character.charCount(text.codePointAt(start));
            } else if (end < text.length() && lastSpaceEnd > start
                    && bytes > maxBytes / 2) {
                end = lastSpaceEnd;
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
