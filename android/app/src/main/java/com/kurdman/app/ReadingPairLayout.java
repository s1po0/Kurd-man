package com.kurdman.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/** Fields/actions stack at narrow widths or large system text instead of clipping labels. */
public class ReadingPairLayout extends LinearLayout {
    public ReadingPairLayout(Context c) { super(c); }
    public ReadingPairLayout(Context c,AttributeSet attrs) { super(c,attrs); }
    @Override protected void onMeasure(int width,int height) {
        int available=MeasureSpec.getSize(width);
        boolean stacked=available<KoorixDesign.dp(getContext(),280)
                ||getResources().getConfiguration().fontScale>1.25f;
        int desired=stacked?VERTICAL:HORIZONTAL;
        if(getOrientation()!=desired)setOrientation(desired);
        int visible=0;
        for(int i=0;i<getChildCount();i++) {
            View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
            LayoutParams p=(LayoutParams)child.getLayoutParams();
            int start=stacked?0:visible==0?0:KoorixDesign.dp(getContext(),8);
            int top=stacked&&visible>0?KoorixDesign.dp(getContext(),8):0;
            int targetWidth=stacked?LayoutParams.MATCH_PARENT:0;
            float weight=stacked?0:1;
            if(p.width!=targetWidth||p.weight!=weight||p.getMarginStart()!=start||p.topMargin!=top) {
                p.width=targetWidth;p.weight=weight;p.setMarginStart(start);p.topMargin=top;
                child.setLayoutParams(p);
            }
            visible++;
        }
        super.onMeasure(width,height);
    }
}
