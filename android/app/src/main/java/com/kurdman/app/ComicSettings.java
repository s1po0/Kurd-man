package com.kurdman.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Android persistence/localisation for the pure-Java comic skill. */
final class ComicSettings {
    static final String PREFS = "kurdman_settings";
    private static final String ENABLED = "comic_skill_v1_enabled";
    private static final String GENRE = "comic_skill_v1_genre";
    private static List<ComicTranslationSkill.Entry> lexicon;

    private ComicSettings() { }

    private static String glossaryKey(String source, String target) {
        return "comic_skill_v1_glossary_" + source + "_" + target;
    }

    static ComicTranslationSkill.Config read(Context context, String source, String target) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new ComicTranslationSkill.Config(BuildConfig.PUBLIC_EDITION || prefs.getBoolean(ENABLED, true),
                genre(prefs.getString(GENRE, "AUTO")),
                prefs.getString(glossaryKey(source, target), ""));
    }

    static void save(Context context, String source, String target, ComicTranslationSkill.Config config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(ENABLED, config.enabled)
                .putString(GENRE, config.genre.name())
                .putString(glossaryKey(source, target), config.glossary).apply();
    }

    static synchronized List<ComicTranslationSkill.Entry> lexicon(Context context) throws IOException {
        if (lexicon == null) {
            lexicon = ComicTranslationSkill.readLexicon(new InputStreamReader(
                    context.getAssets().open("comic-lexicon-v1.tsv"), StandardCharsets.UTF_8));
        }
        return lexicon;
    }

    static String[] genreLabels(Context context) {
        return new String[]{context.getString(R.string.comic_genre_auto),
                context.getString(R.string.comic_genre_fantasy),
                context.getString(R.string.comic_genre_martial),
                context.getString(R.string.comic_genre_everyday)};
    }

    static String summary(Context context, ComicTranslationSkill.Config config) {
        if (!config.enabled) return context.getString(R.string.comic_disabled_summary);
        int entries;
        try { entries = ComicTranslationSkill.validateGlossary(config.glossary); }
        catch (ComicTranslationSkill.GlossaryException invalid) { entries = 0; }
        return context.getString(R.string.comic_enabled_summary,
                genreLabels(context)[config.genre.ordinal()], entries);
    }

    static String warnings(Context context, String codes) {
        if (codes == null || codes.isEmpty()) return "";
        List<String> messages = new ArrayList<>();
        for (String code : codes.split(",")) {
            switch (code) {
                case "numbers": messages.add(context.getString(R.string.comic_review_numbers)); break;
                case "number_order": messages.add(context.getString(R.string.q_numbers_order)); break;
                case "glossary_missing": messages.add(context.getString(R.string.q_missing_term)); break;
                case "target_script": messages.add(context.getString(R.string.q_script)); break;
                case "empty_target": messages.add(context.getString(R.string.q_empty_target)); break;
                case "markers": messages.add(context.getString(R.string.q_markers)); break;
                case "source_empty": messages.add(context.getString(R.string.q_source_empty)); break;
                case "ocr_low": messages.add(context.getString(R.string.q_ocr)); break;
                case "glossary_invalid": messages.add(context.getString(R.string.q_invalid_glossary)); break;
                case "repetition": messages.add(context.getString(R.string.comic_review_repetition)); break;
                case "untranslated": messages.add(context.getString(R.string.comic_review_untranslated)); break;
                case "glossary_unconfirmed": messages.add(context.getString(R.string.comic_review_glossary)); break;
                default: break;
            }
        }
        return messages.isEmpty() ? "" : "⚠ " + String.join(" • ", messages);
    }

    static JSONObject toJson(ComicTranslationSkill.Config config) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("version", 1);
        json.put("enabled", config.enabled);
        json.put("genre", config.genre.name());
        json.put("glossary", config.glossary);
        return json;
    }

    static ComicTranslationSkill.Config fromJson(JSONObject json) {
        // Old projects must not suddenly acquire a different translation mode.
        if (json == null) return new ComicTranslationSkill.Config(false, ComicTranslationSkill.Genre.AUTO, "");
        return new ComicTranslationSkill.Config(json.optBoolean("enabled", false),
                genre(json.optString("genre", "AUTO")), json.optString("glossary", ""));
    }

    private static ComicTranslationSkill.Genre genre(String value) {
        try { return ComicTranslationSkill.Genre.valueOf(value); }
        catch (IllegalArgumentException | NullPointerException ignored) { return ComicTranslationSkill.Genre.AUTO; }
    }
}
