package com.kurdman.app;

import android.graphics.Color;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Visual style for one translated text block.
 *
 * <p>The same object is used for the global default chosen on the main
 * screen and for every editable block in the post-translation studio. It is
 * deliberately made of primitive fields so a complete translation project
 * can be serialized to JSON and reopened after the process restarts.</p>
 */
public class TextStyleSettings {
    public static final int BG_AUTO = 0;
    public static final int BG_CUSTOM = 1;
    public static final int BG_NONE = 2;

    public static final int ALIGN_START = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_END = 2;

    public static final int SHAPE_ROUNDED = 0;
    public static final int SHAPE_RECTANGLE = 1;
    public static final int SHAPE_PILL = 2;
    public static final int SHAPE_ELLIPSE = 3;

    public static final int GRADIENT_HORIZONTAL = 0;
    public static final int GRADIENT_VERTICAL = 1;
    public static final int GRADIENT_DIAGONAL = 2;

    public static final String[] PRESET_LABELS = {
            "خۆکار — قسەهەڵگری ڕەسەن",
            "سپی و ڕەش — کلاسیک",
            "ڕەش و سپی — شەوانە",
            "زەردی گەش",
            "شینی دەریایی",
            "سەوزی بەهاری",
            "پەمەیی نەرم",
            "سپی بێ پاشبنەما + دەوروبەر",
            "ڕەش بێ پاشبنەما + دەوروبەر",
            "ئاڵتوونی",
            "نیۆن",
            "کوردستان"
    };

    public String fontId = FontCatalog.FONT_VAZIRMATN;
    public int fontWeight = 700;
    public boolean italic = false;
    public boolean underline = false;
    public boolean strikethrough = false;
    public float sizeScale = 1.0f;
    public float lineSpacing = 1.02f;
    public int alignment = ALIGN_CENTER;

    public boolean autoTextColor = true;
    public int textColor = Color.BLACK;
    public boolean gradient = false;
    public int gradientColor = Color.rgb(20, 184, 166);
    public int gradientDirection = GRADIENT_HORIZONTAL;
    public int textOpacity = 255;

    public boolean outline = false;
    public int outlineColor = Color.WHITE;
    public float outlineWidthRatio = 0.12f;

    public boolean shadow = false;
    public int shadowColor = Color.BLACK;
    public float shadowStrength = 0.55f;

    public int backgroundMode = BG_AUTO;
    public int backgroundColor = Color.WHITE;
    public int backgroundOpacity = 255;
    public int backgroundShape = SHAPE_ROUNDED;
    public float backgroundRadiusRatio = 0.20f;
    public boolean backgroundBorder = false;
    public int backgroundBorderColor = Color.BLACK;
    public float backgroundBorderWidth = 3f;
    public float paddingScale = 1.0f;

    public float rotationDegrees = 0f;
    public boolean visible = true;
    public boolean eraseOriginal = true;
    public boolean forceErase = false;
    public boolean locked = false;

    public TextStyleSettings() {
    }

    public TextStyleSettings(TextStyleSettings other) {
        copyFrom(other);
    }

    public void copyFrom(TextStyleSettings other) {
        if (other == null) {
            return;
        }
        fontId = other.fontId;
        fontWeight = other.fontWeight;
        italic = other.italic;
        underline = other.underline;
        strikethrough = other.strikethrough;
        sizeScale = other.sizeScale;
        lineSpacing = other.lineSpacing;
        alignment = other.alignment;
        autoTextColor = other.autoTextColor;
        textColor = other.textColor;
        gradient = other.gradient;
        gradientColor = other.gradientColor;
        gradientDirection = other.gradientDirection;
        textOpacity = other.textOpacity;
        outline = other.outline;
        outlineColor = other.outlineColor;
        outlineWidthRatio = other.outlineWidthRatio;
        shadow = other.shadow;
        shadowColor = other.shadowColor;
        shadowStrength = other.shadowStrength;
        backgroundMode = other.backgroundMode;
        backgroundColor = other.backgroundColor;
        backgroundOpacity = other.backgroundOpacity;
        backgroundShape = other.backgroundShape;
        backgroundRadiusRatio = other.backgroundRadiusRatio;
        backgroundBorder = other.backgroundBorder;
        backgroundBorderColor = other.backgroundBorderColor;
        backgroundBorderWidth = other.backgroundBorderWidth;
        paddingScale = other.paddingScale;
        rotationDegrees = other.rotationDegrees;
        visible = other.visible;
        eraseOriginal = other.eraseOriginal;
        forceErase = other.forceErase;
        locked = other.locked;
        sanitize();
    }

