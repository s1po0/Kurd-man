package com.kurdman.app;

import android.content.Intent;
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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
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
    private static final long PAGE_DEADLINE_MS=120000, IMPORT_DEADLINE_MS=30L*60*1000;
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),r->{Thread t=new Thread(r,"koorix-static-web");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final List<StaticWebPage> history=new ArrayList<>();
    private final List<StaticWebPage.Link> links=new ArrayList<>();
    private EditText address;private TextView status,summary;private Button go,back,forward,translate,stop;private BaseAdapter adapter;
    private StaticWebPage page;private PinnedHttpsClient.Cancellation cancellation;private Runnable deadline;
    private int generation,position=-1;private boolean busy;
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
            public int getCount(){return links.size();}public Object getItem(int i){return links.get(i);}public long getItemId(int i){return i;}
            public View getView(int i,View recycled,ViewGroup parent){TextView text=recycled instanceof TextView?(TextView)recycled:new TextView(StaticWebReaderActivity.this);
                text.setText(links.get(i).label);text.setTextColor(getColor(R.color.text_primary));text.setTextSize(16);text.setTypeface(KoorixDesign.font(StaticWebReaderActivity.this,false));
                int pad=KoorixDesign.dp(StaticWebReaderActivity.this,12);text.setPadding(pad,pad,pad,pad);text.setMinHeight(KoorixDesign.dp(StaticWebReaderActivity.this,48));text.setBackgroundResource(R.drawable.bg_card);return text;}
        };
        list.setAdapter(adapter);list.setOnItemClickListener((parent,view,index,id)->{if(!busy)load(links.get(index).url);});root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);translate=button(R.string.web_translate);translate.setId(R.id.webReaderTranslate);KoorixDesign.primary(translate,true);translate.setOnClickListener(v->capture());actions.addView(translate,new LinearLayout.LayoutParams(0,-2,1));
        stop=button(R.string.web_stop);stop.setId(R.id.webReaderStop);stop.setOnClickListener(v->cancel(R.string.web_hint));actions.addView(stop);root.addView(actions);setContentView(root);KoorixDesign.apply(this,root);updateButtons();
    }
    private Button button(int id){Button b=new Button(this,null,0);b.setText(id);b.setTypeface(KoorixDesign.font(this,true));KoorixDesign.primary(b,false);b.setMinHeight(KoorixDesign.dp(this,48));return b;}
    private void updateButtons(){go.setEnabled(!busy);back.setEnabled(!busy&&position>0);forward.setEnabled(!busy&&position>=0&&position<history.size()-1);translate.setEnabled(!busy&&page!=null&&!page.images.isEmpty());stop.setEnabled(busy);}
    private void open(){if(busy)return;String text=address.getText().toString().trim();if(text.isEmpty())return;
        String value=text.startsWith("https://")?text:text.contains("://")?"":!text.contains(" ")&&text.contains(".")?"https://"+text:"https://www.google.com/search?q="+Uri.encode(text);
        try{PinnedHttpsClient.checkedUrl(value);}catch(IOException bad){status.setText(R.string.web_invalid);return;}
        android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(address.getWindowToken(),0);address.clearFocus();load(value);
    }
    StaticWebPage fetchPage(String url,PinnedHttpsClient.Cancellation cancel)throws IOException{return new StaticWebTransport().page(url,cancel);}
    StaticWebImport.Result fetchImages(List<String> images,File cache,PinnedHttpsClient.Cancellation cancel,StaticWebImport.Progress progress)throws IOException{return new StaticWebImport().capture(images,cache,cancel,progress);}
    private int begin(int message,long deadlineMillis){
        busy=true;cancellation=new PinnedHttpsClient.Cancellation();int token=++generation;status.setText(message);updateButtons();
        deadline=()->{if(active(token))cancel(R.string.web_error);};handler.postDelayed(deadline,deadlineMillis);return token;
    }
    private boolean active(int token){return busy&&generation==token&&!isFinishing()&&!isDestroyed();}
    private void end(){busy=false;if(deadline!=null)handler.removeCallbacks(deadline);deadline=null;cancellation=null;updateButtons();}
    private void cancel(int message){if(!busy)return;generation++;if(cancellation!=null)cancellation.cancel();end();status.setText(message);}
    private void load(String url){
        if(busy)return;int token=begin(R.string.web_loading,PAGE_DEADLINE_MS);PinnedHttpsClient.Cancellation signal=cancellation;
        try{WORKER.execute(()->{StaticWebPage result=null;try{result=fetchPage(url,signal);}catch(Exception ignored){}final StaticWebPage fetched=result;
            handler.post(()->{if(!active(token))return;end();if(fetched==null){status.setText(R.string.web_error);return;}
                while(history.size()>position+1)history.remove(history.size()-1);history.add(fetched);if(history.size()>50)history.remove(0);position=history.size()-1;showPage(fetched);
            });});}catch(RejectedExecutionException unavailable){end();status.setText(R.string.web_error);}
    }
    private void showPage(StaticWebPage value){page=value;address.setText(value.url);links.clear();links.addAll(value.links);adapter.notifyDataSetChanged();
        summary.setText(getString(R.string.static_web_summary,value.title.isEmpty()?value.url:value.title,value.images.size(),value.links.size()));
        status.setText(value.truncated?R.string.web_capped:value.images.isEmpty()?R.string.static_web_no_images:R.string.static_web_ready);updateButtons();}
    private void move(int delta){if(busy)return;int next=position+delta;if(next>=0&&next<history.size()){position=next;showPage(history.get(position));}}
    private void capture(){
        if(busy||page==null||page.images.isEmpty())return;final List<String> images=new ArrayList<>(page.images);final File cache=getApplicationContext().getCacheDir();
        int token=begin(R.string.web_discover,IMPORT_DEADLINE_MS);PinnedHttpsClient.Cancellation signal=cancellation;
        try{WORKER.execute(()->{StaticWebImport.Result result=null;try{result=fetchImages(images,cache,signal,(current,total)->handler.post(()->{if(active(token))status.setText(getString(R.string.web_capture,current,total));}));}catch(Exception ignored){}
            final StaticWebImport.Result captured=result;handler.post(()->{
                if(!active(token)){discard(captured,cache);return;}end();if(captured==null){status.setText(R.string.web_empty);return;}
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
