package com.kurdman.app;

import android.content.Context;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatTextView;

/** Script-capable UI font for native spinner rows; font-sample adapters can still override it. */
public class ReadingTextView extends AppCompatTextView {
    public ReadingTextView(Context c) { this(c,null); }
    public ReadingTextView(Context c,AttributeSet attrs) {
        super(c,attrs);
        if(!isInEditMode())setTypeface(KoorixDesign.font(c,getTypeface()!=null&&getTypeface().isBold()));
        setIncludeFontPadding(true);
    }
}