    /** Copies the look but preserves this block's position-oriented flags. */
    public void copyVisualFrom(TextStyleSettings other) {
        if (other == null) {
            return;
        }
        float keepRotation = rotationDegrees;
        boolean keepVisible = visible;
        boolean keepErase = eraseOriginal;
        boolean keepForceErase = forceErase;
        boolean keepLocked = locked;
        copyFrom(other);
        rotationDegrees = keepRotation;
        visible = keepVisible;
        eraseOriginal = keepErase;
        forceErase = keepForceErase;
        locked = keepLocked;
    }

    public void applyPreset(int preset) {
        // Every preset starts from a readable, bubble-friendly baseline.
        autoTextColor = false;
        gradient = false;
        outline = false;
        shadow = false;
        backgroundBorder = false;
        backgroundShape = SHAPE_ROUNDED;
        backgroundRadiusRatio = 0.20f;
        backgroundOpacity = 255;
        textOpacity = 255;
        fontWeight = Math.max(fontWeight, 650);
        switch (preset) {
            case 1:
                textColor = Color.rgb(17, 24, 39);
                backgroundMode = BG_CUSTOM;
                backgroundColor = Color.WHITE;
                break;
            case 2:
                textColor = Color.WHITE;
                backgroundMode = BG_CUSTOM;
                backgroundColor = Color.rgb(15, 23, 42);
                break;
            case 3:
                textColor = Color.rgb(69, 26, 3);
                backgroundMode = BG_CUSTOM;
                backgroundColor = Color.rgb(253, 224, 71);
                break;
            case 4:
                textColor = Color.WHITE;
                backgroundMode = BG_CUSTOM;
                backgroundColor = Color.rgb(37, 99, 235);
                break;
            case 5:
                textColor = Color.rgb(5, 46, 22);
                backgroundMode = BG_CUSTOM;
                backgroundColor = Color.rgb(134, 239, 172);
                break;
            case 6:
                textColor = Color.rgb(80, 7, 36);
                backgroundMode = BG_CUSTOM;
                backgroundColor = Color.rgb(251, 207, 232);
                break;
            case 7:
                textColor = Color.WHITE;
                backgroundMode = BG_NONE;
                outline = true;
                outlineColor = Color.BLACK;
                outlineWidthRatio = 0.16f;
                shadow = true;
                break;
            case 8:
                textColor = Color.BLACK;
                backgroundMode = BG_NONE;
                outline = true;
                outlineColor = Color.WHITE;
                outlineWidthRatio = 0.16f;
                break;
            case 9:
                textColor = Color.rgb(253, 224, 71);
                gradient = true;
                gradientColor = Color.rgb(217, 119, 6);
                gradientDirection = GRADIENT_VERTICAL;
                backgroundMode = BG_CUSTOM;
                backgroundColor = Color.rgb(69, 26, 3);
                outline = true;
                outlineColor = Color.rgb(120, 53, 15);
                outlineWidthRatio = 0.08f;
                shadow = true;
                break;
            case 10:
                textColor = Color.rgb(167, 243, 208);
                gradient = true;
                gradientColor = Color.rgb(34, 211, 238);
                backgroundMode = BG_CUSTOM;
                backgroundColor = Color.rgb(2, 6, 23);
                shadow = true;
                shadowColor = Color.rgb(34, 211, 238);
                break;
            case 11:
                textColor = Color.WHITE;
                backgroundMode = BG_CUSTOM;
                backgroundColor = Color.rgb(21, 128, 61);
                backgroundBorder = true;
                backgroundBorderColor = Color.rgb(250, 204, 21);
                backgroundBorderWidth = 4f;
                break;
            case 0:
            default:
                autoTextColor = true;
                textColor = Color.BLACK;
                backgroundMode = BG_AUTO;
                backgroundColor = Color.WHITE;
                break;
        }
        sanitize();
    }

