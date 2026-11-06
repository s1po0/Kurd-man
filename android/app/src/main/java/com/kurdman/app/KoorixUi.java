package com.kurdman.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

/** Small accessible dark-theme building blocks shared by the local library/Premium pages. */
final class KoorixUi {
    static int dp(Activity a,int n){return Math.round(a.getResources().getDisplayMetrics().density*n);}
    static LinearLayout page(Activity a,String title,String subtitle){
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xFF0B0618,0xFF160B2E}));
        LinearLayout root=new LinearLayout(a);root.setOrientation(LinearLayout.VERTICAL);int pad=dp(a,20);root.setPadding(pad,pad,pad,pad);
        root.setLayoutDirection("en".equals(a.getResources().getConfiguration().getLocales().get(0).getLanguage())?View.LAYOUT_DIRECTION_LTR:View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root);a.setContentView(scroll);text(a,root,title,25,true);text(a,root,subtitle,14,false);return root;
    }
    static TextView text(Activity a,LinearLayout root,String value,int size,boolean bold){
        TextView text=new TextView(a);text.setText(value);text.setTextColor(bold?0xFFF8FAFC:0xFFBDB2DA);text.setTextSize(size);
        text.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);text.setLineSpacing(dp(a,3),1.15f);
        if(bold)text.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(a,12);root.addView(text,params);return text;
    }
    static LinearLayout card(Activity a,LinearLayout root){
        LinearLayout card=new LinearLayout(a);card.setOrientation(LinearLayout.VERTICAL);int p=dp(a,16);card.setPadding(p,p,p,p);
        GradientDrawable background=new GradientDrawable();background.setColor(0xFF1E1538);background.setCornerRadius(dp(a,16));background.setStroke(dp(a,1),0xFF372A63);card.setBackground(background);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(a,12);params.bottomMargin=dp(a,6);root.addView(card,params);return card;
    }
    static Button button(Activity a,LinearLayout root,String label,Runnable action){
        Button button=new Button(a);button.setText(label);button.setTextColor(0xFFF8FAFC);button.setTextSize(14);button.setAllCaps(false);button.setMinHeight(dp(a,52));
        button.setBackgroundTintList(ColorStateList.valueOf(0xFF6D28D9));button.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(a,6);params.bottomMargin=dp(a,6);root.addView(button,params);return button;
    }
    static void enabled(LinearLayout root,boolean enabled){for(int i=0;i<root.getChildCount();i++){View child=root.getChildAt(i);if(child instanceof LinearLayout)enabled((LinearLayout)child,enabled);else child.setEnabled(enabled);}}
}
