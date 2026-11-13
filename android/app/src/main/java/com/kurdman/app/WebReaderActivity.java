package com.kurdman.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.UUID;

/** Explicit, bounded page capture. Website code has no Android bridge. */
public final class WebReaderActivity extends AppCompatActivity {
    static final String EXTRA_FILES="koorix.web.files";
    private static final long MAX_TOTAL=300L*1024*1024, MAX_IMAGE=32L*1024*1024;
    private static final int MAX_PAGES=300;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayList<String> candidates=new ArrayList<>(),files=new ArrayList<>();
    private WebView web;
    private EditText address;
    private TextView status;
    private Button translate,stop;
    private String discovery,capture,transport,topUrl;
    private File session,current;
    private FileOutputStream out;
    private long total,currentBytes,deadline;
    private int epoch,index,failed,jsId;
    private boolean capturing,transferred;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {discovery=asset("web/discover-images.js");capture=asset("web/capture-image.js");transport=asset("web/transport.js");}
        catch(Exception failure){finish();return;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(getColor(R.color.page));
        LinearLayout bar=new LinearLayout(this);bar.setOrientation(LinearLayout.HORIZONTAL);
        Button back=button(R.string.web_back);back.setOnClickListener(v->{if(web.canGoBack())web.goBack();else finish();});bar.addView(back);
        Button forward=button(R.string.web_forward);forward.setOnClickListener(v->{if(web.canGoForward())web.goForward();});bar.addView(forward);
        address=new EditText(this);address.setSingleLine(true);address.setHint(R.string.web_address);address.setTextDirection(View.TEXT_DIRECTION_LTR);address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);address.setImeOptions(EditorInfo.IME_ACTION_GO);
        bar.addView(address,new LinearLayout.LayoutParams(0,-2,1));
        Button go=button(R.string.web_go);go.setOnClickListener(v->open());bar.addView(go);
        address.setOnEditorActionListener((v,id,event)->{if(id==EditorInfo.IME_ACTION_GO){open();return true;}return false;});root.addView(bar);
        status=new TextView(this);status.setText(R.string.web_hint);status.setTextColor(getColor(R.color.text_secondary));status.setPadding(12,8,12,8);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(status);
        web=new WebView(this);WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSupportMultipleWindows(false);settings.setSafeBrowsingEnabled(true);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !secureUrl(request.getUrl().toString());}
            @Override public void onPageStarted(WebView view,String url,Bitmap favicon){epoch++;if(capturing)cancelCapture();if(!secureUrl(url)){view.stopLoading();status.setText(R.string.web_invalid);}}
            @Override public void onPageFinished(WebView view,String url){if(secureUrl(url))address.setText(url);}
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        translate=button(R.string.web_translate);translate.setOnClickListener(v->beginCapture());actions.addView(translate,new LinearLayout.LayoutParams(0,-2,1));
        stop=button(R.string.web_stop);stop.setEnabled(false);stop.setOnClickListener(v->cancelCapture());actions.addView(stop);
        root.addView(actions);setContentView(root);
    }
    private Button button(int label){Button b=new Button(this);b.setText(label);b.setMinHeight(KoorixDesign.dp(this,48));return b;}
    private String asset(String name) throws Exception {try(InputStream in=getAssets().open(name);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);return bytes.toString(StandardCharsets.UTF_8.name());}}
    static boolean secureUrl(String value){try{Uri u=Uri.parse(value);return "https".equalsIgnoreCase(u.getScheme())&&u.getHost()!=null&&!u.getHost().isEmpty()&&u.getUserInfo()==null;}catch(Exception bad){return false;}}
    static boolean sameOrigin(String page,String candidate){try{Uri a=Uri.parse(page),b=Uri.parse(candidate);return secureUrl(page)&&secureUrl(candidate)&&a.getHost().equalsIgnoreCase(b.getHost())&&(a.getPort()<0?443:a.getPort())==(b.getPort()<0?443:b.getPort());}catch(Exception bad){return false;}}
    private void open(){if(capturing)return;String text=address.getText().toString().trim();if(text.isEmpty())return;String url;
        if(text.startsWith("https://"))url=text;
        else if(text.contains("://")){status.setText(R.string.web_invalid);return;}
        else if(!text.contains(" ")&&text.contains("."))url="https://"+text;
        else url="https://www.google.com/search?q="+Uri.encode(text);
        if(!secureUrl(url)){status.setText(R.string.web_invalid);return;}web.loadUrl(url);
    }
    private void beginCapture(){if(capturing||!secureUrl(web.getUrl())){status.setText(R.string.web_invalid);return;}
        topUrl=web.getUrl();int token=++epoch;capturing=true;translate.setEnabled(false);stop.setEnabled(true);status.setText(R.string.web_discover);
        candidates.clear();files.clear();index=0;failed=0;total=0;transferred=false;
        session=new File(getCacheDir(),"web-reader/"+UUID.randomUUID());if(!session.mkdirs()){cancelCapture();status.setText(R.string.web_error);return;}
        web.evaluateJavascript(discovery,raw->{if(!valid(token))return;try{
            JSONObject obj=new JSONObject(raw);JSONArray imgs=obj.getJSONArray("images");LinkedHashSet<String> unique=new LinkedHashSet<>();
            for(int i=0;i<imgs.length()&&unique.size()<MAX_PAGES;i++){String u=imgs.getJSONObject(i).optString("url","");if(u.length()<=8192&&sameOrigin(topUrl,u))unique.add(u);}
            candidates.addAll(unique);if(candidates.isEmpty()){cancelCapture();status.setText(R.string.web_empty);return;}
            String install="window.__koorixWebCapture=("+transport+")((u,m,s)=>("+capture+")(u,m,fetch,15000,s),btoa.bind(window));true";
            web.evaluateJavascript(install,result->{if(valid(token))next(token);});
        }catch(Exception e){cancelCapture();status.setText(R.string.web_error);}});
    }
    private boolean valid(int token){return capturing&&epoch==token&&topUrl!=null&&topUrl.equals(web.getUrl());}
    private void next(int token){if(!valid(token))return;if(index>=candidates.size()||total>=MAX_TOTAL){finishCapture();return;}
        status.setText(getString(R.string.web_capture,index+1,candidates.size()));current=new File(session,String.format(Locale.ROOT,"%04d.part",index+1));currentBytes=0;
        try{out=new FileOutputStream(current);}catch(Exception failure){cancelCapture();status.setText(R.string.web_error);return;}
        long limit=Math.min(MAX_IMAGE,MAX_TOTAL-total);deadline=android.os.SystemClock.elapsedRealtime()+30000;
        String code="window.__koorixWebCapture.start("+JSONObject.quote(candidates.get(index))+","+limit+")";
        web.evaluateJavascript(code,raw->{if(!valid(token))return;try{jsId=Integer.parseInt(raw);poll(token);}catch(Exception bad){skip(token);}});
    }
    private void poll(int token){if(!valid(token))return;if(android.os.SystemClock.elapsedRealtime()>deadline){skip(token);return;}
        web.evaluateJavascript("window.__koorixWebCapture.poll("+jsId+")",raw->{if(!valid(token))return;try{
            JSONObject result=new JSONObject(raw);String state=result.optString("status");
            if("working".equals(state)){handler.postDelayed(()->poll(token),50);return;}
            if("chunk".equals(state)){String data=result.optString("data","");if(data.isEmpty()||data.length()>65536)throw new IllegalArgumentException();byte[] chunk=Base64.decode(data,Base64.DEFAULT);if(chunk.length==0||chunk.length>49152||currentBytes+chunk.length>MAX_IMAGE||total+currentBytes+chunk.length>MAX_TOTAL)throw new IllegalArgumentException();out.write(chunk);currentBytes+=chunk.length;handler.post(()->poll(token));return;}
            if("done".equals(state)){out.close();out=null;BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(current.getAbsolutePath(),bounds);
                if(bounds.outWidth<=0||bounds.outHeight<=0||(long)bounds.outWidth*bounds.outHeight>100_000_000L)throw new IllegalArgumentException();
                String ext="image/png".equals(bounds.outMimeType)?".png":"image/webp".equals(bounds.outMimeType)?".webp":"image/bmp".equals(bounds.outMimeType)?".bmp":"image/jpeg".equals(bounds.outMimeType)?".jpg":null;
                if(ext==null)throw new IllegalArgumentException();File finalFile=new File(session,String.format(Locale.ROOT,"%04d%s",index+1,ext));if(!current.renameTo(finalFile))throw new IllegalStateException();files.add(finalFile.getAbsolutePath());total+=currentBytes;current=null;index++;next(token);return;}
            skip(token);
        }catch(Exception failure){skip(token);}});
    }
    private void skip(int token){try{if(out!=null)out.close();}catch(Exception ignored){}out=null;if(current!=null)current.delete();current=null;failed++;index++;web.evaluateJavascript("window.__koorixWebCapture.cancel()",null);next(token);}
    private void finishCapture(){capturing=false;stop.setEnabled(false);translate.setEnabled(true);if(files.isEmpty()){deleteSession();status.setText(R.string.web_empty);return;}
        if(failed>0)Toast.makeText(this,getString(R.string.web_partial,failed),Toast.LENGTH_LONG).show();transferred=true;setResult(RESULT_OK,new Intent().putStringArrayListExtra(EXTRA_FILES,files));finish();}
    private void deleteSession(){if(session!=null)TranslationProjectStore.deleteRecursive(session);session=null;}
    private void cancelCapture(){if(!capturing)return;capturing=false;epoch++;stop.setEnabled(false);translate.setEnabled(true);try{if(out!=null)out.close();}catch(Exception ignored){}out=null;web.evaluateJavascript("if(window.__koorixWebCapture)window.__koorixWebCapture.cancel()",null);deleteSession();status.setText(R.string.web_hint);}
    @Override protected void onDestroy(){cancelCapture();if(!transferred)deleteSession();if(web!=null){web.stopLoading();web.destroy();}super.onDestroy();}
    @Override public void onBackPressed(){if(capturing){cancelCapture();return;}if(web.canGoBack()){web.goBack();return;}super.onBackPressed();}
}
