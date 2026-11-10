package com.kurdman.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.*;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local searchable library. Source files, backup and frozen-job contracts are unchanged. */
public class ProjectLibraryActivity extends AppCompatActivity {
    static final String EXTRA_RESUME="resume_saved_project", EXTRA_CHANGED="library_project_changed", EXTRA_STORY="use_saved_story";
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private LinearLayout root,list;
    private TextView message;
    private EditText search;
    private List<TranslationProject> projects=new ArrayList<>();
    private ActivityResultLauncher<String> backup;
    private ActivityResultLauncher<String[]> restore;
    private ActivityResultLauncher<Intent> editor;
    private String backupId="";
    private boolean busy;
    private int generation;
    interface Task { void run() throws Exception; }
    @Override protected void onCreate(@Nullable Bundle saved){
        super.onCreate(saved);if(saved!=null)backupId=saved.getString("backup_id","");
        backup=registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"),uri->{if(uri!=null)task(()->{TranslationProject p=TranslationProjectStore.load(this,backupId);if(p==null)throw new IOException("Project missing");try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("Output missing");ProjectArchive.exportProject(p,out);}});});
        restore=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{if(uri!=null)task(()->{File temp=File.createTempFile("koorix-import-",".zip",getCacheDir());try{try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temp)){if(in==null)throw new IOException("Input missing");ProjectArchive.copy(in,out,ProjectArchive.MAX_BYTES);}ProjectArchive.importProject(this,temp);setResult(RESULT_OK,new Intent().putExtra(EXTRA_CHANGED,true));}finally{temp.delete();}});});
        editor=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{if(result.getResultCode()==RESULT_OK&&result.getData()!=null&&result.getData().getBooleanExtra(EditorActivity.EXTRA_EXPORT,false)){setResult(RESULT_OK,new Intent().putExtra(EditorActivity.EXTRA_EXPORT,true).putExtra(EXTRA_CHANGED,true));finish();}else refresh();});
        root=KoorixUi.page(this,getString(R.string.rel_library),getString(R.string.ux_library_sub));
        search=new EditText(this);search.setId(R.id.librarySearch);search.setSingleLine(true);search.setHint(R.string.ux_library_search);search.setContentDescription(getString(R.string.ux_library_search));
        search.setBackgroundResource(R.drawable.bg_input);search.setTextColor(getColor(R.color.text_primary));search.setHintTextColor(getColor(R.color.text_muted));search.setTextSize(15);search.setTypeface(KoorixDesign.font(this,false));search.setPadding(dp(16),dp(12),dp(16),dp(12));search.setMinHeight(dp(56));
        root.addView(search,new LinearLayout.LayoutParams(-1,-2));
        if(saved!=null)search.setText(saved.getString("library_query",""));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){renderProjects();}public void afterTextChanged(Editable e){}});
        KoorixUi.button(this,root,getString(R.string.rel_import),()->{if(!JobRuntime.isRunning())restore.launch(new String[]{"application/zip","application/octet-stream"});});
        message=KoorixUi.text(this,root,"",14,false);message.setId(R.id.libraryMessage);message.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        list=new LinearLayout(this);list.setId(R.id.libraryProjectList);list.setOrientation(LinearLayout.VERTICAL);root.addView(list);refresh();
    }
    private int dp(int value){return KoorixDesign.dp(this,value);}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("backup_id",backupId);state.putString("library_query",search.getText().toString());super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){generation++;super.onDestroy();}
    private void refresh(){
        if(busy)return;int token=++generation;
        IO.execute(()->{List<TranslationProject> loaded=TranslationProjectStore.list(getApplicationContext());runOnUiThread(()->{
            if(token!=generation||isFinishing()||isDestroyed())return;
            projects=loaded;renderProjects();
        });});
    }
    private void renderProjects(){
        if(list==null)return;list.removeAllViews();
        String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown=0;
        for(TranslationProject p:projects){
            String name=p.displayName.isEmpty()?p.baseName:p.displayName;
            if(!query.isEmpty()&&!name.toLowerCase(Locale.ROOT).contains(query))continue;
            shown++;
            LinearLayout card=KoorixUi.card(this,list);
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);card.addView(row);
            TextView title=new TextView(this);title.setText(name);title.setTextSize(18);title.setTextColor(getColor(R.color.text_primary));title.setTypeface(KoorixDesign.font(this,true));title.setIncludeFontPadding(true);
            row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
            Button menu=new Button(this,null,0);menu.setId(R.id.libraryProjectMenu);menu.setBackgroundResource(R.drawable.bg_nav_item);row.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(48)));
            KoorixDesign.icon(menu,R.drawable.ic_ui_more,R.string.ux_library_menu);menu.setContentDescription(getString(R.string.ux_library_menu)+": "+name);menu.setOnClickListener(v->projectMenu(menu,p));
            KoorixUi.text(this,card,getString(R.string.rel_page_count,p.pages.size(),p.sourceLanguage,p.targetLanguage),13,false);
            TextView status=KoorixUi.text(this,card,getString(p.completed?R.string.rel_finished:R.string.rel_paused),12,false);status.setTextColor(getColor(R.color.primary));
            if(p.completed)KoorixDesign.primary(KoorixUi.button(this,card,getString(R.string.rel_open_studio),()->select(p,false)),true);
            else if(p.resumable)KoorixDesign.primary(KoorixUi.button(this,card,getString(R.string.rel_resume),()->select(p,true)),true);
        }
        if(shown==0)KoorixUi.text(this,list,getString(projects.isEmpty()?R.string.ux_library_empty:R.string.ux_library_no_match),15,false);
    }
    private void projectMenu(Button anchor,TranslationProject p){
        PopupMenu menu=new PopupMenu(this,anchor);
        menu.getMenu().add(0,1,0,R.string.rel_backup);menu.getMenu().add(0,2,1,R.string.rel_reuse_story);menu.getMenu().add(0,3,2,R.string.rel_rename);menu.getMenu().add(0,4,3,R.string.rel_delete);
        menu.setOnMenuItemClickListener(item->{
            if(busy||JobRuntime.isRunning()){message.setText(R.string.rel_busy);return true;}
            switch(item.getItemId()){
                case 1:new AlertDialog.Builder(this).setTitle(R.string.rel_backup).setMessage(R.string.rel_backup_notice).setNegativeButton(R.string.rel_cancel,null).setPositiveButton(R.string.rel_save,(d,w)->{backupId=p.projectDir.getName();backup.launch(p.baseName+".koorix.zip");}).show();break;
                case 2:setResult(RESULT_OK,new Intent().putExtra(EXTRA_STORY,p.projectDir.getName()));finish();break;
                case 3:rename(p);break;
                case 4:new AlertDialog.Builder(this).setTitle(R.string.rel_delete).setMessage(R.string.rel_delete_project).setNegativeButton(R.string.rel_cancel,null).setPositiveButton(R.string.rel_delete,(d,w)->task(()->TranslationProjectStore.delete(this,p))).show();break;
            }
            return true;
        });menu.show();
    }
    private void select(TranslationProject p,boolean resume){if(JobRuntime.isRunning()){message.setText(R.string.rel_busy);return;}try{TranslationProjectStore.activate(this,p);if(resume){setResult(RESULT_OK,new Intent().putExtra(EXTRA_RESUME,p.projectDir.getName()).putExtra(EXTRA_CHANGED,true));finish();}else{setResult(RESULT_OK,new Intent().putExtra(EXTRA_CHANGED,true));editor.launch(new Intent(this,EditorActivity.class));}}catch(Exception failure){message.setText(R.string.rel_failed);}}
    private void rename(TranslationProject p){EditText name=new EditText(this);name.setText(p.displayName);name.setTextColor(getColor(R.color.text_primary));name.setTextSize(16);name.setPadding(dp(20),dp(12),dp(20),dp(12));new AlertDialog.Builder(this).setTitle(R.string.rel_rename).setView(name).setNegativeButton(R.string.rel_cancel,null).setPositiveButton(R.string.rel_save,(d,w)->{String value=name.getText().toString().trim();if(!value.isEmpty()&&value.length()<=120)task(()->{p.displayName=value;p.save();});}).show();}
    private void task(Task action){if(busy||!JobRuntime.tryBeginJob()){message.setText(R.string.rel_busy);return;}JobRuntime.init(getApplicationContext());KeepAliveService.startForJob(this);busy=true;KoorixUi.enabled(root,false);message.setText(R.string.rel_working);
        IO.execute(()->{JobRuntime.markThreadStarted();boolean ok=false;try{action.run();setResult(RESULT_OK,new Intent().putExtra(EXTRA_CHANGED,true));ok=true;}catch(Throwable failure){DiagnosticLog.error(getApplicationContext(),"project_library",failure,"Library operation failed; existing projects retained.");}finally{JobRuntime.markThreadFinished();JobRuntime.endJob();KeepAliveService.jobFinished(getApplicationContext());}
            final boolean done=ok;runOnUiThread(()->{if(isFinishing()||isDestroyed())return;busy=false;KoorixUi.enabled(root,true);message.setText(done?R.string.rel_saved:R.string.rel_failed);refresh();});});}
}
