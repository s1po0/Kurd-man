package com.kurdman.app;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/** A readable, centred measure on tablets without a separate/hidden phone UI. */
public class ReadingColumn extends LinearLayout {
    public ReadingColumn(Context c) { super(c); }
    public ReadingColumn(Context c,AttributeSet attrs) { super(c,attrs); }
    @Override protected void onMeasure(int width,int height) {
        int cap=KoorixDesign.dp(getContext(),760);
        int size=MeasureSpec.getSize(width);
        if(MeasureSpec.getMode(width)!=MeasureSpec.UNSPECIFIED&&size>cap)
            width=MeasureSpec.makeMeasureSpec(cap,MeasureSpec.EXACTLY);
        super.onMeasure(width,height);
    }
}
