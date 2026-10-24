package com.kurdman.app;

import android.graphics.Color;
import android.graphics.Rect;

import java.util.Locale;

/** One OCR text block, its Sorani translation, and its editable placement. */
public class Segment {
    public String text;
    public String ckb = "";
    /** Translation as first produced, used by the editor's reset action. */
    public String originalCkb = "";
    /** OCR rectangle in page coordinates. This is never moved by the editor. */
    public Rect box;
    /** Editable rectangle where the Kurdish text is drawn. */
    public Rect renderBox;
    /** Detected source rectangle that can be cleaned before replacement. */
    public Rect eraseBox;
    public int lines;
    /** OCR confidence in [0,1], or -1 when the engine did not report it. */
    public float ocrConfidence = -1f;
    public int detectedBackgroundColor = Color.WHITE;
    public int detectedInkColor = Color.BLACK;
    public boolean autoBackgroundReliable = false;
    public TextStyleSettings style = new TextStyleSettings();
    /** Set by a renderer when this segment was actually drawn on the page. */
    public boolean rendered;

    public Segment(String text, Rect box, int lines) {
        this.text = text == null ? "" : text.trim();
        this.box = new Rect(box);
        this.renderBox = new Rect(box);
        this.eraseBox = new Rect(box);
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

    public TextStyleSettings ensureStyle() {
        if (style == null) {
            style = new TextStyleSettings();
        }
        style.sanitize();
        return style;
    }

    public Rect getRenderBox() {
        if (renderBox == null || renderBox.width() <= 0 || renderBox.height() <= 0) {
            renderBox = new Rect(box);
        }
        return renderBox;
    }

    public Rect getEraseBox() {
        if (eraseBox == null || eraseBox.width() <= 0 || eraseBox.height() <= 0) {
            eraseBox = new Rect(box);
        }
        return eraseBox;
    }

    public Segment duplicate() {
        Segment copy = new Segment(text, box, lines);
        copy.ckb = ckb;
        copy.originalCkb = originalCkb;
        copy.renderBox = new Rect(getRenderBox());
        copy.eraseBox = new Rect(getEraseBox());
        copy.ocrConfidence = ocrConfidence;
        copy.detectedBackgroundColor = detectedBackgroundColor;
        copy.detectedInkColor = detectedInkColor;
        copy.autoBackgroundReliable = autoBackgroundReliable;
        copy.style = new TextStyleSettings(ensureStyle());
        copy.rendered = rendered;
        return copy;
    }
}
