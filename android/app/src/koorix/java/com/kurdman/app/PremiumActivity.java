package com.kurdman.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;

/** Premium belongs to a protected device identity. There is NO user login screen. */
public class PremiumActivity extends AppCompatActivity {
    private LinearLayout root;
    private TextView status,usage,message;
    private EditText code;
    private boolean busy;
    @Override protected void onCreate(@Nullable Bundle saved){
        super.onCreate(saved);
        root=KoorixUi.page(this,getString(R.string.rel_premium),getString(R.string.rel_no_login));
        KoorixUi.text(this,root,getString(R.string.rel_premium_sub),14,false);
        LinearLayout card=KoorixUi.card(this,root);
        status=KoorixUi.text(this,card,getString(R.string.kx_free_plan),18,true);
        usage=KoorixUi.text(this,card,"",14,false);
        KoorixUi.button(this,card,getString(R.string.rel_refresh),this::refresh);
        LinearLayout offers=KoorixUi.card(this,root);
        KoorixUi.text(this,offers,getString(R.string.rel_order_notice),14,false);
        int[] months={1,3,6,12},prices={5000,14000,26000,40000};
        for(int i=0;i<months.length;i++){final int term=months[i];KoorixUi.button(this,offers,getString(R.string.rel_plan,term,prices[i]),()->order(term));}
        LinearLayout activation=KoorixUi.card(this,root);
        code=new EditText(this);code.setId(View.generateViewId());code.setHint(R.string.rel_code_hint);code.setSingleLine(true);code.setTextColor(0xFFF8FAFC);code.setHintTextColor(0xFFBDB2DA);code.setTextDirection(View.TEXT_DIRECTION_LTR);code.setMaxLines(1);
        activation.addView(code,new LinearLayout.LayoutParams(-1,KoorixUi.dp(this,56)));
        KoorixUi.button(this,activation,getString(R.string.rel_redeem),this::redeem);
        message=KoorixUi.text(this,root,"",14,false);message.setTextIsSelectable(true);
        KoorixUi.button(this,root,getString(R.string.rel_delete_device),()->new AlertDialog.Builder(this)
            .setTitle(R.string.rel_delete_device).setMessage(R.string.rel_delete_device_notice)
            .setNegativeButton(R.string.rel_cancel,null).setPositiveButton(R.string.rel_delete,(d,w)->removeDevice()).show());
        KoorixUi.button(this,root,getString(R.string.rel_back),this::finish);
        refresh();
    }
    private void setBusy(boolean value){busy=value;KoorixUi.enabled(root,!value);if(value)message.setText(R.string.rel_working);}
    private boolean live(){return !isFinishing()&&!isDestroyed();}
    private void refresh(){if(busy)return;setBusy(true);SupabaseAuth.fetchStatus(this,(result,error)->{if(!live())return;setBusy(false);if(result==null){message.setText(R.string.rel_device_network);return;}
        status.setText(result.optBoolean("premium",false)?getString(R.string.kx_premium_until,result.optString("premium_until","").split("T")[0]):getString(R.string.kx_free_plan));
        usage.setText(getString(R.string.rel_usage,result.optInt("jobs_today",0),result.optInt("free_job_limit",3),result.optInt("pages_today",0),result.optInt("free_page_limit",60)));message.setText("");});}
    private void order(int months){if(busy)return;new AlertDialog.Builder(this).setTitle(getString(R.string.rel_plan,months,months==1?5000:months==3?14000:months==6?26000:40000))
        .setMessage(R.string.rel_order_notice).setNegativeButton(R.string.rel_cancel,null).setPositiveButton(R.string.rel_save,(d,w)->{setBusy(true);SupabaseAuth.requestOrder(this,months,(result,error)->{if(!live())return;setBusy(false);message.setText(result==null?getString(R.string.rel_device_network):getString(R.string.rel_order_saved,result.optString("id",""))+"\n"+getString(R.string.rel_plan,result.optInt("months",months),result.optInt("price_iqd",0)));});}).show();}
    private void redeem(){if(busy)return;String value=code.getText().toString().trim();if(value.length()<6||value.length()>80){code.setError(getString(R.string.rel_code_bad));return;}setBusy(true);SupabaseAuth.redeemCode(this,value,(result,error)->{if(!live())return;setBusy(false);if(result!=null&&result.optBoolean("ok",false)){code.setText("");refresh();message.setText(R.string.rel_code_ok);}else message.setText(result==null?R.string.rel_device_network:R.string.rel_code_bad);});}
    private void removeDevice(){if(busy||JobRuntime.isRunning())return;setBusy(true);SupabaseAuth.deleteDeviceData(this,(result,error)->{if(!live())return;setBusy(false);if(result!=null&&result.optBoolean("ok",false)){status.setText(R.string.kx_free_plan);usage.setText("");message.setText(R.string.rel_deleted);}else message.setText(R.string.rel_device_network);});}
}
