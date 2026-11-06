package com.kurdman.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional local project management, not a required translation/setup screen. */
public class ProjectLibraryActivity extends AppCompatActivity {
    static final String EXTRA_RESUME="resume_saved_project", EXTRA_CHANGED="library_project_changed", EXTRA_STORY="use_saved_story";
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private LinearLayout root,list;
    private TextView message;
    private ActivityResultLauncher<String> backup;
    private ActivityResultLauncher<String[]> restore;
    private ActivityResultLauncher<Intent> editor;
    private String backupId="";
    private boolean busy;
    interface Task { void run() throws Exception; }
    @Override protected void onCreate(@Nullable Bundle saved){
        super.onCreate(saved);if(saved!=null)backupId=saved.getString("backup_id","");
        backup=registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"),uri->{if(uri!=null)task(()->{TranslationProject p=TranslationProjectStore.load(this,backupId);if(p==null)throw new IOException("Project missing");try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("Output missing");ProjectArchive.exportProject(p,out);}});});
        restore=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{if(uri!=null)task(()->{File temp=File.createTempFile("koorix-import-",".zip",getCacheDir());try{try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temp)){if(in==null)throw new IOException("Input missing");ProjectArchive.copy(in,out,ProjectArchive.MAX_BYTES);}ProjectArchive.importProject(this,temp);setResult(RESULT_OK,new Intent().putExtra(EXTRA_CHANGED,true));}finally{temp.delete();}});});
        editor=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{if(result.getResultCode()==RESULT_OK&&result.getData()!=null&&result.getData().getBooleanExtra(EditorActivity.EXTRA_EXPORT,false)){setResult(RESULT_OK,new Intent().putExtra(EditorActivity.EXTRA_EXPORT,true).putExtra(EXTRA_CHANGED,true));finish();}else refresh();});
        root=KoorixUi.page(this,getString(R.string.rel_library),getString(R.string.rel_library_sub));
        KoorixUi.button(this,root,getString(R.string.rel_import),()->{if(!JobRuntime.isRunning())restore.launch(new String[]{"application/zip","application/octet-stream"});});
        message=KoorixUi.text(this,root,"",14,false);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);root.addView(list);
        KoorixUi.button(this,root,getString(R.string.rel_back),this::finish);refresh();
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putString("backup_id",backupId);super.onSaveInstanceState(state);}
    private void refresh(){if(busy)return;list.removeAllViews();List<TranslationProject> projects=TranslationProjectStore.list(this);
        if(projects.isEmpty()){KoorixUi.text(this,list,getString(R.string.rel_library_empty),15,false);return;}
        for(TranslationProject p:projects){LinearLayout card=KoorixUi.card(this,list);
            KoorixUi.text(this,card,p.displayName.isEmpty()?p.baseName:p.displayName,18,true);
            KoorixUi.text(this,card,getString(R.string.rel_page_count,p.pages.size(),p.sourceLanguage,p.targetLanguage),13,false);
            KoorixUi.text(this,card,getString(p.completed?R.string.rel_finished:R.string.rel_paused),13,false);
            if(p.completed)KoorixUi.button(this,card,getString(R.string.rel_open_studio),()->select(p,false));
            else if(p.resumable)KoorixUi.button(this,card,getString(R.string.rel_resume),()->select(p,true));
            KoorixUi.button(this,card,getString(R.string.rel_backup),()->new AlertDialog.Builder(this).setTitle(R.string.rel_backup).setMessage(R.string.rel_backup_notice).setNegativeButton(R.string.rel_cancel,null).setPositiveButton(R.string.rel_save,(d,w)->{backupId=p.projectDir.getName();backup.launch(p.baseName+".koorix.zip");}).show());
            KoorixUi.button(this,card,getString(R.string.rel_reuse_story),()->{if(JobRuntime.isRunning())return;setResult(RESULT_OK,new Intent().putExtra(EXTRA_STORY,p.projectDir.getName()));finish();});
            KoorixUi.button(this,card,getString(R.string.rel_rename),()->rename(p));
            KoorixUi.button(this,card,getString(R.string.rel_delete),()->new AlertDialog.Builder(this).setTitle(R.string.rel_delete).setMessage(R.string.rel_delete_project).setNegativeButton(R.string.rel_cancel,null).setPositiveButton(R.string.rel_delete,(d,w)->task(()->TranslationProjectStore.delete(this,p))).show());
        }
    }
    private void select(TranslationProject p,boolean resume){if(JobRuntime.isRunning()){message.setText(R.string.rel_busy);return;}try{TranslationProjectStore.activate(this,p);if(resume){setResult(RESULT_OK,new Intent().putExtra(EXTRA_RESUME,p.projectDir.getName()).putExtra(EXTRA_CHANGED,true));finish();}else{setResult(RESULT_OK,new Intent().putExtra(EXTRA_CHANGED,true));editor.launch(new Intent(this,EditorActivity.class));}}catch(Exception failure){message.setText(R.string.rel_failed);}}
    private void rename(TranslationProject p){EditText name=new EditText(this);name.setText(p.displayName);new AlertDialog.Builder(this).setTitle(R.string.rel_rename).setView(name).setNegativeButton(R.string.rel_cancel,null).setPositiveButton(R.string.rel_save,(d,w)->{String value=name.getText().toString().trim();if(!value.isEmpty()&&value.length()<=120)task(()->{p.displayName=value;p.save();});}).show();}
    private void task(Task action){if(busy||!JobRuntime.tryBeginJob()){message.setText(R.string.rel_busy);return;}JobRuntime.init(getApplicationContext());KeepAliveService.startForJob(this);busy=true;KoorixUi.enabled(root,false);message.setText(R.string.rel_working);
        IO.execute(()->{JobRuntime.markThreadStarted();boolean ok=false;try{action.run();setResult(RESULT_OK,new Intent().putExtra(EXTRA_CHANGED,true));ok=true;}catch(Throwable failure){DiagnosticLog.error(getApplicationContext(),"project_library",failure,"Library operation failed; existing projects retained.");}finally{JobRuntime.markThreadFinished();JobRuntime.endJob();KeepAliveService.jobFinished(getApplicationContext());}
            final boolean done=ok;runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;KoorixUi.enabled(root,true);message.setText(done?R.string.rel_saved:R.string.rel_failed);refresh();});});}
}