    /** Removes per-block geometry flags before using this object globally. */
    public void prepareAsGlobalDefault() {
        visible = true;
        locked = false;
        eraseOriginal = true;
        forceErase = false;
        rotationDegrees = 0f;
        sanitize();
    }

    public void sanitize() {
        if (fontId == null || fontId.trim().isEmpty()) {
            fontId = FontCatalog.FONT_VAZIRMATN;
        }
        fontWeight = clampInt(fontWeight, 100, 900);
        sizeScale = clampFloat(sizeScale, 0.45f, 2.4f);
        lineSpacing = clampFloat(lineSpacing, 0.75f, 1.8f);
        alignment = clampInt(alignment, ALIGN_START, ALIGN_END);
        gradientDirection = clampInt(gradientDirection, 0, 2);
        textOpacity = clampInt(textOpacity, 20, 255);
        outlineWidthRatio = clampFloat(outlineWidthRatio, 0f, 0.38f);
        shadowStrength = clampFloat(shadowStrength, 0f, 1f);
        backgroundMode = clampInt(backgroundMode, BG_AUTO, BG_NONE);
        backgroundOpacity = clampInt(backgroundOpacity, 0, 255);
        backgroundShape = clampInt(backgroundShape, SHAPE_ROUNDED, SHAPE_ELLIPSE);
        backgroundRadiusRatio = clampFloat(backgroundRadiusRatio, 0f, 0.5f);
        backgroundBorderWidth = clampFloat(backgroundBorderWidth, 0f, 20f);
        paddingScale = clampFloat(paddingScale, 0.35f, 2.2f);
        while (rotationDegrees > 180f) {
            rotationDegrees -= 360f;
        }
        while (rotationDegrees < -180f) {
            rotationDegrees += 360f;
        }
    }

    public JSONObject toJson() throws JSONException {
        sanitize();
        JSONObject json = new JSONObject();
        json.put("fontId", fontId);
        json.put("fontWeight", fontWeight);
        json.put("italic", italic);
        json.put("underline", underline);
        json.put("strikethrough", strikethrough);
        json.put("sizeScale", sizeScale);
        json.put("lineSpacing", lineSpacing);
        json.put("alignment", alignment);
        json.put("autoTextColor", autoTextColor);
        json.put("textColor", textColor);
        json.put("gradient", gradient);
        json.put("gradientColor", gradientColor);
        json.put("gradientDirection", gradientDirection);
        json.put("textOpacity", textOpacity);
        json.put("outline", outline);
        json.put("outlineColor", outlineColor);
        json.put("outlineWidthRatio", outlineWidthRatio);
        json.put("shadow", shadow);
        json.put("shadowColor", shadowColor);
        json.put("shadowStrength", shadowStrength);
        json.put("backgroundMode", backgroundMode);
        json.put("backgroundColor", backgroundColor);
        json.put("backgroundOpacity", backgroundOpacity);
        json.put("backgroundShape", backgroundShape);
        json.put("backgroundRadiusRatio", backgroundRadiusRatio);
        json.put("backgroundBorder", backgroundBorder);
        json.put("backgroundBorderColor", backgroundBorderColor);
        json.put("backgroundBorderWidth", backgroundBorderWidth);
        json.put("paddingScale", paddingScale);
        json.put("rotationDegrees", rotationDegrees);
        json.put("visible", visible);
        json.put("eraseOriginal", eraseOriginal);
        json.put("forceErase", forceErase);
        json.put("locked", locked);
        return json;
    }

