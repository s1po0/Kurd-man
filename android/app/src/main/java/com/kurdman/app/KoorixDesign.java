package com.kurdman.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.graphics.Insets;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** Reading-studio design tokens and native accessibility. Never alters translated ink/data. */
final class KoorixDesign {
    private static final String PREFS="koorix_ui";
    static int dp(Context c,float value) { return Math.round(c.getResources().getDisplayMetrics().density*value); }
    static int color(Context c,int id) { return c.getColor(id); }
    static boolean dark(Context c) { return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES; }
    static int themeMode(Context c) { return c.getSharedPreferences(PREFS,0).getInt("theme",0); }
    static int nightMode(int choice) { return choice==1?AppCompatDelegate.MODE_NIGHT_NO:choice==2?AppCompatDelegate.MODE_NIGHT_YES:AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM; }
    static void initialize(Context c) {
        if(BuildConfig.PUBLIC_EDITION)AppCompatDelegate.setDefaultNightMode(nightMode(themeMode(c)));
    }
    static void setTheme(Context c,int choice) {
        int value=Math.max(0,Math.min(2,choice));
        if(value==themeMode(c))return;
        c.getSharedPreferences(PREFS,0).edit().putInt("theme",value).apply();
        AppCompatDelegate.setDefaultNightMode(nightMode(value));
    }
    static Typeface font(Context c,boolean bold) {
        TextStyleSettings s=new TextStyleSettings();s.fontId=FontCatalog.FONT_VAZIRMATN;s.fontWeight=bold?700:400;
        return FontCatalog.getTypeface(c,s);
    }
    static GradientDrawable surface(Context c,int fill,int border,int radius) {
        GradientDrawable d=new GradientDrawable();d.setColor(color(c,fill));d.setCornerRadius(dp(c,radius));
        if(border!=0)d.setStroke(dp(c,1),color(c,border));return d;
    }
    static void apply(Activity activity,View root) {
        if(root==null)return;
        root.setLayoutDirection("en".equals(activity.getResources().getConfiguration().getLocales().get(0).getLanguage())?View.LAYOUT_DIRECTION_LTR:View.LAYOUT_DIRECTION_RTL);
        Typeface regular=font(activity,false),bold=font(activity,true);
        typography(root,regular,bold);
        // System bars are handled once in logical coordinates. Fixed nav/CTAs
        // stay outside the scroll area and outside status/gesture/IME insets.
        if(BuildConfig.PUBLIC_EDITION) {
            WindowCompat.setDecorFitsSystemWindows(activity.getWindow(),false);
            activity.getWindow().setStatusBarColor(Color.TRANSPARENT);
            activity.getWindow().setNavigationBarColor(color(activity,R.color.page));
            WindowInsetsControllerCompat controller=WindowCompat.getInsetsController(activity.getWindow(),root);
            controller.setAppearanceLightStatusBars(!dark(activity));
            controller.setAppearanceLightNavigationBars(!dark(activity));
            final int l=root.getPaddingLeft(),t=root.getPaddingTop(),r=root.getPaddingRight(),b=root.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
                Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
                Insets ime=insets.getInsets(WindowInsetsCompat.Type.ime());
                view.setPadding(l+bars.left,t+bars.top,r+bars.right,b+Math.max(bars.bottom,ime.bottom));
                View nav=view.findViewById(R.id.bottomNavigation),dock=view.findViewById(R.id.translateActionDock);
                boolean keyboard=insets.isVisible(WindowInsetsCompat.Type.ime());
                if(nav!=null)nav.setVisibility(keyboard?View.GONE:View.VISIBLE);
                if(dock!=null) {
                    View home=view.findViewById(R.id.pageTranslate);
                    dock.setVisibility(!keyboard&&home!=null&&home.getVisibility()==View.VISIBLE?View.VISIBLE:View.GONE);
                }
                return insets;
            });
            ViewCompat.requestApplyInsets(root);
        }
    }
    private static void typography(View view,Typeface regular,Typeface bold) {
        // A selected font sample and the actual comic canvas belong to the
        // reader's lettering style, not to app chrome. Never restyle them.
        if(view.getId()==R.id.stylePreview||view instanceof PageEditorView)return;
        if(view instanceof Spinner)return;
        if(view instanceof TextView) {
            TextView t=(TextView)view;
            boolean strong=view instanceof Button||(t.getTypeface()!=null&&t.getTypeface().isBold());
            if(!"monospace".equals(t.getTag()))t.setTypeface(strong?bold:regular);
            t.setIncludeFontPadding(true);
            if(view instanceof Button) {
                Button button=(Button)view;button.setAllCaps(false);
                button.setMinHeight(dp(view.getContext(),48));button.setMinWidth(dp(view.getContext(),48));
                // These two buttons are actual reader-selected colour samples,
                // not chrome. Keep their saved swatch tint intact.
                if(button.getId()!=R.id.textColorButton&&button.getId()!=R.id.backgroundColorButton) {
                    button.setBackgroundTintList(null);
                    if(button instanceof AppCompatButton)((AppCompatButton)button).setSupportBackgroundTintList(null);
                }
                button.setCompoundDrawableTintList(t.getTextColors());
            }
        }
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)typography(group.getChildAt(i),regular,bold);
        }
    }
    static void refreshTypography(View view) {
        if(view!=null)typography(view,font(view.getContext(),false),font(view.getContext(),true));
    }
    static void nav(Button button,boolean selected) {
        if(button==null)return;
        button.setSelected(selected);button.setAlpha(1f);button.setBackgroundResource(R.drawable.bg_nav_item);
        int ink=color(button.getContext(),selected?R.color.primary:R.color.text_secondary);
        button.setTextColor(ink);button.setCompoundDrawableTintList(ColorStateList.valueOf(ink));
        button.setTypeface(font(button.getContext(),selected));
        if(android.os.Build.VERSION.SDK_INT>=30)button.setStateDescription(selected?button.getContext().getString(R.string.ux_selected):null);
    }
    static void icon(Button button,int resource,int description) {
        if(button==null)return;
        button.setText("");button.setContentDescription(button.getContext().getString(description));
        button.setTooltipText(button.getContentDescription());button.setGravity(Gravity.CENTER);button.setPadding(0,0,0,0);
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(resource,0,0,0);
        button.setCompoundDrawableTintList(button.getTextColors());
        button.setMinHeight(dp(button.getContext(),48));button.setMinWidth(dp(button.getContext(),48));
        ViewGroup.LayoutParams p=button.getLayoutParams();
        if(p!=null) {if(p.width>=0&&p.width<dp(button.getContext(),48))p.width=dp(button.getContext(),48);if(p.height>=0&&p.height<dp(button.getContext(),48))p.height=dp(button.getContext(),48);button.setLayoutParams(p);}
    }
    static void primary(Button button,boolean primary) {
        button.setBackgroundResource(primary?R.drawable.bg_button_primary:R.drawable.bg_button_secondary);
        button.setTextColor(androidx.core.content.ContextCompat.getColorStateList(button.getContext(),primary?R.color.ui_primary_text:R.color.ui_secondary_text));
        button.setBackgroundTintList(null);if(button instanceof AppCompatButton)((AppCompatButton)button).setSupportBackgroundTintList(null);
        button.setCompoundDrawableTintList(button.getTextColors());
        button.setAllCaps(false);button.setMinHeight(dp(button.getContext(),48));
    }
}
