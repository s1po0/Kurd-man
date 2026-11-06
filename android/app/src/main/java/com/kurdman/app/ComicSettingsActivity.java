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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Separate story setup, either future preset or an explicitly selected saved project. */
public final class ComicSettingsActivity extends AppCompatActivity {
    public static final String EXTRA_PROJECT="saved_project";
    private String source,target;
    private EditText glossary;
    private CheckBox enabled,future;
    private Spinner genre;
    private TranslationProject project;
    private boolean saving;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();

    @Override protected void attachBaseContext(Context base){
        if(!BuildConfig.PUBLIC_EDITION){
            Configuration config=new Configuration(base.getResources().getConfiguration());
            Locale sorani=new Locale("ckb");config.setLocale(sorani);config.setLayoutDirection(sorani);
            base=base.createConfigurationContext(config);
        }
        super.attachBaseContext(base);
    }
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);setContentView(R.layout.activity_comic_settings);
        if(getIntent().getBooleanExtra(EXTRA_PROJECT,false)){
            project=TranslationProjectStore.load(this);
            if(project==null||!project.completed||JobRuntime.isRunning()){finish();return;}
        }
        source=project==null?getIntent().getStringExtra("source"):project.sourceLanguage;
        target=project==null?getIntent().getStringExtra("target"):project.targetLanguage;
        if(source==null)source="en";if(target==null)target="ckb";
        ((TextView)findViewById(R.id.comicLanguagePair)).setText(source+" → "+target);
        enabled=findViewById(R.id.comicEnabled);genre=findViewById(R.id.comicGenre);
        glossary=findViewById(R.id.comicGlossary);future=findViewById(R.id.comicApplyFuture);
        future.setVisibility(project==null?View.GONE:View.VISIBLE);
        findViewById(R.id.comicProjectNote).setVisibility(project==null?View.GONE:View.VISIBLE);
        ComicTranslationSkill.Config config=project==null?ComicSettings.read(this,source,target):project.comicConfig;
        enabled.setChecked(config.enabled);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,R.layout.spinner_item,ComicSettings.genreLabels(this));
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);genre.setAdapter(adapter);genre.setSelection(config.genre.ordinal());
        glossary.setText(config.glossary);
        if(!"ckb".equals(target)||!"en".equals(source))glossary.setHint(R.string.comic_glossary_generic_example);
        findViewById(R.id.comicSave).setOnClickListener(v->save());
        findViewById(R.id.comicBack).setOnClickListener(v->closeWhenSaved());
        getOnBackPressedDispatcher().addCallback(this,new androidx.activity.OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){closeWhenSaved();}
        });
        findViewById(R.id.comicClear).setOnClickListener(v->new AlertDialog.Builder(this)
                .setTitle(R.string.comic_clear_title).setMessage(project==null?R.string.comic_clear_message:R.string.q_project_note)
                .setPositiveButton(R.string.comic_clear,(dialog,which)->glossary.setText(""))
                .setNegativeButton(R.string.ks_0358,null).show());
        findViewById(R.id.comicOtherTargetsNote).setVisibility("ckb".equals(target)?View.GONE:View.VISIBLE);
    }
    private void save(){
        if(saving||JobRuntime.isRunning())return;
        String text=glossary.getText().toString();
        try{ComicTranslationSkill.validateGlossary(text);}
        catch(ComicTranslationSkill.GlossaryException invalid){glossary.setError(getString(R.string.comic_glossary_error,invalid.line));glossary.requestFocus();return;}
        ComicTranslationSkill.Config chosen=new ComicTranslationSkill.Config(enabled.isChecked(),
                ComicTranslationSkill.Genre.values()[genre.getSelectedItemPosition()],text);
        if(project==null){ComicSettings.save(this,source,target,chosen);finishSaved();return;}
        if(project!=TranslationProjectStore.load(this)){finish();return;}
        ComicTranslationSkill.Config old=project.comicConfig;project.comicConfig=chosen;
        final String snapshot=project.snapshotJson();
        final boolean alsoFuture=future.isChecked();saving=true;findViewById(R.id.comicSave).setEnabled(false);
        worker.execute(()->{
            boolean ok;
            try{
                if(snapshot==null)throw new IllegalStateException();
                TranslationProjectStore.saveSnapshot(project,snapshot);
                if(alsoFuture)ComicSettings.save(getApplicationContext(),source,target,chosen);
                ok=true;
            }catch(Exception failure){ok=false;}
            final boolean saved=ok;
            runOnUiThread(()->{
                saving=false;if(!saved)project.comicConfig=old;
                if(isDestroyed())return;
                findViewById(R.id.comicSave).setEnabled(true);
                if(saved)finishSaved();else Toast.makeText(this,R.string.q_save_error,Toast.LENGTH_LONG).show();
            });
        });
    }
    private void closeWhenSaved(){if(saving)Toast.makeText(this,R.string.q_saving,Toast.LENGTH_SHORT).show();else finish();}
    private void finishSaved(){Toast.makeText(this,R.string.comic_saved,Toast.LENGTH_SHORT).show();setResult(RESULT_OK);finish();}
    @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
