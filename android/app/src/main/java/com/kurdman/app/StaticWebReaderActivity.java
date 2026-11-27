package com.kurdman.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Candidate static reader: no WebView, scripts, cookie jar or automatic page
 * resource requests. It is deliberately not a general JavaScript browser.
 * Transport and lifecycle still require device/security review before release.
 */
public class StaticWebReaderActivity extends AppCompatActivity {
    static final String EXTRA_START_URL="koorix.static.start_url";
    private static final long PAGE_DEADLINE_MS=120000, IMPORT_DEADLINE_MS=30L*60*1000;
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),r->{Thread t=new Thread(r,"koorix-static-web");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final List<StaticWebPage> history=new ArrayList<>();
    private final List<StaticWebPage.Link> links=new ArrayList<>();
    private EditText address;private TextView status,summary;private Button go,back,forward,translate,stop;private BaseAdapter adapter;
    private StaticWebPage page;private PinnedHttpsClient.Cancellation cancellation;private Runnable deadline;
    private int generation,position=-1;private boolean busy;private boolean[] selected=new boolean[0];
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(getColor(R.color.page));
        int p=KoorixDesign.dp(this,12);root.setPadding(p,p,p,p);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
        back=button(R.string.web_back);KoorixDesign.icon(back,R.drawable.ic_ui_back,R.string.web_back);back.setOnClickListener(v->move(-1));bar.addView(back,new LinearLayout.LayoutParams(KoorixDesign.dp(this,48),KoorixDesign.dp(this,48)));
        forward=button(R.string.web_forward);KoorixDesign.icon(forward,R.drawable.ic_ui_chevron_next,R.string.web_forward);forward.setOnClickListener(v->move(1));bar.addView(forward,new LinearLayout.LayoutParams(KoorixDesign.dp(this,48),KoorixDesign.dp(this,48)));
        address=new EditText(this);address.setId(R.id.webReaderAddress);address.setSingleLine(true);address.setHint(R.string.web_address);address.setTextDirection(View.TEXT_DIRECTION_LTR);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setOnEditorActionListener((v,id,event)->{if(id==EditorInfo.IME_ACTION_GO){open();return true;}return false;});bar.addView(address,new LinearLayout.LayoutParams(0,-2,1));
        go=button(R.string.web_go);go.setId(R.id.webReaderGo);KoorixDesign.primary(go,true);go.setOnClickListener(v->open());bar.addView(go);root.addView(bar);
        TextView note=new TextView(this);note.setText(R.string.static_web_notice);note.setTextColor(getColor(R.color.text_secondary));note.setPadding(0,p,0,p);root.addView(note);
        status=new TextView(this);status.setId(R.id.webReaderStatus);status.setText(R.string.web_hint);status.setTextColor(getColor(R.color.text_secondary));status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(status);
        summary=new TextView(this);summary.setId(R.id.staticWebSummary);summary.setTextColor(getColor(R.color.text_primary));summary.setPadding(0,p,0,p);root.addView(summary);
        ListView list=new ListView(this);list.setId(R.id.staticWebLinks);list.setDividerHeight(KoorixDesign.dp(this,4));
        adapter=new BaseAdapter(){
            public int getCount(){return links.size()+selected.length;}public Object getItem(int i){return i<links.size()?links.get(i):page.images.get(i-links.size());}public long getItemId(int i){return i;}
            public int getViewTypeCount(){return 2;}public int getItemViewType(int i){return i<links.size()?0:1;}
            public View getView(int i,View recycled,ViewGroup parent){
                int pad=KoorixDesign.dp(StaticWebReaderActivity.this,12);
                if(i<links.size()){TextView text=recycled instanceof TextView?(TextView)recycled:new TextView(StaticWebReaderActivity.this);
                    text.setText(links.get(i).label);text.setTextColor(getColor(R.color.text_primary));text.setTextSize(16);text.setTypeface(KoorixDesign.font(StaticWebReaderActivity.this,false));
                    text.setPadding(pad,pad,pad,pad);text.setMinHeight(KoorixDesign.dp(StaticWebReaderActivity.this,48));text.setBackgroundResource(R.drawable.bg_card);return text;}
                int n=i-links.size();LinearLayout row=recycled instanceof LinearLayout?(LinearLayout)recycled:new LinearLayout(StaticWebReaderActivity.this);
                CheckBox check;Button preview;
                if(row.getChildCount()==0){row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(pad,0,pad,0);row.setBackgroundResource(R.drawable.bg_card);
                    check=new CheckBox(StaticWebReaderActivity.this);check.setId(R.id.staticWebImageSelect);row.addView(check,new LinearLayout.LayoutParams(0,-2,1));
                    preview=button(R.string.static_web_preview);preview.setId(R.id.staticWebImagePreview);row.addView(preview);
                }else {check=row.findViewById(R.id.staticWebImageSelect);preview=row.findViewById(R.id.staticWebImagePreview);}
                check.setOnCheckedChangeListener(null);String imageUrl=page.images.get(n);Uri uri=Uri.parse(imageUrl);String host=uri.getHost()==null?"":uri.getHost();String name=uri.getLastPathSegment();
                String label=StaticWebPage.clean(host+(name==null?"":" / "+name),100);check.setText(getString(R.string.static_web_image,n+1,label));check.setTextColor(getColor(R.color.text_primary));check.setTextSize(16);check.setTypeface(KoorixDesign.font(StaticWebReaderActivity.this,false));check.setChecked(selected[n]);check.setEnabled(!busy);
                boolean[] snapshot=selected;check.setOnCheckedChangeListener((b,checked)->{if(selected==snapshot&&n<selected.length&&!busy){selected[n]=checked;updateButtons();}});
                preview.setEnabled(!busy);preview.setContentDescription(getString(R.string.static_web_preview_title,n+1));preview.setOnClickListener(v->{if(selected==snapshot&&!busy)preview(imageUrl,n+1);});return row;}
        };
        list.setAdapter(adapter);list.setOnItemClickListener((parent,view,index,id)->{if(!busy&&index<links.size())load(links.get(index).url);});root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);translate=button(R.string.web_translate);translate.setId(R.id.webReaderTranslate);KoorixDesign.primary(translate,true);translate.setOnClickListener(v->capture());actions.addView(translate,new LinearLayout.LayoutParams(0,-2,1));
        stop=button(R.string.web_stop);stop.setId(R.id.webReaderStop);stop.setOnClickListener(v->cancel(R.string.web_hint));actions.addView(stop);root.addView(actions);setContentView(root);KoorixDesign.apply(this,root);updateButtons();
        String start=getIntent()==null?null:getIntent().getStringExtra(EXTRA_START_URL);
        if(start!=null){try{PinnedHttpsClient.checkedUrl(start);load(start);}catch(IOException bad){status.setText(R.string.web_invalid);}}
    }
    private Button button(int id){Button b=new Button(this,null,0);b.setText(id);b.setTypeface(KoorixDesign.font(this,true));KoorixDesign.primary(b,false);b.setMinHeight(KoorixDesign.dp(this,48));return b;}
    private void updateButtons(){boolean any=false;for(boolean value:selected)any|=value;
        go.setEnabled(!busy);back.setEnabled(!busy&&position>0);forward.setEnabled(!busy&&position>=0&&position<history.size()-1);translate.setEnabled(!busy&&any);stop.setEnabled(busy);}
    private void open(){if(busy)return;String text=address.getText().toString().trim();if(text.isEmpty())return;
        String value=text.startsWith("https://")?text:text.contains("://")?"":!text.contains(" ")&&text.contains(".")?"https://"+text:"https://www.google.com/search?q="+Uri.encode(text);
        try{PinnedHttpsClient.checkedUrl(value);}catch(IOException bad){status.setText(R.string.web_invalid);return;}
        android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(address.getWindowToken(),0);address.clearFocus();load(value);
    }
    StaticWebPage fetchPage(String url,PinnedHttpsClient.Cancellation cancel)throws IOException{return new StaticWebTransport().page(url,cancel);}
    Bitmap fetchPreview(String url,PinnedHttpsClient.Cancellation cancel)throws IOException{return new StaticWebPreview().fetch(url,cancel);}
    StaticWebImport.Result fetchImages(List<String> images,File cache,PinnedHttpsClient.Cancellation cancel,StaticWebImport.Progress progress)throws IOException{return new StaticWebImport().capture(images,cache,cancel,progress);}
    private int begin(int message,long deadlineMillis){
        busy=true;cancellation=new PinnedHttpsClient.Cancellation();int token=++generation;status.setText(message);updateButtons();adapter.notifyDataSetChanged();
        deadline=()->{if(active(token))cancel(R.string.web_error);};handler.postDelayed(deadline,deadlineMillis);return token;
    }
    private boolean active(int token){return busy&&generation==token&&!isFinishing()&&!isDestroyed();}
    private void end(){busy=false;if(deadline!=null)handler.removeCallbacks(deadline);deadline=null;cancellation=null;updateButtons();adapter.notifyDataSetChanged();}
    private void cancel(int message){if(!busy)return;generation++;if(cancellation!=null)cancellation.cancel();end();status.setText(message);}
    private void load(String url){
        if(busy)return;int token=begin(R.string.web_loading,PAGE_DEADLINE_MS);PinnedHttpsClient.Cancellation signal=cancellation;
        try{WORKER.execute(()->{StaticWebPage result=null;try{result=fetchPage(url,signal);}catch(Exception ignored){}final StaticWebPage fetched=result;
            handler.post(()->{if(!active(token))return;end();if(fetched==null){status.setText(R.string.web_error);return;}
                while(history.size()>position+1)history.remove(history.size()-1);history.add(fetched);if(history.size()>50)history.remove(0);position=history.size()-1;showPage(fetched);
            });});}catch(RejectedExecutionException unavailable){end();status.setText(R.string.web_error);}
    }
    private void preview(String url,int number){
        if(busy)return;int token=begin(R.string.web_image_loading,PAGE_DEADLINE_MS);PinnedHttpsClient.Cancellation signal=cancellation;
        try{WORKER.execute(()->{Bitmap result=null;try{result=fetchPreview(url,signal);}catch(Exception ignored){}final Bitmap bitmap=result;
            handler.post(()->{if(!active(token)){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();return;}end();
                if(bitmap==null){status.setText(R.string.web_error);return;}status.setText(R.string.static_web_ready);
                ImageView image=new ImageView(this);image.setImageBitmap(bitmap);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription(getString(R.string.static_web_preview_title,number));
                image.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,KoorixDesign.dp(this,480)));
                AlertDialog dialog=new AlertDialog.Builder(this).setTitle(R.string.static_web_preview).setView(image).setPositiveButton(android.R.string.ok,null).create();
                dialog.setOnDismissListener(d->{image.setImageDrawable(null);if(!bitmap.isRecycled())bitmap.recycle();});dialog.show();
            });});}catch(RejectedExecutionException unavailable){end();status.setText(R.string.web_error);}
    }
    private void showPage(StaticWebPage value){page=value;address.setText(value.url);links.clear();links.addAll(value.links);selected=new boolean[value.images.size()];java.util.Arrays.fill(selected,true);adapter.notifyDataSetChanged();
        summary.setText(getString(R.string.static_web_summary,value.title.isEmpty()?value.url:value.title,value.images.size(),value.links.size()));
        status.setText(value.truncated?R.string.web_capped:value.images.isEmpty()?R.string.static_web_no_images:R.string.static_web_ready);updateButtons();}
    private void move(int delta){if(busy)return;int next=position+delta;if(next>=0&&next<history.size()){position=next;showPage(history.get(position));}}
    private void capture(){
        if(busy||page==null)return;final List<String> images=new ArrayList<>();for(int i=0;i<selected.length;i++)if(selected[i])images.add(page.images.get(i));
        if(images.isEmpty())return;final File cache=getApplicationContext().getCacheDir();
        int token=begin(R.string.web_discover,IMPORT_DEADLINE_MS);PinnedHttpsClient.Cancellation signal=cancellation;
        try{WORKER.execute(()->{StaticWebImport.Result result=null;try{result=fetchImages(images,cache,signal,(current,total)->handler.post(()->{if(active(token))status.setText(getString(R.string.web_capture,current,total));}));}catch(Exception ignored){}
            final StaticWebImport.Result captured=result;handler.post(()->{
                if(!active(token)){discard(captured,cache);return;}end();if(captured==null){status.setText(R.string.static_web_empty);return;}
                ArrayList<String> files=new ArrayList<>();for(File file:captured.files)files.add(file.getAbsolutePath());
                if(captured.failed>0)Toast.makeText(this,getString(R.string.web_partial,captured.failed),Toast.LENGTH_LONG).show();
                setResult(RESULT_OK,new Intent().putStringArrayListExtra(WebReaderActivity.EXTRA_FILES,files)
                        .putExtra(WebReaderActivity.EXTRA_VISIBLE,new boolean[files.size()]));finish();
            });});}catch(RejectedExecutionException unavailable){end();status.setText(R.string.web_error);}
    }
    private static void discard(StaticWebImport.Result result,File cache){if(result!=null)try{result.discard(cache);}catch(IOException ignored){}}
    @Override protected void onDestroy(){cancel(R.string.web_hint);super.onDestroy();}
    @Override public void onBackPressed(){if(busy){cancel(R.string.web_hint);return;}if(position>0){move(-1);return;}super.onBackPressed();}
}
