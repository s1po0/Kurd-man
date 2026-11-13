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
    private Button previous,next,toggle;
    private ScrollView scroll;
    private Bitmap displayed;
    private int page;
    private boolean original;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {project=TranslationProjectStore.load(this,getIntent().getStringExtra(EXTRA_PROJECT));}
        catch(Exception invalid){finish();return;}
        if(project==null||!project.privateResults||!project.completed||project.pages.isEmpty()){finish();return;}
        if(state!=null){page=Math.max(0,Math.min(state.getInt("page"),project.pages.size()-1));original=state.getBoolean("original");}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(getColor(R.color.page));
        LinearLayout navigation=new LinearLayout(this);previous=button(R.string.web_previous);next=button(R.string.web_next);label=new TextView(this);label.setTextColor(getColor(R.color.text_primary));label.setGravity(android.view.Gravity.CENTER);label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        navigation.addView(previous);navigation.addView(label,new LinearLayout.LayoutParams(0,-2,1));navigation.addView(next);root.addView(navigation);
        scroll=new ScrollView(this);image=new ImageView(this);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription(getString(R.string.ux_result_preview));scroll.addView(image,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);toggle=button(R.string.web_original);Button export=button(R.string.web_save);actions.addView(toggle,new LinearLayout.LayoutParams(0,-2,1));actions.addView(export,new LinearLayout.LayoutParams(0,-2,1));root.addView(actions);setContentView(root);
        previous.setOnClickListener(v->{if(page>0){page--;show();}});next.setOnClickListener(v->{if(page+1<project.pages.size()){page++;show();}});
        toggle.setOnClickListener(v->{original=!original;show();});export.setOnClickListener(v->{setResult(RESULT_OK,new Intent().putExtra(EditorActivity.EXTRA_EXPORT,true));finish();});show();
    }
    private Button button(int text){Button b=new Button(this);b.setText(text);b.setMinHeight(KoorixDesign.dp(this,48));return b;}
    private void show(){File file=original?project.pages.get(page).sourceFile(project.projectDir):SavedJobPipeline.renderedPage(project,page);
        Bitmap nextImage=BitmapIo.decodeFile(file);Bitmap old=displayed;displayed=nextImage;image.setImageBitmap(nextImage);if(old!=null&&!old.isRecycled())old.recycle();
        label.setText(getString(R.string.web_page,page+1,project.pages.size()));toggle.setText(original?R.string.web_translated:R.string.web_original);previous.setEnabled(page>0);next.setEnabled(page+1<project.pages.size());scroll.post(()->scroll.scrollTo(0,0));}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("page",page);out.putBoolean("original",original);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){if(image!=null)image.setImageDrawable(null);if(displayed!=null&&!displayed.isRecycled())displayed.recycle();super.onDestroy();}
}
