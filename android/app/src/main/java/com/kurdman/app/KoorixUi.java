package com.kurdman.app;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Shared native reading-studio components; no new framework/network/model. */
final class KoorixUi {
    static int dp(Activity a,int n){return KoorixDesign.dp(a,n);}
    static LinearLayout page(Activity a,String title,String subtitle){
        LinearLayout shell=new LinearLayout(a);shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(a.getColor(R.color.page));
        shell.setLayoutDirection("en".equals(a.getResources().getConfiguration().getLocales().get(0).getLanguage())?View.LAYOUT_DIRECTION_LTR:View.LAYOUT_DIRECTION_RTL);
        LinearLayout header=new LinearLayout(a);header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(a,12),dp(a,8),dp(a,16),dp(a,8));
        Button back=new Button(a,null,0);back.setBackgroundResource(R.drawable.bg_button_secondary);
        header.addView(back,new LinearLayout.LayoutParams(dp(a,48),dp(a,48)));
        KoorixDesign.icon(back,R.drawable.ic_ui_back,R.string.ux_back);back.setOnClickListener(v->a.finish());
        TextView heading=new TextView(a);heading.setText(title);heading.setTextColor(a.getColor(R.color.text_primary));heading.setTextSize(22);heading.setTypeface(KoorixDesign.font(a,true));
        LinearLayout.LayoutParams h=new LinearLayout.LayoutParams(0,-2,1);h.setMarginStart(dp(a,12));header.addView(heading,h);
        androidx.core.view.ViewCompat.setAccessibilityHeading(heading,true);
        shell.addView(header,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        ReadingColumn column=new ReadingColumn(a);column.setOrientation(LinearLayout.VERTICAL);column.setPadding(dp(a,16),dp(a,8),dp(a,16),dp(a,24));
        scroll.addView(column,new android.widget.FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_HORIZONTAL));
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        a.setContentView(shell);text(a,column,subtitle,14,false);KoorixDesign.apply(a,shell);return column;
    }
    static TextView text(Activity a,LinearLayout root,String value,int size,boolean bold){
        TextView text=new TextView(a);text.setText(value);text.setTextColor(a.getColor(bold?R.color.text_primary:R.color.text_secondary));text.setTextSize(size);
        text.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);text.setLineSpacing(dp(a,2),1.12f);text.setIncludeFontPadding(true);
        text.setTypeface(KoorixDesign.font(a,bold));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(a,8);root.addView(text,params);return text;
    }
    static LinearLayout card(Activity a,LinearLayout root){
        LinearLayout card=new LinearLayout(a);card.setOrientation(LinearLayout.VERTICAL);int p=dp(a,16);card.setPadding(p,p,p,p);
        card.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(a,16);root.addView(card,params);return card;
    }
    static Button button(Activity a,LinearLayout root,String label,Runnable action){
        Button button=new Button(a,null,0);button.setText(label);button.setTextSize(14);button.setTypeface(KoorixDesign.font(a,true));button.setIncludeFontPadding(true);
        button.setPadding(dp(a,16),dp(a,12),dp(a,16),dp(a,12));KoorixDesign.primary(button,false);button.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(a,8);root.addView(button,params);return button;
    }
    static void enabled(LinearLayout root,boolean enabled){for(int i=0;i<root.getChildCount();i++){View child=root.getChildAt(i);if(child instanceof LinearLayout)enabled((LinearLayout)child,enabled);else child.setEnabled(enabled);}}
}
