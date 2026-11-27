package com.kurdman.app;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native catalog and explicitly confirmed Android installer handoff. No extension code is run here. */
public class ExtensionCatalogActivity extends AppCompatActivity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final List<Item> items=new ArrayList<>();
    private List<ExtensionLibrarySections.Installed> local=Collections.emptyList();
    private List<ExtensionStoreIndex.Entry> catalog=Collections.emptyList();
    private TextView status;
    private ActivityResultLauncher<Intent> reader;
    private EditText search;
    private Button refresh;
    private BaseAdapter adapter;
    private boolean inventoryKnown=false, storeKnown=false, loading=false, installerLaunched=false;
    private String storeSigner;
    private int generation;
    private static final class Item {
        final int heading;final ExtensionLibrarySections.Row row;
        Item(int heading,ExtensionLibrarySections.Row row){this.heading=heading;this.row=row;}
    }
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        reader=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
            if(result.getResultCode()==RESULT_OK&&result.getData()!=null){setResult(RESULT_OK,result.getData());finish();}
        });
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(getColor(R.color.page));
        root.setLayoutDirection("en".equals(getResources().getConfiguration().getLocales().get(0).getLanguage())?View.LAYOUT_DIRECTION_LTR:View.LAYOUT_DIRECTION_RTL);
        int pad=KoorixDesign.dp(this,16);root.setPadding(pad,pad,pad,pad);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        Button back=new Button(this,null,0);KoorixDesign.primary(back,false);KoorixDesign.icon(back,R.drawable.ic_ui_back,R.string.ux_back);
        back.setOnClickListener(v->finish());header.addView(back,new LinearLayout.LayoutParams(KoorixDesign.dp(this,48),KoorixDesign.dp(this,48)));
        TextView title=new TextView(this);title.setText(R.string.ext_title);title.setTextColor(getColor(R.color.text_primary));title.setTextSize(21);title.setTypeface(KoorixDesign.font(this,true));
        LinearLayout.LayoutParams titleLayout=new LinearLayout.LayoutParams(0,-2,1);titleLayout.setMarginStart(KoorixDesign.dp(this,12));header.addView(title,titleLayout);
        refresh=new Button(this,null,0);refresh.setId(R.id.extensionCatalogRefresh);refresh.setText(R.string.ext_refresh);KoorixDesign.primary(refresh,false);refresh.setOnClickListener(v->load());header.addView(refresh);
        root.addView(header);
        TextView note=new TextView(this);note.setText(R.string.ext_notice);note.setTextColor(getColor(R.color.text_secondary));note.setTextSize(14);note.setPadding(0,KoorixDesign.dp(this,10),0,KoorixDesign.dp(this,8));root.addView(note);
        search=new EditText(this);search.setId(R.id.extensionCatalogSearch);search.setSingleLine(true);search.setTextSize(16);search.setHint(R.string.ext_search);search.setContentDescription(getString(R.string.ext_search));
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){render();}public void afterTextChanged(Editable e){}});
        root.addView(search,new LinearLayout.LayoutParams(-1,-2));
        status=new TextView(this);status.setId(R.id.extensionCatalogStatus);status.setTextColor(getColor(R.color.text_secondary));status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);status.setPadding(0,KoorixDesign.dp(this,8),0,KoorixDesign.dp(this,8));root.addView(status);
        ListView list=new ListView(this);list.setId(R.id.extensionCatalogList);list.setDividerHeight(KoorixDesign.dp(this,4));
        adapter=new BaseAdapter(){
            @Override public int getCount(){return items.size();}
            @Override public Object getItem(int i){return items.get(i);}
            @Override public long getItemId(int i){return i;}
            @Override public boolean areAllItemsEnabled(){return false;}
            @Override public boolean isEnabled(int position){return items.get(position).row!=null;}
            @Override public View getView(int position,View recycled,ViewGroup parent){
                Item item=items.get(position);TextView text=recycled instanceof TextView?(TextView)recycled:new TextView(ExtensionCatalogActivity.this);
                text.setPadding(KoorixDesign.dp(ExtensionCatalogActivity.this,12),KoorixDesign.dp(ExtensionCatalogActivity.this,12),KoorixDesign.dp(ExtensionCatalogActivity.this,12),KoorixDesign.dp(ExtensionCatalogActivity.this,12));
                text.setTextColor(getColor(R.color.text_primary));text.setTextSize(item.row==null?16:15);text.setTypeface(KoorixDesign.font(ExtensionCatalogActivity.this,item.row==null));
                text.setBackgroundResource(item.row==null?android.R.color.transparent:R.drawable.bg_card);
                androidx.core.view.ViewCompat.setAccessibilityHeading(text,item.row==null);
                if(item.row==null)text.setText(item.heading);
                else {ExtensionStoreIndex.Entry e=item.row.catalogEntry;String warning=e==null?"":warning(e.contentWarning);
                    text.setText(ExtensionInstalledInventory.safeLabel(item.row.displayName,item.row.packageName)+(warning.isEmpty()?"":" • "+warning));}
                return text;
            }
        };
        list.setAdapter(adapter);list.setOnItemClickListener((p,v,pos,id)->{Item item=items.get(pos);if(!loading&&item.row!=null)showDetails(item.row);});
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);KoorixDesign.apply(this,root);load();
    }
    private String warning(int code){
        if(code==2)return getString(R.string.ext_mixed);
        if(code==3)return getString(R.string.ext_adult);
        if(code==0)return getString(R.string.ext_unknown_warning);
        return "";
    }
    private void load(){
        if(loading)return;loading=true;refresh.setEnabled(false);status.setText(R.string.ext_loading);final int token=++generation;
        PackageManager pm=getApplicationContext().getPackageManager();
        io.execute(()->{
            List<ExtensionLibrarySections.Installed> found=Collections.emptyList();ExtensionStoreIndex index=null;boolean scanned=false;
            try{found=scanInstalled(pm);scanned=true;}catch(RuntimeException ignored){}
            try{index=fetchCatalog();}catch(Exception ignored){}
            final List<ExtensionLibrarySections.Installed> result=found;final ExtensionStoreIndex loaded=index;final boolean scanOk=scanned;
            runOnUiThread(()->{if(isFinishing()||isDestroyed()||generation!=token)return;
                local=result;inventoryKnown=scanOk;storeKnown=loaded!=null;catalog=loaded==null?Collections.emptyList():loaded.entries;
                storeSigner=loaded==null?null:loaded.signingKey;
                loading=false;refresh.setEnabled(true);
                if(!inventoryKnown)status.setText(R.string.ext_inventory_unavailable);
                else if(!storeKnown)status.setText(R.string.ext_store_unavailable);
                else status.setText(R.string.ext_ready);
                render();
            });
        });
    }
    List<ExtensionLibrarySections.Installed> scanInstalled(PackageManager pm){return ExtensionInstalledInventory.scan(pm);}
    ExtensionStoreIndex fetchCatalog() throws java.io.IOException {return new ExtensionCatalogClient().load(45000);}
    private void render(){
        if(adapter==null||search==null)return;
        items.clear();ExtensionLibrarySections sections=ExtensionLibrarySections.build(local,catalog,search.getText().toString());
        if(!sections.installed.isEmpty()){items.add(new Item(R.string.ext_installed,null));for(ExtensionLibrarySections.Row row:sections.installed)items.add(new Item(0,row));}
        if(!sections.available.isEmpty()){items.add(new Item(inventoryKnown?R.string.ext_available:R.string.ext_catalog_unknown,null));for(ExtensionLibrarySections.Row row:sections.available)items.add(new Item(0,row));}
        adapter.notifyDataSetChanged();
    }
    private void showDetails(ExtensionLibrarySections.Row row){
        boolean canPrepare=inventoryKnown&&row.catalogEntry!=null&&storeSigner!=null;
        StringBuilder message=new StringBuilder(getString(canPrepare?R.string.ext_details_notice:R.string.ext_details_unavailable));
        if(row.catalogEntry!=null){
            for(ExtensionStoreIndex.Source source:row.catalogEntry.sources){
                if(message.length()>8000){message.append('\n').append('…');break;}
                message.append('\n').append('•').append(' ').append(ExtensionInstalledInventory.safeLabel(source.name,source.language)).append(" (").append(ExtensionInstalledInventory.safeLabel(source.language,"?")).append(')');
            }
        }else message.append('\n').append(getString(R.string.ext_no_store_info));
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle(ExtensionInstalledInventory.safeLabel(row.displayName,row.packageName))
                .setMessage(message.toString()).setNegativeButton(android.R.string.cancel,null);
        if(canPrepare){
            final String signer=storeSigner;final ExtensionStoreIndex.Entry entry=row.catalogEntry;
            dialog.setPositiveButton(R.string.ext_prepare,(d,w)->confirmSigner(entry,signer));
        }else dialog.setPositiveButton(android.R.string.ok,null);
        if(row.catalogEntry!=null&&!row.catalogEntry.sources.isEmpty())
            dialog.setNeutralButton(R.string.ext_open_site,(d,w)->chooseStaticSite(row.catalogEntry));
        dialog.show();
    }
    private void chooseStaticSite(ExtensionStoreIndex.Entry entry){
        List<ExtensionStoreIndex.Source> supported=new ArrayList<>();List<String> labels=new ArrayList<>();
        for(ExtensionStoreIndex.Source source:entry.sources){
            try{PinnedHttpsClient.checkedUrl(source.homeUrl);}catch(java.io.IOException invalid){continue;}
            supported.add(source);labels.add(ExtensionInstalledInventory.safeLabel(source.name,"?")+" ("+ExtensionInstalledInventory.safeLabel(source.language,"?")+")");
        }
        if(supported.isEmpty()){status.setText(R.string.ext_site_unavailable);return;}
        new AlertDialog.Builder(this).setTitle(R.string.ext_choose_site).setItems(labels.toArray(new String[0]),(d,index)->{
            if(loading||isFinishing()||isDestroyed())return;
            reader.launch(new Intent(this,StaticWebReaderActivity.class).putExtra(StaticWebReaderActivity.EXTRA_START_URL,supported.get(index).homeUrl));
        }).setNegativeButton(R.string.ext_cancel,null).show();
    }
    private void confirmSigner(ExtensionStoreIndex.Entry entry,String signer){
        if(loading||isFinishing()||isDestroyed())return;
        new AlertDialog.Builder(this).setTitle(R.string.ext_signer_title)
                .setMessage(getString(R.string.ext_signer_notice,"\u2066"+signer+"\u2069"))
                .setNegativeButton(R.string.ext_cancel,null)
                .setPositiveButton(R.string.ext_approve_signer,(d,w)->checkInstallPermission(entry,signer)).show();
    }
    private void checkInstallPermission(ExtensionStoreIndex.Entry entry,String signer){
        if(loading||isFinishing()||isDestroyed())return;
        if(!getPackageManager().canRequestPackageInstalls()){
            new AlertDialog.Builder(this).setTitle(R.string.ext_allow_install_title).setMessage(R.string.ext_allow_install_message)
                    .setNegativeButton(R.string.ext_cancel,null).setPositiveButton(R.string.ext_open_settings,(d,w)->{
                        try{startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));}
                        catch(RuntimeException unavailable){status.setText(R.string.ext_installer_missing);}
                    }).show();return;
        }
        prepare(entry,signer);
    }
    private void prepare(ExtensionStoreIndex.Entry entry,String signer){
        loading=true;refresh.setEnabled(false);status.setText(R.string.ext_preparing);final int token=++generation;
        File cache=getApplicationContext().getCacheDir();PackageManager pm=getApplicationContext().getPackageManager();
        io.execute(()->{
            File prepared=null;try{prepared=ExtensionApkStager.prepare(entry,signer,cache,pm,120000);}catch(Exception ignored){}
            final File result=prepared;
            runOnUiThread(()->{if(isFinishing()||isDestroyed()||generation!=token)return;
                loading=false;refresh.setEnabled(true);
                if(result==null){status.setText(R.string.ext_prepare_failed);return;}
                try{
                    Uri uri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",result);
                    Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(intent);installerLaunched=true;status.setText(R.string.ext_installer_opened);
                }catch(RuntimeException unavailable){status.setText(R.string.ext_installer_missing);}
            });
        });
    }
    @Override protected void onResume(){super.onResume();if(installerLaunched){installerLaunched=false;load();}}
    @Override protected void onDestroy(){generation++;io.shutdownNow();super.onDestroy();}
}
