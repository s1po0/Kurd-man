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
    /** Translation provenance/review hints; not a semantic accuracy score. */
    public String translationMethod = "standard";
    public String translationWarnings = "";
    /** OCR rectangle in page coordinates. This is never moved by the editor. */
    public Rect box;
    /** Editable rectangle where the Kurdish text is drawn. */
    public Rect renderBox;
    /** Detected source rectangle that can be cleaned before replacement. */
    public Rect eraseBox;
    public int lines;
    /** Typical source line pitch in page pixels, measured from the OCR
     * line boxes themselves (median distance between consecutive line
     * tops, or glyph height × leading for a single line). Zero when
     * unknown. The typesetter starts from this instead of
     * {@code box.height() / lines}: ML Kit block boxes are loosely
     * padded, so box/lines over- or under-estimates the real font size
     * differently for every fragment — one root cause of the "some
     * words small, some words large" output. */
    public float sourceLineHeight = 0f;
    /** OCR confidence in [0,1], or -1 when the engine did not report it. */
    public float ocrConfidence = -1f;
    public int detectedBackgroundColor = Color.WHITE;
    public int detectedInkColor = Color.BLACK;
    public boolean autoBackgroundReliable = false;
    /** True when the detected bubble is round/oval, so text must be fitted
     * to the ellipse inside the rectangle, not to the rectangle itself. */
    public boolean bubbleOval = false;
    /** Per-row horizontal extent (absolute page X, -1 when empty) of the
     * actual bubble interior inside {@link #eraseBox}, starting at
     * {@link #interiorTop}. When present, text lines are fitted against
     * this real outline so they can never cross the bubble border. */
    public int interiorTop = -1;
    public int[] interiorLeft = null;
    public int[] interiorRight = null;
    /** True when this block is lettering drawn over artwork (SFX outside
     * any speech bubble). Such blocks are erased glyph-by-glyph against
     * the artwork and re-typeset in the original ink colour. Only set
     * after the outside eraser has validated the block. */
    public boolean outsideText = false;
    /** Whether the outside lettering is bright ink on a dark background. */
    public boolean outsideBrightInk = true;
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
        copy.translationMethod = translationMethod;
        copy.translationWarnings = translationWarnings;
        copy.renderBox = new Rect(getRenderBox());
        copy.eraseBox = new Rect(getEraseBox());
        copy.sourceLineHeight = sourceLineHeight;
        copy.ocrConfidence = ocrConfidence;
        copy.detectedBackgroundColor = detectedBackgroundColor;
        copy.detectedInkColor = detectedInkColor;
        copy.autoBackgroundReliable = autoBackgroundReliable;
        copy.bubbleOval = bubbleOval;
        copy.interiorTop = interiorTop;
        copy.interiorLeft = interiorLeft == null ? null : interiorLeft.clone();
        copy.interiorRight = interiorRight == null ? null : interiorRight.clone();
        copy.outsideText = outsideText;
        copy.outsideBrightInk = outsideBrightInk;
        copy.style = new TextStyleSettings(ensureStyle());
        copy.rendered = rendered;
        return copy;
    }
}
