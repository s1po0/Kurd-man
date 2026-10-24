package com.kurdman.app;

import android.graphics.Rect;

import java.util.Locale;

/** One OCR text block and its Sorani translation. */
public class Segment {
    public String text;
    public String ckb = "";
    public Rect box;
    public int lines;
    /** Set by the renderer when this segment was actually drawn on the page. */
    public boolean rendered;

    public Segment(String text, Rect box, int lines) {
        this.text = text == null ? "" : text.trim();
        this.box = new Rect(box);
        this.lines = Math.max(1, lines);
    }

    public String normalized() {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    public float centerX() {
        return box.exactCenterX();
    }

    public float centerY() {
        return box.exactCenterY();
    }
}
