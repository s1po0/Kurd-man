package com.kurdman.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;

/** One decoded page at a time; source and translated products remain private. */
public final class WebResultActivity extends AppCompatActivity {
    static final String EXTRA_PROJECT="koorix.web.project";
    private TranslationProject project;
    private ImageView image;
    private TextView label;
    private Button previous,next,toggle,export;
    private TextView hint;
    private ScrollView scroll;
    private Bitmap displayed;
    private final java.util.concurrent.ExecutorService decoder=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ExecutorService refresher=java.util.concurrent.Executors.newSingleThreadExecutor();
    private int generation;
    private int refreshGeneration;
    private boolean resumed,refreshPending,refreshAgain;
    private int page;
    private java.util.List<Integer> readyPages;
    private boolean original;
    private final SavedJobCoordinator.Listener jobListener=(changed,status,running)->{
        if(changed!=null&&project!=null&&changed.projectDir!=null
                &&changed.projectDir.equals(project.projectDir)) requestRefresh();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {project=snapshot(TranslationProjectStore.load(this,getIntent().getStringExtra(EXTRA_PROJECT)));}
        catch(Exception invalid){finish();return;}
        if(project==null||!project.privateResults||(readyPages=readyPages(project)).isEmpty()){finish();return;}
        if(state!=null){int restored=readyPages.indexOf(state.getInt("chapterPage",readyPages.get(0)));page=restored>=0?restored:0;original=state.getBoolean("original");}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(getColor(R.color.page));
        LinearLayout navigation=new LinearLayout(this);previous=button(R.string.web_previous);KoorixDesign.icon(previous,R.drawable.ic_ui_back,R.string.web_previous);next=button(R.string.web_next);KoorixDesign.icon(next,R.drawable.ic_ui_chevron_next,R.string.web_next);next.setId(R.id.webResultNext);label=new TextView(this);label.setId(R.id.webResultPageLabel);label.setTextColor(getColor(R.color.text_primary));label.setGravity(android.view.Gravity.CENTER);label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        navigation.addView(previous,new LinearLayout.LayoutParams(KoorixDesign.dp(this,48),-2));navigation.addView(label,new LinearLayout.LayoutParams(0,-2,1));navigation.addView(next,new LinearLayout.LayoutParams(KoorixDesign.dp(this,48),-2));root.addView(navigation);
        scroll=new ScrollView(this);image=new ImageView(this);image.setId(R.id.webResultImage);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription(getString(R.string.ux_result_preview));scroll.addView(image,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        hint=new TextView(this);hint.setText(R.string.web_partial_reader_hint);hint.setTextColor(getColor(R.color.text_secondary));hint.setPadding(12,8,12,8);hint.setVisibility(project.completed?View.GONE:View.VISIBLE);root.addView(hint);
        LinearLayout actions=new LinearLayout(this);toggle=button(R.string.web_original);toggle.setId(R.id.webResultToggle);export=button(R.string.web_save);export.setEnabled(project.completed&&project.exportsComplete&&!JobRuntime.isRunning());KoorixDesign.primary(export,true);export.setId(R.id.webResultExport);actions.addView(toggle,new LinearLayout.LayoutParams(0,-2,1));actions.addView(export,new LinearLayout.LayoutParams(0,-2,1));root.addView(actions);setContentView(root);
        previous.setOnClickListener(v->{if(page>0){page--;show();}});next.setOnClickListener(v->{if(page+1<readyPages.size()){page++;show();}});
        toggle.setOnClickListener(v->{original=!original;show();});export.setOnClickListener(v->{setResult(RESULT_OK,new Intent().putExtra(EditorActivity.EXTRA_EXPORT,true));finish();});show();
    }
    /** Never expose the coordinator's mutable in-memory job to a reader. */
    static TranslationProject snapshot(TranslationProject selected) {
        if(selected==null||selected.projectDir==null)return null;
        // AtomicFile.openRead can recover a backup, so serialize it with the
        // active project's synchronized save rather than racing a write.
        synchronized(selected){try{return TranslationProject.load(selected.projectDir);}catch(java.io.IOException invalid){return null;}}
    }
    /** Only durable rendered pages are eligible; unfinished pages are never exposed. */
    static java.util.List<Integer> readyPages(TranslationProject project) {
        java.util.List<Integer> ready=new java.util.ArrayList<>();
        if(project==null||!project.privateResults||project.projectDir==null)return ready;
        for(int i=0;i<project.pages.size();i++){
            TranslationProject.Page page=project.pages.get(i);File source=page.sourceFile(project.projectDir);
            if(page.renderComplete&&source!=null&&source.isFile()&&SavedJobPipeline.renderedPage(project,i).isFile())ready.add(i);
        }
        return ready;
    }
    private Button button(int text){Button b=new Button(this);b.setText(text);b.setTypeface(KoorixDesign.font(this,true));KoorixDesign.primary(b,false);return b;}
    private void show(){
        final int chapterPage=readyPages.get(page);
        final File file=original?project.pages.get(chapterPage).sourceFile(project.projectDir):SavedJobPipeline.renderedPage(project,chapterPage);
        final int request=++generation;
        Bitmap previousBitmap=displayed;displayed=null;image.setImageDrawable(null);
        if(previousBitmap!=null&&!previousBitmap.isRecycled())previousBitmap.recycle();
        label.setText(getString(R.string.web_page,chapterPage+1,project.pages.size()));toggle.setText(original?R.string.web_translated:R.string.web_original);
        image.setContentDescription(getString(R.string.web_image_loading));
        previous.setEnabled(page>0);next.setEnabled(page+1<readyPages.size());scroll.post(()->scroll.scrollTo(0,0));
        decoder.execute(()->{
            Bitmap loaded=null;try{loaded=BitmapIo.decodeFile(file);}catch(RuntimeException|OutOfMemoryError ignored){/* visible error below */}
            final Bitmap bitmap=loaded;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed()||request!=generation){if(bitmap!=null)bitmap.recycle();return;}
                Bitmap old=displayed;displayed=bitmap;image.setImageBitmap(bitmap);if(old!=null&&!old.isRecycled())old.recycle();
                if(bitmap==null){label.setText(R.string.web_error);image.setContentDescription(getString(R.string.web_error));}
                else image.setContentDescription(getString(original?R.string.web_original:R.string.web_translated));
            });
        });
    }
    @Override protected void onResume(){super.onResume();if(project!=null&&readyPages!=null&&!readyPages.isEmpty()){resumed=true;SavedJobCoordinator.addListener(jobListener);requestRefresh();}}
    @Override protected void onPause(){resumed=false;refreshGeneration++;SavedJobCoordinator.removeListener(jobListener);super.onPause();}
    /** Coalesce progress events; project JSON can be large, so read it off the UI thread. */
    private void requestRefresh(){
        if(!resumed)return;
        if(refreshPending){refreshAgain=true;return;}
        refreshPending=true;final int request=refreshGeneration;final String id=project.projectDir.getName();
        refresher.execute(()->{
            TranslationProject latest=null;
            try{latest=snapshot(TranslationProjectStore.load(getApplicationContext(),id));}catch(Exception ignored){/* keep last durable snapshot */}
            final TranslationProject result=latest;
            runOnUiThread(()->{
                refreshPending=false;
                if(resumed&&request==refreshGeneration&&result!=null&&result.privateResults)applySnapshot(result);
                boolean again=refreshAgain;refreshAgain=false;
                if(resumed&&(again||request!=refreshGeneration))requestRefresh();
            });
        });
    }
    private void applySnapshot(TranslationProject latest){
        java.util.List<Integer> pages=readyPages(latest);if(pages.isEmpty())return;
        int chapterPage=readyPages.get(page);int index=pages.indexOf(chapterPage);
        project=latest;readyPages=pages;page=index>=0?index:0;
        hint.setVisibility(project.completed?View.GONE:View.VISIBLE);
        export.setEnabled(project.completed&&project.exportsComplete&&!JobRuntime.isRunning());
        if(index<0)show();else{previous.setEnabled(page>0);next.setEnabled(page+1<readyPages.size());}
    }
    @Override protected void onSaveInstanceState(Bundle out){if(readyPages!=null&&!readyPages.isEmpty())out.putInt("chapterPage",readyPages.get(page));out.putBoolean("original",original);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){generation++;refresher.shutdownNow();decoder.shutdownNow();if(image!=null)image.setImageDrawable(null);if(displayed!=null&&!displayed.isRecycled())displayed.recycle();super.onDestroy();}
}
