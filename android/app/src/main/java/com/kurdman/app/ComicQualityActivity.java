package com.kurdman.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;


import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Reader-facing skill, inside normal Koorix. All analysis/corrections are local. */
public final class ComicQualityActivity extends AppCompatActivity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Future<?> scanning;
    private int generation,pageIndex,filterIndex;
    private boolean binding,saving;
    private TranslationProject project;
    private ComicQualityReview.Scan scan;
    private final List<ComicQualityReview.Item> visible=new ArrayList<>();
    private final Rows rows=new Rows();
    private TextView stats,empty;
    private Spinner pageSelector,filter;
    private Button setup,export;
    private ListView list;
    private ActivityResultLauncher<Intent> editor,settings;

    @Override protected void attachBaseContext(Context base){
        if(!BuildConfig.PUBLIC_EDITION){
            Configuration config=new Configuration(base.getResources().getConfiguration());
            Locale sorani=new Locale("ckb");config.setLocale(sorani);config.setLayoutDirection(sorani);
            base=base.createConfigurationContext(config);
        }
        super.attachBaseContext(base);
    }
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);setContentView(R.layout.activity_comic_quality);
        KoorixDesign.apply(this,findViewById(R.id.qualityRoot));
        KoorixDesign.icon(findViewById(R.id.qualityBack),R.drawable.ic_ui_back,R.string.comic_back);
        stats=findViewById(R.id.qualityStats);empty=findViewById(R.id.qualityEmpty);
        pageSelector=findViewById(R.id.qualityPage);filter=findViewById(R.id.qualityFilter);
        setup=findViewById(R.id.qualitySettings);export=findViewById(R.id.qualityExport);
        list=findViewById(R.id.qualityList);list.setEmptyView(empty);list.setAdapter(rows);
        if(state!=null){pageIndex=state.getInt("page",0);filterIndex=state.getInt("filter",0);}
        editor=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
            if(result.getResultCode()==Activity.RESULT_OK&&result.getData()!=null
                    &&result.getData().getBooleanExtra(EditorActivity.EXTRA_EXPORT,false)){
                setResult(Activity.RESULT_OK,new Intent().putExtra(EditorActivity.EXTRA_EXPORT,true));finish();
            }
        });
        settings=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{ });
        findViewById(R.id.qualityBack).setOnClickListener(v->closeWhenSaved());
        getOnBackPressedDispatcher().addCallback(this,new androidx.activity.OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){closeWhenSaved();}
        });
        findViewById(R.id.qualityGuide).setOnClickListener(v->new AlertDialog.Builder(this)
                .setTitle(R.string.q_guide_button).setMessage(R.string.q_guide).setPositiveButton(R.string.q_done,null).show());
        setup.setOnClickListener(v->{
            if(saving||JobRuntime.isRunning())return;
            Intent intent=new Intent(this,ComicSettingsActivity.class);
            if(project!=null)intent.putExtra(ComicSettingsActivity.EXTRA_PROJECT,true);
            else intent.putExtra("source",getIntent().getStringExtra("source"))
                    .putExtra("target",getIntent().getStringExtra("target"));
            settings.launch(intent);
        });
        export.setOnClickListener(v->openEditor(pageIndex,0));
        adapter(filter,new String[]{getString(R.string.q_all),getString(R.string.q_hints),getString(R.string.q_not_reviewed)});
        filter.setSelection(Math.max(0,Math.min(2,filterIndex)));
        pageSelector.setOnItemSelectedListener(new Selection(){@Override public void chosen(int index){if(!binding){pageIndex=index;renderRows();}}});
        filter.setOnItemSelectedListener(new Selection(){@Override public void chosen(int index){if(!binding){filterIndex=index;renderRows();}}});
        list.setOnItemClickListener((parent,view,position,id)->{if(!saving&&position<visible.size())showBlock(visible.get(position));});
    }
    @Override protected void onResume(){super.onResume();refresh();}
    @Override protected void onPause(){generation++;if(scanning!=null)scanning.cancel(true);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("page",pageIndex);out.putInt("filter",filterIndex);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){generation++;if(scanning!=null)scanning.cancel(true);worker.shutdown();super.onDestroy();}

    private boolean writable(){
        return !saving&&!JobRuntime.isRunning()&&project!=null&&project==TranslationProjectStore.load(this)&&project.completed;
    }
    private void refresh(){
        int token=++generation;if(scanning!=null)scanning.cancel(true);
        visible.clear();rows.notifyDataSetChanged();scan=null;
        project=JobRuntime.isRunning()?null:TranslationProjectStore.load(this);
        String sourcePair=project==null?getIntent().getStringExtra("source"):project.sourceLanguage;
        String targetPair=project==null?getIntent().getStringExtra("target"):project.targetLanguage;
        ((TextView)findViewById(R.id.qualityPair)).setText((sourcePair==null?"en":sourcePair)+" → "+(targetPair==null?"ckb":targetPair));
        if(project==null||!project.completed||project.pages.isEmpty()){
            project=null;stats.setText(R.string.q_no_project);empty.setText(R.string.q_no_project);
            findViewById(R.id.qualitySelectors).setVisibility(View.GONE);export.setVisibility(View.GONE);
            setup.setText(R.string.q_settings);return;
        }
        setup.setText(R.string.q_project_settings);export.setVisibility(View.VISIBLE);
        findViewById(R.id.qualitySelectors).setVisibility(View.VISIBLE);
        stats.setText(R.string.q_scanning);empty.setText(R.string.q_scanning);
        binding=true;
        List<String> labels=new ArrayList<>();
        for(int i=0;i<project.pages.size();i++)labels.add(getString(R.string.q_page,i+1,project.pages.size()));
        adapter(pageSelector,labels.toArray(new String[0]));
        pageIndex=Math.max(0,Math.min(pageIndex,project.pages.size()-1));pageSelector.setSelection(pageIndex);
        binding=false;
        final TranslationProject selected=project;
        scanning=worker.submit(()->{
            try{
                ComicQualityReview.Scan result=ComicQualityReview.scan(selected);
                runOnUiThread(()->{
                    if(token!=generation||isFinishing()||isDestroyed())return;
                    scan=result;stats.setText(getString(R.string.q_stats,result.segments,result.hints,result.checked,result.unreviewed));
                    empty.setText(R.string.q_empty_filter);renderRows();
                });
            }catch(java.util.concurrent.CancellationException stopped){/* local scan cancelled on navigation */}
            catch(Exception invalid){runOnUiThread(()->{
                if(token==generation&&!isDestroyed()){stats.setText(R.string.q_read_error);empty.setText(R.string.q_read_error);}
            });}
        });
    }
    private void renderRows(){
        visible.clear();
        if(scan!=null&&pageIndex>=0&&pageIndex<scan.pages.size())for(ComicQualityReview.Item item:scan.pages.get(pageIndex)){
            if(filterIndex==1&&item.codes.isEmpty()&&!ComicQualityReview.REVISE.equals(item.decision)&&!ComicQualityReview.UNCERTAIN.equals(item.decision))continue;
            if(filterIndex==2&&!ComicQualityReview.UNREVIEWED.equals(item.decision))continue;
            visible.add(item);
        }
        rows.notifyDataSetChanged();
    }
    private void openEditor(int page,int segment){
        if(!writable())return;
        editor.launch(new Intent(this,EditorActivity.class).putExtra(EditorActivity.EXTRA_PAGE,page)
                .putExtra(EditorActivity.EXTRA_SEGMENT,segment));
    }
    private Segment segment(ComicQualityReview.Item item){return project.pages.get(item.page).segments.get(item.segment);}
    private void showBlock(ComicQualityReview.Item item){
        if(!writable())return;
        Segment current=segment(item);
        LinearLayout body=column();
        label(body,getString(R.string.q_source),true);text(body,current.text,true);
        String hints=ComicSettings.warnings(this,ComicQualityReview.codes(project,item.page,item.segment));
        label(body,hints.isEmpty()?getString(R.string.q_no_hints):hints,false);
        label(body,getString(R.string.q_target),true);
        EditText target=input(body,10000,false);target.setId(R.id.qualityTargetInput);target.setText(current.ckb);
        label(body,getString(R.string.q_decision),true);
        Spinner decision=new Spinner(this);decision.setId(R.id.qualityDecision);adapter(decision,decisionLabels());body.addView(decision,match());
        String[] decisions=decisions();String initial=ComicQualityReview.decision(project,item.page,item.segment);
        for(int i=0;i<decisions.length;i++)if(decisions[i].equals(initial))decision.setSelection(i);
        target.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){decision.setSelection(0);}
            public void afterTextChanged(android.text.Editable value){}
        });
        Button remember=button(body,R.string.q_remember);remember.setId(R.id.qualityRemember);remember.setOnClickListener(v->{
            String before=segment(item).ckb;
            showRemember(item,target.getText().toString(),()->{
                if(!java.util.Objects.equals(before,segment(item).ckb))target.setText(segment(item).ckb);
                decision.setSelection(0);
            });
        });
        Button onPage=button(body,R.string.q_editor);onPage.setId(R.id.qualityOnPage);
        label(body,getString(R.string.q_context),true);
        List<Segment> page=project.pages.get(item.page).segments;
        for(int index=Math.max(0,item.segment-1);index<=Math.min(page.size()-1,item.segment+1);index++){
            if(index==item.segment)continue;
            Segment neighbour=page.get(index);text(body,neighbour.text+"\n→ "+neighbour.ckb,true);
        }
        label(body,getString(R.string.q_limits),false);
        ScrollView scroll=new ScrollView(this);scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(getString(R.string.q_block,item.page+1,item.segment+1))
                .setView(scroll).setNegativeButton(R.string.q_cancel,null).setPositiveButton(R.string.q_save,null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->
                commitTarget(item,target,decisions[decision.getSelectedItemPosition()],dialog,null)));
        onPage.setOnClickListener(v->commitTarget(item,target,decisions[decision.getSelectedItemPosition()],dialog,
                ()->openEditor(item.page,item.segment)));
        dialog.show();
    }
    private void commitTarget(ComicQualityReview.Item item,EditText input,String decision,AlertDialog dialog,Runnable after){
        if(!writable())return;
        generation++;if(scanning!=null)scanning.cancel(true);
        String previous=project.snapshotJson();if(previous==null)return;Segment current=segment(item);
        String target=input.getText().toString().trim();
        if(!target.equals(current.ckb)){current.ckb=target;ComicQualityReview.edited(current);}
        try{ComicQualityReview.record(project,item.page,item.segment,decision);}
        catch(IllegalArgumentException blocked){rollback(previous);input.setError(getString(R.string.q_review_blocked));return;}
        persist(previous,()->{dialog.dismiss();if(after!=null)after.run();});
    }
    private void showRemember(ComicQualityReview.Item item,String candidate,Runnable onSaved){
        if(!writable())return;
        LinearLayout body=column();label(body,getString(R.string.q_name_source),true);
        EditText source=input(body,80,true);source.setId(R.id.qualityMemorySource);String original=ComicTranslationSkill.clean(segment(item).text);
        if(original!=null&&original.length()<=80)source.setText(original);
        label(body,getString(R.string.q_name_target),true);EditText target=input(body,160,true);target.setId(R.id.qualityMemoryTarget);
        String normalized=ComicTranslationSkill.clean(candidate);
        if(normalized.length()<=160)target.setText(normalized);
        CheckBox exact=new CheckBox(this);exact.setId(R.id.qualityMemoryExact);exact.setText(R.string.q_apply_exact);exact.setTextColor(getColor(R.color.text_primary));body.addView(exact);
        CheckBox future=new CheckBox(this);future.setId(R.id.qualityMemoryFuture);future.setText(R.string.q_future);future.setTextColor(Color.WHITE);body.addView(future);
        label(body,getString(R.string.q_memory_help),false);
        ScrollView scroll=new ScrollView(this);scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(R.string.q_remember).setView(scroll)
                .setNegativeButton(R.string.q_cancel,null).setPositiveButton(R.string.q_save,null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!writable())return;
            String old=project.snapshotJson();if(old==null)return;
            try{
                String from=source.getText().toString(),to=target.getText().toString();
                final String sourceLanguage=project.sourceLanguage,targetLanguage=project.targetLanguage;
                ComicTranslationSkill.Config preset=ComicSettings.read(this,project.sourceLanguage,project.targetLanguage);
                String futureGlossary=future.isChecked()?ComicQualityChecks.upsertGlossary(preset.glossary,from,to):null;
                generation++;if(scanning!=null)scanning.cancel(true);
                int changed=ComicQualityReview.remember(project,from,to,exact.isChecked());
                persist(old,()->{
                    if(futureGlossary!=null)ComicSettings.save(this,sourceLanguage,targetLanguage,
                            new ComicTranslationSkill.Config(preset.enabled,preset.genre,futureGlossary));
                    dialog.dismiss();onSaved.run();Toast.makeText(this,getString(R.string.q_memory_saved,changed),Toast.LENGTH_LONG).show();
                });
            }catch(ComicTranslationSkill.GlossaryException invalid){rollback(old);source.setError(getString(R.string.comic_glossary_error,invalid.line));}
        }));dialog.show();
    }
    /** Complete write before navigating to the editor, so stale saves cannot race it. */
    private void persist(String previous,Runnable after){
        saving=true;list.setEnabled(false);setup.setEnabled(false);export.setEnabled(false);
        final String snapshot=project.snapshotJson();final TranslationProject expected=project;
        worker.execute(()->{
            boolean success;
            try{if(snapshot==null)throw new IllegalStateException();TranslationProjectStore.saveSnapshot(expected,snapshot);success=true;}
            catch(Exception failure){success=false;}
            final boolean saved=success;
            runOnUiThread(()->{
                saving=false;if(!saved&&project==expected)rollback(previous);
                if(isDestroyed())return;
                list.setEnabled(true);setup.setEnabled(true);export.setEnabled(true);
                if(saved){Toast.makeText(this,R.string.q_saved,Toast.LENGTH_SHORT).show();if(after!=null)after.run();}
                else Toast.makeText(this,R.string.q_save_error,Toast.LENGTH_LONG).show();
                if(!isFinishing())refresh();
            });
        });
    }
    private void closeWhenSaved(){if(saving)Toast.makeText(this,R.string.q_saving,Toast.LENGTH_SHORT).show();else finish();}
    private void rollback(String previous){try{project.restoreSnapshot(previous);}catch(Exception ignored){/* fixed UI error only; never log reader text */}}
    private String[] decisions(){return new String[]{ComicQualityReview.UNREVIEWED,ComicQualityReview.CHECKED,ComicQualityReview.REVISE,ComicQualityReview.UNCERTAIN};}
    private String[] decisionLabels(){return new String[]{getString(R.string.q_not_reviewed),getString(R.string.q_checked),getString(R.string.q_revise),getString(R.string.q_uncertain)};}
    private String decisionLabel(String decision){String[] codes=decisions();for(int i=0;i<codes.length;i++)if(codes[i].equals(decision))return decisionLabels()[i];return getString(R.string.q_not_reviewed);}
    private void adapter(Spinner spinner,String[] values){ArrayAdapter<String> a=new ArrayAdapter<>(this,R.layout.spinner_item,values);a.setDropDownViewResource(R.layout.spinner_dropdown_item);spinner.setAdapter(a);}
    private LinearLayout column(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(dp(18),dp(10),dp(18),dp(16));return layout;}
    private LinearLayout.LayoutParams match(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);return p;}
    private TextView label(LinearLayout parent,String value,boolean bold){TextView t=text(parent,value,false);t.setTextSize(bold?14:12);if(bold)t.setTypeface(null,android.graphics.Typeface.BOLD);else t.setTextColor(getColor(R.color.text_secondary));return t;}
    private TextView text(LinearLayout parent,String value,boolean selectable){TextView t=new TextView(this);t.setText(value);t.setTextSize(15);t.setTextColor(getColor(R.color.text_primary));t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);t.setTextIsSelectable(selectable);t.setTypeface(KoorixDesign.font(this,false));t.setIncludeFontPadding(true);parent.addView(t,match());return t;}
    private EditText input(LinearLayout parent,int max,boolean single){EditText t=new EditText(this);t.setTextColor(getColor(R.color.text_primary));t.setBackgroundResource(R.drawable.bg_input);t.setPadding(dp(12),dp(10),dp(12),dp(10));t.setTextSize(15);t.setSingleLine(single);t.setMinLines(single?1:3);t.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS|(single?0:InputType.TYPE_TEXT_FLAG_MULTI_LINE));t.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});t.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);t.setTypeface(KoorixDesign.font(this,false));t.setIncludeFontPadding(true);parent.addView(t,match());return t;}
    private Button button(LinearLayout parent,int text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(getColor(R.color.text_primary));b.setTextSize(13);b.setBackgroundResource(R.drawable.bg_button_secondary);LinearLayout.LayoutParams p=match();p.height=LinearLayout.LayoutParams.WRAP_CONTENT;b.setMinHeight(dp(48));b.setPadding(dp(16),dp(12),dp(16),dp(12));KoorixDesign.primary(b,false);b.setTypeface(KoorixDesign.font(this,true));parent.addView(b,p);return b;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private abstract static class Selection implements AdapterView.OnItemSelectedListener{public void onItemSelected(AdapterView<?> p,View v,int i,long id){chosen(i);}public void onNothingSelected(AdapterView<?> p){}abstract void chosen(int i);}

    private final class Rows extends BaseAdapter{
        public int getCount(){return visible.size();}public Object getItem(int position){return visible.get(position);}
        public long getItemId(int position){ComicQualityReview.Item item=visible.get(position);return ((long)item.page<<32)|item.segment;}
        public View getView(int position,View recycled,ViewGroup parent){
            LinearLayout card;TextView title,source,target,hints;
            if(recycled instanceof LinearLayout){card=(LinearLayout)recycled;title=(TextView)card.getChildAt(0);source=(TextView)card.getChildAt(1);target=(TextView)card.getChildAt(2);hints=(TextView)card.getChildAt(3);}
            else{card=column();card.setBackgroundResource(R.drawable.bg_card);title=label(card,"",true);source=text(card,"",false);target=text(card,"",false);hints=label(card,"",false);source.setMaxLines(2);target.setMaxLines(2);source.setEllipsize(TextUtils.TruncateAt.END);target.setEllipsize(TextUtils.TruncateAt.END);hints.setMaxLines(3);hints.setEllipsize(TextUtils.TruncateAt.END);card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);}
            ComicQualityReview.Item item=visible.get(position);Segment segment=segment(item);
            title.setText(getString(R.string.q_block,item.page+1,item.segment+1)+" • "+decisionLabel(item.decision));
            source.setText(getString(R.string.q_source)+": "+segment.text);source.setTextColor(getColor(R.color.text_secondary));
            target.setText("→ "+segment.ckb);String warning=ComicSettings.warnings(ComicQualityActivity.this,item.codes);
            hints.setText(warning.isEmpty()?getString(R.string.q_no_hints):warning);hints.setTextColor(getColor(warning.isEmpty()?R.color.text_secondary:R.color.warning));
            return card;
        }
    }
}