    public static TextStyleSettings fromJson(JSONObject json) {
        TextStyleSettings style = new TextStyleSettings();
        if (json == null) {
            return style;
        }
        style.fontId = json.optString("fontId", style.fontId);
        style.fontWeight = json.optInt("fontWeight", style.fontWeight);
        style.italic = json.optBoolean("italic", style.italic);
        style.underline = json.optBoolean("underline", style.underline);
        style.strikethrough = json.optBoolean("strikethrough", style.strikethrough);
        style.sizeScale = (float) json.optDouble("sizeScale", style.sizeScale);
        style.lineSpacing = (float) json.optDouble("lineSpacing", style.lineSpacing);
        style.alignment = json.optInt("alignment", style.alignment);
        style.autoTextColor = json.optBoolean("autoTextColor", style.autoTextColor);
        style.textColor = json.optInt("textColor", style.textColor);
        style.gradient = json.optBoolean("gradient", style.gradient);
        style.gradientColor = json.optInt("gradientColor", style.gradientColor);
        style.gradientDirection = json.optInt("gradientDirection", style.gradientDirection);
        style.textOpacity = json.optInt("textOpacity", style.textOpacity);
        style.outline = json.optBoolean("outline", style.outline);
        style.outlineColor = json.optInt("outlineColor", style.outlineColor);
        style.outlineWidthRatio = (float) json.optDouble(
                "outlineWidthRatio", style.outlineWidthRatio);
        style.shadow = json.optBoolean("shadow", style.shadow);
        style.shadowColor = json.optInt("shadowColor", style.shadowColor);
        style.shadowStrength = (float) json.optDouble(
                "shadowStrength", style.shadowStrength);
        style.backgroundMode = json.optInt("backgroundMode", style.backgroundMode);
        style.backgroundColor = json.optInt("backgroundColor", style.backgroundColor);
        style.backgroundOpacity = json.optInt(
                "backgroundOpacity", style.backgroundOpacity);
        style.backgroundShape = json.optInt("backgroundShape", style.backgroundShape);
        style.backgroundRadiusRatio = (float) json.optDouble(
                "backgroundRadiusRatio", style.backgroundRadiusRatio);
        style.backgroundBorder = json.optBoolean(
                "backgroundBorder", style.backgroundBorder);
        style.backgroundBorderColor = json.optInt(
                "backgroundBorderColor", style.backgroundBorderColor);
        style.backgroundBorderWidth = (float) json.optDouble(
                "backgroundBorderWidth", style.backgroundBorderWidth);
        style.paddingScale = (float) json.optDouble("paddingScale", style.paddingScale);
        style.rotationDegrees = (float) json.optDouble(
                "rotationDegrees", style.rotationDegrees);
        style.visible = json.optBoolean("visible", style.visible);
        style.eraseOriginal = json.optBoolean("eraseOriginal", style.eraseOriginal);
        style.forceErase = json.optBoolean("forceErase", style.forceErase);
        style.locked = json.optBoolean("locked", style.locked);
        style.sanitize();
        return style;
    }

    public static TextStyleSettings fromJsonString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new TextStyleSettings();
        }
        try {
            return fromJson(new JSONObject(value));
        } catch (JSONException ignored) {
            return new TextStyleSettings();
        }
    }

    public String toJsonString() {
        try {
            return toJson().toString();
        } catch (JSONException ignored) {
            return "{}";
        }
    }

    public static int withOpacity(int color, int opacity) {
        int alpha = Math.round(Color.alpha(color) * (clampInt(opacity, 0, 255) / 255f));
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    public static String hex(int color) {
        return String.format("#%02X%02X%02X",
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampFloat(float value, float min, float max) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
