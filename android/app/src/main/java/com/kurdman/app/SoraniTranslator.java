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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern LATIN_TOKEN_PATTERN = Pattern.compile(
            "[A-Za-z\\u00C0-\\u024F]+(?:['’.-][A-Za-z\\u00C0-\\u024F]+)*");
    private static final Set<String> LATIN_WORDS_TO_KEEP = new HashSet<>(Arrays.asList(
            "http", "https", "www", "com", "org", "net", "io", "app",
            "kg", "km", "cm", "mm", "hp", "mp", "xp", "dna", "usb", "wifi"));
    private static final Set<String> COMMON_STANDALONE_WORDS = new HashSet<>(Arrays.asList(
            "a", "an", "the", "i", "you", "he", "she", "it", "we", "they",
            "me", "him", "her", "us", "them", "my", "your", "his", "our", "their",
            "is", "are", "was", "were", "be", "been", "being", "am",
            "run", "stop", "wait", "look", "come", "go", "die", "kill", "attack",
            "fight", "help", "move", "open", "close", "get", "take", "give",
            "yes", "no", "what", "why", "how", "when", "where", "who",
            "hunter", "king", "queen", "master", "lord", "brother", "sister",
            "father", "mother", "friend", "enemy", "monster", "gate", "sword"));

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

        String preparedSource = prepareSourceText(normalized, sourceLanguage);
        String key = sourceLanguage + "\n" + preparedSource;
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        String translated;
        if (preparedSource.length() <= 1200) {
            translated = translateChunk(preparedSource, sourceLanguage);
        } else {
            StringBuilder joined = new StringBuilder();
            for (String part : splitLongText(preparedSource, 1200)) {
                if (joined.length() > 0) {
                    joined.append(' ');
                }
                joined.append(translateChunk(part, sourceLanguage));
                pauseBriefly();
            }
            translated = joined.toString();
        }

        translated = transliteratePreservedNames(normalized, translated);
        translated = normalize(translated);
        if (translated.isEmpty()) {
            throw new IOException("Empty translation response");
        }
        cache.put(key, translated);
        return translated;
    }

    /**
     * Manhwa OCR text is very often ALL CAPS. Google frequently leaves an
     * unfamiliar ALL-CAPS personal name in Latin letters, while the same
     * name in ordinary title case is transliterated into Sorani script.
     * Normalize only mostly-uppercase English blocks before translation.
     */
    private String prepareSourceText(String text, String sourceLanguage) {
        if (!"en".equals(sourceLanguage) || text == null) {
            return text;
        }
        int upper = 0;
        int lower = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isUpperCase(c)) {
                upper++;
            } else if (Character.isLowerCase(c)) {
                lower++;
            }
        }
        int cased = upper + lower;
        if (cased < 4 || (upper / (double) cased) < 0.68) {
            return text;
        }

        StringBuilder out = new StringBuilder(text.length());
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                out.append(inWord ? Character.toLowerCase(c) : Character.toUpperCase(c));
                inWord = true;
            } else {
                out.append(c);
                if (c != '\'' && c != '’') {
                    inWord = false;
                }
            }
        }
        return out.toString();
    }

    /**
     * Converts personal names that a translation route preserved in Latin
     * letters into Kurdish Sorani letters. This is deliberately limited to
     * name-looking tokens (or very short name-only segments), so ordinary
     * untranslated English words are not disguised as translations.
     */
    private String transliteratePreservedNames(String source, String translated) {
        if (translated == null || translated.isEmpty()) {
            return translated == null ? "" : translated;
        }
        Matcher matcher = LATIN_TOKEN_PATTERN.matcher(translated);
        if (!matcher.find()) {
            return translated;
        }
        matcher.reset();

        Map<String, String> sourceTokens = latinTokensByLowercase(source);
        boolean hasArabic = arabicScriptLetterCount(translated) > 0;
        boolean standaloneName = !hasArabic && isLikelyStandaloneName(source);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group();
            String sourceToken = sourceTokens.get(token.toLowerCase(Locale.ROOT));
            String replacement = shouldTransliterateToken(
                    token, sourceToken, hasArabic, standaloneName)
                    ? transliterateLatinToken(token)
                    : token;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private Map<String, String> latinTokensByLowercase(String text) {
        Map<String, String> tokens = new HashMap<>();
        if (text == null) {
            return tokens;
        }
        Matcher matcher = LATIN_TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            tokens.putIfAbsent(token.toLowerCase(Locale.ROOT), token);
        }
        return tokens;
    }

    private boolean shouldTransliterateToken(String token, String sourceToken,
                                             boolean hasArabic, boolean standaloneName) {
        String lettersOnly = token.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z\\u00C0-\\u024F]", "");
        if (lettersOnly.length() < 2 || LATIN_WORDS_TO_KEEP.contains(lettersOnly)) {
            return false;
        }
        if (standaloneName) {
            return true;
        }
        if (!hasArabic || COMMON_STANDALONE_WORDS.contains(lettersOnly)) {
            return false;
        }
        return looksNameForm(token)
                || (sourceToken != null && looksNameForm(sourceToken));
    }

    private boolean isLikelyStandaloneName(String source) {
        if (source == null) {
            return false;
        }
        Matcher matcher = LATIN_TOKEN_PATTERN.matcher(source);
        int tokenCount = 0;
        int letterCount = 0;
        while (matcher.find()) {
            String token = matcher.group();
            String lettersOnly = token.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z\\u00C0-\\u024F]", "");
            if (lettersOnly.length() < 2
                    || COMMON_STANDALONE_WORDS.contains(lettersOnly)
                    || LATIN_WORDS_TO_KEEP.contains(lettersOnly)
                    || !looksNameForm(token)) {
                return false;
            }
            tokenCount++;
            letterCount += lettersOnly.length();
        }
        return tokenCount >= 1 && tokenCount <= 4 && letterCount <= 36;
    }

    private boolean looksNameForm(String token) {
        boolean firstLetterUpper = false;
        boolean seenLetter = false;
        boolean seenLower = false;
        for (int offset = 0; offset < token.length();) {
            int codePoint = token.codePointAt(offset);
            if (Character.isLetter(codePoint)) {
                if (!seenLetter && Character.isUpperCase(codePoint)) {
                    firstLetterUpper = true;
                }
                if (seenLower && Character.isUpperCase(codePoint)) {
                    return true;
                }
                if (Character.isLowerCase(codePoint)) {
                    seenLower = true;
                }
                seenLetter = true;
            }
            offset += Character.charCount(codePoint);
        }
        return firstLetterUpper;
    }

    private String transliterateLatinToken(String token) {
        String[] parts = token.split("-", -1);
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) {
                out.append('-');
            }
            out.append(transliterateLatinWord(part));
        }
        return out.length() == 0 ? token : out.toString();
    }

    private String transliterateLatinWord(String rawWord) {
        String originalLetters = rawWord.replaceAll("[^A-Za-z]", "");
        if (originalLetters.length() >= 2 && originalLetters.length() <= 5
                && originalLetters.equals(originalLetters.toUpperCase(Locale.ROOT))
                && !originalLetters.matches(".*[AEIOU].*")) {
            StringBuilder initials = new StringBuilder();
            for (int i = 0; i < originalLetters.length(); i++) {
                initials.append(latinLetterName(originalLetters.charAt(i)));
            }
            return initials.toString();
        }

        String word = stripLatinAccents(rawWord)
                .replace("’", "'")
                .replace("'", "")
                .replace(".", "")
                .toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < word.length()) {
            char c = word.charAt(i);
            if (!Character.isLetter(c)) {
                i++;
                continue;
            }
            if (i > 0 && c == word.charAt(i - 1) && !isLatinVowel(c)) {
                i++;
                continue;
            }

            if (startsAt(word, i, "iao")) {
                appendVowel(out, "یاو");
                i += 3;
                continue;
            }
            if (startsAt(word, i, "tch") || startsAt(word, i, "sch")) {
                out.append(startsAt(word, i, "tch") ? "چ" : "ش");
                i += 3;
                continue;
            }
            if (startsAt(word, i, "ngh")) {
                out.append("نگ");
                i += 3;
                continue;
            }
            if (startsAt(word, i, "eau")) {
                appendVowel(out, "ۆ");
                i += 3;
                continue;
            }
            if (startsAt(word, i, "yeo")) {
                out.append("یۆ");
                i += 3;
                continue;
            }

            if (startsAt(word, i, "ya")) {
                out.append("یا");
                i += 2;
                continue;
            }
            if (startsAt(word, i, "ye")) {
                out.append("یێ");
                i += 2;
                continue;
            }
            if (startsAt(word, i, "yo")) {
                out.append("یۆ");
                i += 2;
                continue;
            }
            if (startsAt(word, i, "yu")) {
                out.append("یو");
                i += 2;
                continue;
            }

            if (startsAt(word, i, "sh")) {
                out.append("ش");
                i += 2;
                continue;
            }
            if (startsAt(word, i, "ch")) {
                out.append("چ");
                i += 2;
                continue;
            }
            if (startsAt(word, i, "kh")) {
                out.append("خ");
                i += 2;
                continue;
            }
            if (startsAt(word, i, "zh")) {
                out.append("ژ");
                i += 2;
                continue;
            }
            if (startsAt(word, i, "ph")) {
                out.append("ف");
                i += 2;
                continue;
            }
            if (startsAt(word, i, "th")) {
                out.append("س");
                i += 2;
                continue;
            }
            if (startsAt(word, i, "ck")) {
                out.append("ک");
                i += 2;
                continue;
            }
            if (startsAt(word, i, "qu")) {
                out.append("کو");
                i += 2;
                continue;
            }
            if (startsAt(word, i, "ng")) {
                char after = i + 2 < word.length() ? word.charAt(i + 2) : '\0';
                if (after == 'e' || after == 'i' || after == 'y') {
                    out.append("ن");
                    i++;
                } else {
                    out.append("نگ");
                    i += 2;
                }
                continue;
            }
            if (startsAt(word, i, "gh")) {
                char after = i + 2 < word.length() ? word.charAt(i + 2) : '\0';
                if (i == 0) {
                    out.append("غ");
                } else if (isLatinVowel(after)) {
                    out.append("گ");
                }
                i += 2;
                continue;
            }
            if (i == 0 && (startsAt(word, i, "kn") || startsAt(word, i, "gn")
                    || startsAt(word, i, "wr"))) {
                if (c == 'w') {
                    out.append("ڕ");
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }

            String vowelPair = vowelPairMapping(word, i);
            if (vowelPair != null) {
                appendVowel(out, vowelPair);
                i += 2;
                continue;
            }

            switch (c) {
                case 'a':
                    appendVowel(out, "ا");
                    break;
                case 'b':
                    out.append("ب");
                    break;
                case 'c': {
                    char next = i + 1 < word.length() ? word.charAt(i + 1) : '\0';
                    out.append(next == 'e' || next == 'i' || next == 'y' ? "س" : "ک");
                    break;
                }
                case 'd':
                    out.append("د");
                    break;
                case 'e':
                    if (i != word.length() - 1 || out.length() == 0) {
                        appendVowel(out, "ێ");
                    }
                    break;
                case 'f':
                    out.append("ف");
                    break;
                case 'g': {
                    char next = i + 1 < word.length() ? word.charAt(i + 1) : '\0';
                    out.append(next == 'e' || next == 'i' || next == 'y' ? "ج" : "گ");
                    break;
                }
                case 'h':
                    if (!(i == word.length() - 1 && i > 0
                            && isLatinVowel(word.charAt(i - 1)))) {
                        out.append("ه");
                    }
                    break;
                case 'i':
                    appendVowel(out, "ی");
                    break;
                case 'j':
                    out.append("ج");
                    break;
                case 'k':
                    out.append("ک");
                    break;
                case 'l':
                    out.append("ل");
                    break;
                case 'm':
                    out.append("م");
                    break;
                case 'n':
                    out.append("ن");
                    break;
                case 'o':
                    appendVowel(out, "ۆ");
                    break;
                case 'p':
                    out.append("پ");
                    break;
                case 'q':
                    out.append("ق");
                    break;
                case 'r':
                    out.append(out.length() == 0 ? "ڕ" : "ر");
                    break;
                case 's':
                    out.append("س");
                    break;
                case 't':
                    out.append("ت");
                    break;
                case 'u':
                    appendVowel(out, "و");
                    break;
                case 'v':
                    out.append("ڤ");
                    break;
                case 'w':
                    out.append("و");
                    break;
                case 'x': {
                    char next = i + 1 < word.length() ? word.charAt(i + 1) : '\0';
                    if (out.length() == 0) {
                        out.append(next == 'i' || next == 'y' || next == 'u' ? "ش" : "ز");
                    } else {
                        out.append("کس");
                    }
                    break;
                }
                case 'y':
                    if (out.length() == 0) {
                        out.append("ی");
                    } else {
                        appendVowel(out, "ی");
                    }
                    break;
                case 'z':
                    out.append("ز");
                    break;
                default:
                    break;
            }
            i++;
        }
        return out.length() == 0 ? rawWord : out.toString();
    }

    private String vowelPairMapping(String word, int i) {
        if (i + 1 >= word.length()) {
            return null;
        }
        String pair = word.substring(i, i + 2);
        switch (pair) {
            case "ae":
                return "ە";
            case "ai":
            case "ay":
                return "ای";
            case "au":
            case "aw":
                return "او";
            case "ea":
            case "ee":
            case "ie":
                return "ی";
            case "ei":
            case "ey":
                return "ەی";
            case "eo":
            case "oe":
                return "ۆ";
            case "eu": {
                char after = i + 2 < word.length() ? word.charAt(i + 2) : '\0';
                return after == 'n' || after == 'm' ? "ێ" : "ێو";
            }
            case "ia":
                return "یا";
            case "io":
                return "یۆ";
            case "iu":
                return "یو";
            case "oa":
                return "ۆا";
            case "oi":
            case "oy":
                return "ۆی";
            case "oo":
            case "ou":
                return "و";
            case "ua":
                return "وا";
            case "ue":
                return i + 2 == word.length() ? "و" : "وێ";
            case "ui":
                return "وی";
            case "uo":
                return "وۆ";
            default:
                return null;
        }
    }

    private void appendVowel(StringBuilder out, String mapping) {
        if (out.length() == 0) {
            out.append("ئ");
        }
        out.append(mapping);
    }

    private boolean startsAt(String value, int offset, String prefix) {
        return value.regionMatches(offset, prefix, 0, prefix.length());
    }

    private boolean isLatinVowel(char c) {
        return "aeiouy".indexOf(c) >= 0;
    }

    private String stripLatinAccents(String value) {
        String replaced = value
                .replace("ß", "ss")
                .replace("Æ", "Ae")
                .replace("æ", "ae")
                .replace("Œ", "Oe")
                .replace("œ", "oe")
                .replace("Ø", "O")
                .replace("ø", "o")
                .replace("Đ", "D")
                .replace("đ", "d")
                .replace("Ð", "D")
                .replace("ð", "d")
                .replace("Þ", "Th")
                .replace("þ", "th")
                .replace("Ł", "L")
                .replace("ł", "l");
        return Normalizer.normalize(replaced, Normalizer.Form.NFD)
                .replaceAll("\\p{Mn}+", "");
    }

    private String latinLetterName(char upper) {
        switch (upper) {
            case 'A': return "ئەی";
            case 'B': return "بی";
            case 'C': return "سی";
            case 'D': return "دی";
            case 'E': return "ئی";
            case 'F': return "ئێف";
            case 'G': return "جی";
            case 'H': return "ئێچ";
            case 'I': return "ئای";
            case 'J': return "جەی";
            case 'K': return "کەی";
            case 'L': return "ئێڵ";
            case 'M': return "ئێم";
            case 'N': return "ئێن";
            case 'O': return "ئۆ";
            case 'P': return "پی";
            case 'Q': return "کیو";
            case 'R': return "ئاڕ";
            case 'S': return "ئێس";
            case 'T': return "تی";
            case 'U': return "یوو";
            case 'V': return "ڤی";
            case 'W': return "دەبڵیوو";
            case 'X': return "ئێکس";
            case 'Y': return "وای";
            case 'Z': return "زێد";
            default: return String.valueOf(upper);
        }
    }

    private String translateChunk(String text, String sourceLanguage) throws Exception {
        Exception lastError = null;
        String untranslatedCandidate = null;
        for (int offset = 0; offset < ROUTE_COUNT; offset++) {
            int route = (preferredRoute + offset) % ROUTE_COUNT;
            try {
                String translated = translateViaRoute(route, text, sourceLanguage);
                if (normalize(translated).isEmpty()) {
                    throw new IOException("Empty translation response from " + routeName(route));
                }
                if (looksUntranslated(text, translated)) {
                    // Some free routes occasionally echo the source back. Keep
                    // it only as a last resort (for names/terms) and try the
                    // remaining routes for an actual Sorani translation first.
                    if (untranslatedCandidate == null) {
                        untranslatedCandidate = translated;
                    }
                    lastError = new IOException(
                            "Untranslated response from " + routeName(route));
                } else {
                    preferredRoute = route;
                    return translated;
                }
            } catch (Exception e) {
                lastError = e;
            }
            if (offset + 1 < ROUTE_COUNT) {
                pauseBriefly();
            }
        }
        if (untranslatedCandidate != null) {
            return untranslatedCandidate;
        }
        throw lastError == null
                ? new IOException("All translation routes failed")
                : lastError;
    }

    private boolean looksUntranslated(String source, String result) {
        String src = normalize(source);
        String out = normalize(result);
        if (src.equalsIgnoreCase(out) && letterCount(src) >= 4) {
            return true;
        }
        // A normal Sorani sentence contains Arabic-script letters. If the
        // result has none, it is probably an echoed English/Korean source.
        return letterCount(src) >= 5
                && letterCount(out) >= 4
                && arabicScriptLetterCount(out) == 0
                && (src.contains(" ") || src.length() >= 8);
    }

    private int letterCount(String value) {
        int count = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isLetter(codePoint)) {
                count++;
            }
            offset += Character.charCount(codePoint);
        }
        return count;
    }

    private int arabicScriptLetterCount(String value) {
        int count = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isLetter(codePoint)
                    && ((codePoint >= 0x0600 && codePoint <= 0x06FF)
                    || (codePoint >= 0x0750 && codePoint <= 0x077F)
                    || (codePoint >= 0x08A0 && codePoint <= 0x08FF)
                    || (codePoint >= 0xFB50 && codePoint <= 0xFDFF)
                    || (codePoint >= 0xFE70 && codePoint <= 0xFEFF))) {
                count++;
            }
            offset += Character.charCount(codePoint);
        }
        return count;
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

    private String myMemorySourceCode(String sourceLanguage) {
        if ("ko".equals(sourceLanguage)) {
            return "ko-KR";
        }
        if ("ja".equals(sourceLanguage)) {
            return "ja-JP";
        }
        if ("zh-TW".equals(sourceLanguage) || "zh-Hant".equals(sourceLanguage)) {
            return "zh-TW";
        }
        if ("zh-CN".equals(sourceLanguage) || "zh-Hans".equals(sourceLanguage)
                || "zh".equals(sourceLanguage)) {
            return "zh-CN";
        }
        if ("en".equals(sourceLanguage)) {
            return "en-US";
        }
        return sourceLanguage == null ? "en-US" : sourceLanguage;
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
            String pair = myMemorySourceCode(sourceLanguage) + "|ckb-IQ";
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
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 KurdMan/1.4");
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
