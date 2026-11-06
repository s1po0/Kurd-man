package com.kurdman.app;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/** A separate, small screen, rather than more controls on the long main page. */
public final class ComicSettingsActivity extends AppCompatActivity {
    private String source;
    private String target;
    private EditText glossary;
    private CheckBox enabled;
    private Spinner genre;

    @Override
    protected void attachBaseContext(Context base) {
        if (!BuildConfig.PUBLIC_EDITION) {
            Configuration config = new Configuration(base.getResources().getConfiguration());
            Locale sorani = new Locale("ckb");
            config.setLocale(sorani);
            config.setLayoutDirection(sorani);
            base = base.createConfigurationContext(config);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_comic_settings);
        source = getIntent().getStringExtra("source");
        target = getIntent().getStringExtra("target");
        if (source == null) source = "en";
        if (target == null) target = "ckb";
        ((TextView) findViewById(R.id.comicLanguagePair)).setText(source + " → " + target);
        enabled = findViewById(R.id.comicEnabled);
        genre = findViewById(R.id.comicGenre);
        glossary = findViewById(R.id.comicGlossary);
        ComicTranslationSkill.Config config = ComicSettings.read(this, source, target);
        enabled.setChecked(config.enabled);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item,
                ComicSettings.genreLabels(this));
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        genre.setAdapter(adapter);
        genre.setSelection(config.genre.ordinal());
        glossary.setText(config.glossary);
        if (!"ckb".equals(target) || !"en".equals(source)) {
            glossary.setHint(R.string.comic_glossary_generic_example);
        }
        findViewById(R.id.comicSave).setOnClickListener(v -> save());
        findViewById(R.id.comicBack).setOnClickListener(v -> finish());
        findViewById(R.id.comicClear).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.comic_clear_title)
                .setMessage(R.string.comic_clear_message)
                .setPositiveButton(R.string.comic_clear, (dialog, which) -> glossary.setText(""))
                .setNegativeButton(R.string.ks_0358, null).show());
        findViewById(R.id.comicOtherTargetsNote).setVisibility("ckb".equals(target) ? View.GONE : View.VISIBLE);
    }

    private void save() {
        String text = glossary.getText().toString();
        try {
            ComicTranslationSkill.validateGlossary(text);
        } catch (ComicTranslationSkill.GlossaryException invalid) {
            glossary.setError(getString(R.string.comic_glossary_error, invalid.line));
            glossary.requestFocus();
            return;
        }
        ComicSettings.save(this, source, target, new ComicTranslationSkill.Config(
                enabled.isChecked(), ComicTranslationSkill.Genre.values()[genre.getSelectedItemPosition()], text));
        Toast.makeText(this, R.string.comic_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
