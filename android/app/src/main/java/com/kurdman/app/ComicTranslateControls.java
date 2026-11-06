package com.kurdman.app;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/** Optional inline story preferences. Normal use is simply pick -> Translate. */
final class ComicTranslateControls {
    private static final String STATE = "koorix.inline.story";
    private final Context context;
    private final View panel, toggle, reset;
    private final EditText glossary;
    private final Spinner genre;
    private final TextView summary, pair;
    private final Map<String, ComicTranslationSkill.Config> drafts = new LinkedHashMap<>();
    private String source, target;
    private ComicTranslationSkill.Config baseline;

    ComicTranslateControls(Context context, View root, Bundle state) {
        this.context = context;
        panel = root.findViewById(R.id.comicInlinePanel);
        toggle = root.findViewById(R.id.comicSettingsButton);
        reset = root.findViewById(R.id.comicInlineReset);
        glossary = root.findViewById(R.id.comicInlineGlossary);
        genre = root.findViewById(R.id.comicInlineGenre);
        summary = root.findViewById(R.id.comicSkillSummary);
        pair = root.findViewById(R.id.comicInlinePair);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, R.layout.spinner_item,
                ComicSettings.genreLabels(context));
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        genre.setAdapter(adapter);
        toggle.setOnClickListener(v -> panel.setVisibility(
                panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        reset.setOnClickListener(v -> { glossary.setText(""); genre.setSelection(0); });
        if (state != null) {
            Bundle saved = state.getBundle(STATE);
            if (saved != null) {
                String s = saved.getString("source"), t = saved.getString("target");
                int g = saved.getInt("genre", 0);
                String text = saved.getString("glossary", "");
                if (s != null && t != null && g >= 0 && g < ComicTranslationSkill.Genre.values().length
                        && text.length() <= 12000) {
                    drafts.put(key(s, t), new ComicTranslationSkill.Config(true,
                            ComicTranslationSkill.Genre.values()[g], text));
                }
                panel.setVisibility(saved.getBoolean("expanded") ? View.VISIBLE : View.GONE);
            }
        }
    }

    void selectPair(String source, String target) {
        if (source == null || target == null) return;
        ComicTranslationSkill.Config saved = ComicSettings.read(context, source, target);
        if (source.equals(this.source) && target.equals(this.target)) {
            // An explicit future-preset edit in another screen may refresh a clean
            // form, but must not discard an unfinished inline draft.
            if (sameConfig(current(), baseline) && !sameConfig(saved, baseline)) load(saved);
            return;
        }
        if (this.source != null) drafts.put(key(this.source, this.target), current());
        while (drafts.size() > 8) drafts.remove(drafts.keySet().iterator().next());
        this.source = source;
        this.target = target;
        ComicTranslationSkill.Config draft = drafts.remove(key(source, target));
        load(draft == null ? saved : draft);
        baseline = saved;
    }

    private void load(ComicTranslationSkill.Config config) {
        baseline = config;
        glossary.setText(config.glossary);
        glossary.setError(null);
        glossary.setHint("en".equals(source) && "ckb".equals(target)
                ? R.string.comic_glossary_example : R.string.comic_glossary_generic_example);
        genre.setSelection(config.genre.ordinal());
        pair.setText(source + " → " + target);
        summary.setText(BuildConfig.PUBLIC_EDITION || config.enabled
                ? R.string.auto_skill_ready : R.string.comic_disabled_summary);
    }

    private ComicTranslationSkill.Config current() {
        int selected = Math.max(0, genre.getSelectedItemPosition());
        return new ComicTranslationSkill.Config(BuildConfig.PUBLIC_EDITION || baseline == null || baseline.enabled,
                ComicTranslationSkill.Genre.values()[selected], glossary.getText().toString());
    }

    /** No screen transition or Save step. Called before the existing account gate. */
    boolean validate() {
        try {
            ComicTranslationSkill.validateGlossary(glossary.getText().toString());
            glossary.setError(null);
            return true;
        } catch (ComicTranslationSkill.GlossaryException invalid) {
            panel.setVisibility(View.VISIBLE);
            glossary.setError(context.getString(R.string.comic_glossary_error, invalid.line));
            glossary.requestFocus();
            return false;
        }
    }

    ComicTranslationSkill.Config forTranslation() {
        if (source == null || !validate()) return null;
        ComicTranslationSkill.Config config = current();
        // Only Translate applies this explicit draft to future jobs of this pair.
        // Frozen saved projects are never changed by this form.
        ComicSettings.save(context, source, target, config);
        baseline = config;
        drafts.remove(key(source, target));
        return config;
    }

    void useStory(String source,String target,ComicTranslationSkill.Config config) {
        ComicTranslationSkill.Config selected = new ComicTranslationSkill.Config(true,config.genre,config.glossary);
        ComicSettings.save(context,source,target,selected);
        drafts.remove(key(source,target)); this.source=source; this.target=target; load(selected);
    }

    void saveState(Bundle out) {
        if (source == null) return;
        ComicTranslationSkill.Config config = current();
        if (config.glossary.length() > 12000) return;
        Bundle saved = new Bundle();
        saved.putString("source", source); saved.putString("target", target);
        saved.putString("glossary", config.glossary); saved.putInt("genre", config.genre.ordinal());
        saved.putBoolean("expanded", panel.getVisibility() == View.VISIBLE);
        out.putBundle(STATE, saved);
    }

    void setEnabled(boolean enabled) {
        toggle.setEnabled(enabled); reset.setEnabled(enabled);
        glossary.setEnabled(enabled); genre.setEnabled(enabled);
    }

    private static String key(String source, String target) { return source + "\n" + target; }
    private static boolean sameConfig(ComicTranslationSkill.Config a, ComicTranslationSkill.Config b) {
        return a != null && b != null && a.enabled == b.enabled && a.genre == b.genre && a.glossary.equals(b.glossary);
    }
}
