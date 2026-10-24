package com.kurdman.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;

import java.util.List;

/**
 * Draws Kurdish text with the global style or a block's edited style.
 *
 * <p>Android's {@link StaticLayout} performs Arabic-script shaping and RTL
 * layout, so Sorani words remain joined. The automatic renderer still owns
 * bubble detection; this class only measures and paints a block inside the
 * rectangle chosen automatically or moved by the user.</p>
 */
public final class StyledTextRenderer {
    public interface TypefaceResolver {
        Typeface resolve(TextStyleSettings style);
    }

    public static final class FitResult {
        public StaticLayout layout;
        public TextPaint paint;
        public int horizontalPadding;
        public int verticalPadding;
        public float textSize;
        public boolean overflow;
    }

    public static final class EditedResult {
        public final Bitmap bitmap;
        public final int rendered;
        public final int skipped;

        EditedResult(Bitmap bitmap, int rendered, int skipped) {
            this.bitmap = bitmap;
            this.rendered = rendered;
            this.skipped = skipped;
        }
    }

    private StyledTextRenderer() {
    }

    public static FitResult createFit(Segment segment, TypefaceResolver resolver,
                                      boolean allowOverflow) {
        if (segment == null || segment.ckb == null || segment.ckb.trim().isEmpty()) {
            return null;
        }
        TextStyleSettings style = segment.ensureStyle();
        Rect rect = segment.getRenderBox();
        if (rect.width() < 12 || rect.height() < 10) {
            return null;
        }

        int horizontalPadding = clampInt(
                Math.round(rect.width() * 0.065f * style.paddingScale),
                2, Math.max(2, rect.width() / 3));
        int verticalPadding = clampInt(
                Math.round(rect.height() * 0.07f * style.paddingScale),
                2, Math.max(2, rect.height() / 3));
        int width = Math.max(16, rect.width() - horizontalPadding * 2);
        int maxHeight = Math.max(12, rect.height() - verticalPadding * 2);

        float sourceLineHeight = segment.box.height()
                / (float) Math.max(1, segment.lines);
        if (sourceLineHeight <= 0f) {
            sourceLineHeight = Math.max(12f, rect.height());
        }
        int startSize = clampInt(
                Math.round(sourceLineHeight * 0.97f * style.sizeScale), 8, 220);
        int minSize = clampInt(
                Math.round(sourceLineHeight * 0.30f * style.sizeScale), 6, startSize);
        // A box made much larger in the editor should be able to use the
        // extra room. Do not let a tiny OCR line keep a huge manual box at an
        // unreadably small size when the user deliberately enlarged it.
        if (allowOverflow) {
            int boxDrivenStart = clampInt(
                    Math.round(rect.height() * 0.55f * style.sizeScale), 8, 220);
            startSize = Math.max(startSize, Math.min(boxDrivenStart, startSize * 2));
            minSize = Math.min(minSize, startSize);
        }

        TextPaint paint = createBasePaint(segment, resolver);
        float widthTolerance = Math.max(2f, width * 0.02f);
        // A round bubble inside this rectangle has much less room near its
        // top and bottom. Narrow the layout step by step (recentring it via
        // the padding, like real scanlation typesetting) until every line
        // stays inside the ellipse; only then fall back to a smaller font.
        float[] widthFractions = segment.bubbleOval
                ? new float[]{1f, 0.92f, 0.84f, 0.76f, 0.68f, 0.60f}
                : new float[]{1f};
        for (int size = startSize; size >= minSize; size--) {
            applyTextSizeAndShadow(paint, style, size);
            StaticLayout fallbackLayout = null;
            int fallbackPad = horizontalPadding;
            for (float fraction : widthFractions) {
                int layoutWidth = Math.max(16, Math.round(width * fraction));
                int effectivePad = horizontalPadding + (width - layoutWidth) / 2;
                StaticLayout layout = buildLayout(
                        segment.ckb.trim(), paint, layoutWidth, style);
                fallbackLayout = layout;
                fallbackPad = effectivePad;
                boolean fits = layout.getHeight() <= maxHeight
                        && maxLineWidth(layout) <= layoutWidth + widthTolerance
                        && (!segment.bubbleOval || linesInsideEllipse(
                                rect, layout, effectivePad, verticalPadding));
                if (fits) {
                    FitResult fit = new FitResult();
                    fit.layout = layout;
                    fit.paint = paint;
                    fit.horizontalPadding = effectivePad;
                    fit.verticalPadding = verticalPadding;
                    fit.textSize = size;
                    fit.overflow = false;
                    return fit;
                }
                if (layout.getHeight() > maxHeight) {
                    // Narrower layouts only get taller; shrink the font.
                    break;
                }
            }
            if (allowOverflow && size == minSize && fallbackLayout != null) {
                FitResult fit = new FitResult();
                fit.layout = fallbackLayout;
                fit.paint = paint;
                fit.horizontalPadding = fallbackPad;
                fit.verticalPadding = verticalPadding;
                fit.textSize = size;
                fit.overflow = true;
                return fit;
            }
        }
        return null;
    }

    public static EditedResult renderEdited(Bitmap original, List<Segment> segments,
                                            TypefaceResolver resolver) {
        Bitmap output = original.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(output);
        eraseOriginals(canvas, original, segments);

        int rendered = 0;
        int skipped = 0;
        if (segments != null) {
            for (Segment segment : segments) {
                segment.rendered = false;
                if (!segment.ensureStyle().visible
                        || segment.ckb == null || segment.ckb.trim().isEmpty()) {
                    skipped++;
                }
            }
            // Paint all backgrounds before all text so a moved box cannot
            // cover a neighbour's already-drawn Kurdish words.
            for (Segment segment : segments) {
                if (segment.ensureStyle().visible
                        && segment.ckb != null && !segment.ckb.trim().isEmpty()) {
                    drawBackground(canvas, segment);
                }
            }
            for (Segment segment : segments) {
                if (!segment.ensureStyle().visible
                        || segment.ckb == null || segment.ckb.trim().isEmpty()) {
                    continue;
                }
                FitResult fit = createFit(segment, resolver, true);
                if (fit == null) {
                    skipped++;
                    continue;
                }
                drawText(canvas, segment, fit);
                segment.rendered = true;
                rendered++;
            }
        }
        return new EditedResult(output, rendered, skipped);
    }

    public static void eraseOriginals(Canvas canvas, List<Segment> segments) {
        if (segments == null) {
            return;
        }
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        for (Segment segment : segments) {
            eraseOriginal(canvas, segment, fill);
        }
    }

    public static void eraseOriginal(Canvas canvas, Segment segment) {
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        eraseOriginal(canvas, segment, fill);
    }

    /** Whether the source text of this segment may be erased at all. */
    static boolean shouldEraseOriginal(Segment segment) {
        TextStyleSettings style = segment.ensureStyle();
        if (!style.eraseOriginal) {
            return false;
        }
        if (!style.visible && !style.forceErase) {
            return false;
        }
        return segment.autoBackgroundReliable || style.forceErase;
    }

    private static void eraseOriginal(Canvas canvas, Segment segment, Paint fill) {
        TextStyleSettings style = segment.ensureStyle();
        if (style.backgroundMode == TextStyleSettings.BG_CLEAN) {
            // Clean mode removes the glyphs themselves; see
            // applyCleanErase / the editor's cached patches. Painting a
            // solid block here would recreate the visible white patch.
            return;
        }
        if (!shouldEraseOriginal(segment)) {
            return;
        }
        int eraseColor = segment.detectedBackgroundColor;
        if (!segment.autoBackgroundReliable
                && style.backgroundMode == TextStyleSettings.BG_CUSTOM) {
            eraseColor = style.backgroundColor;
        }
        fill.setColor(Color.argb(255,
                Color.red(eraseColor), Color.green(eraseColor), Color.blue(eraseColor)));
        Rect erase = segment.getEraseBox();
        float radius = backgroundRadius(style, erase);
        drawShape(canvas, new RectF(erase),
                effectiveShape(segment, style), radius, fill);
    }

    /**
     * Erases one segment using its style: clean segments get a
     * glyph-level inpainted patch taken from {@code source}, everything
     * else keeps the classic detected-colour fill.
     */
    public static void eraseOriginal(Canvas canvas, Bitmap source, Segment segment) {
        if (segment.ensureStyle().backgroundMode == TextStyleSettings.BG_CLEAN) {
            applyCleanErase(canvas, source, segment);
        } else {
            eraseOriginal(canvas, segment);
        }
    }

    /** Bitmap-aware variant used by the export/final render paths. */
    public static void eraseOriginals(Canvas canvas, Bitmap source,
                                      List<Segment> segments) {
        if (segments == null) {
            return;
        }
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        for (Segment segment : segments) {
            if (segment.ensureStyle().backgroundMode
                    == TextStyleSettings.BG_CLEAN) {
                applyCleanErase(canvas, source, segment);
            } else {
                eraseOriginal(canvas, segment, fill);
            }
        }
    }

    /**
     * Draws the clean-erased patch of one segment. The patch pixels match
     * the surrounding image exactly outside the glyphs, so no rectangle
     * edge is visible.
     */
    public static void applyCleanErase(Canvas canvas, Bitmap source,
                                       Segment segment) {
        if (canvas == null || source == null || source.isRecycled()
                || segment.ensureStyle().backgroundMode
                        != TextStyleSettings.BG_CLEAN
                || !shouldEraseOriginal(segment)) {
            return;
        }
        Rect erase = new Rect(segment.getEraseBox());
        if (!erase.intersect(0, 0, source.getWidth(), source.getHeight())) {
            return;
        }
        Bitmap patch = CleanEraser.createPatch(
                source, erase, segment.detectedBackgroundColor);
        if (patch != null) {
            canvas.drawBitmap(patch, erase.left, erase.top, null);
            patch.recycle();
        }
    }

    public static void drawBackground(Canvas canvas, Segment segment) {
        TextStyleSettings style = segment.ensureStyle();
        if (style.backgroundMode == TextStyleSettings.BG_NONE
                || style.backgroundMode == TextStyleSettings.BG_CLEAN
                || style.backgroundOpacity <= 0) {
            drawBorderOnly(canvas, segment, style);
            return;
        }
        int color = style.backgroundMode == TextStyleSettings.BG_CUSTOM
                ? style.backgroundColor : segment.detectedBackgroundColor;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(TextStyleSettings.withOpacity(color, style.backgroundOpacity));

        Rect rect = segment.getRenderBox();
        int shape = effectiveShape(segment, style);
        canvas.save();
        rotateAroundRect(canvas, rect, style.rotationDegrees);
        drawShape(canvas, new RectF(rect), shape,
                backgroundRadius(style, rect), fill);
        drawBorder(canvas, rect, style, shape);
        canvas.restore();
    }

    private static void drawBorderOnly(Canvas canvas, Segment segment,
                                       TextStyleSettings style) {
        if (!style.backgroundBorder) {
            return;
        }
        Rect rect = segment.getRenderBox();
        canvas.save();
        rotateAroundRect(canvas, rect, style.rotationDegrees);
        drawBorder(canvas, rect, style, effectiveShape(segment, style));
        canvas.restore();
    }

    private static void drawBorder(Canvas canvas, Rect rect,
                                   TextStyleSettings style, int shape) {
        if (!style.backgroundBorder || style.backgroundBorderWidth <= 0f) {
            return;
        }
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setColor(TextStyleSettings.withOpacity(
                style.backgroundBorderColor, Math.max(80, style.backgroundOpacity)));
        border.setStrokeWidth(style.backgroundBorderWidth);
        RectF inset = new RectF(rect);
        float half = style.backgroundBorderWidth / 2f;
        inset.inset(half, half);
        drawShape(canvas, inset, shape,
                backgroundRadius(style, rect), border);
    }

    public static boolean drawText(Canvas canvas, Segment segment, FitResult fit) {
        if (fit == null || fit.layout == null || fit.paint == null) {
            return false;
        }
        TextStyleSettings style = segment.ensureStyle();
        Rect rect = segment.getRenderBox();
        int availableHeight = Math.max(0, rect.height() - fit.verticalPadding * 2);
        int yOffset = fit.verticalPadding
                + Math.max(0, (availableHeight - fit.layout.getHeight()) / 2);

        canvas.save();
        rotateAroundRect(canvas, rect, style.rotationDegrees);
        canvas.translate(rect.left + fit.horizontalPadding, rect.top + yOffset);
        drawLayout(canvas, segment, fit);
        canvas.restore();
        return true;
    }

    private static void drawLayout(Canvas canvas, Segment segment, FitResult fit) {
        TextStyleSettings style = segment.ensureStyle();
        TextPaint paint = fit.paint;
        int originalColor = paint.getColor();
        Shader originalShader = paint.getShader();
        Paint.Style originalStyle = paint.getStyle();
        float originalStrokeWidth = paint.getStrokeWidth();

        if (style.outline && style.outlineWidthRatio > 0f) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, fit.textSize * style.outlineWidthRatio));
            paint.setColor(TextStyleSettings.withOpacity(
                    style.outlineColor, style.textOpacity));
            paint.clearShadowLayer();
            fit.layout.draw(canvas);
            applyTextSizeAndShadow(paint, style, fit.textSize);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(originalStrokeWidth);
        paint.setColor(originalColor);
        paint.setShader(style.gradient
                ? createGradient(segment, style, fit.layout) : originalShader);
        fit.layout.draw(canvas);
        paint.setShader(originalShader);
        paint.setStyle(originalStyle);
        paint.setColor(originalColor);
        paint.setStrokeWidth(originalStrokeWidth);
    }

    private static Shader createGradient(Segment segment, TextStyleSettings style,
                                         StaticLayout layout) {
        int startColor = TextStyleSettings.withOpacity(
                style.autoTextColor ? segment.detectedInkColor : style.textColor,
                style.textOpacity);
        int endColor = TextStyleSettings.withOpacity(
                style.gradientColor, style.textOpacity);
        float width = Math.max(1f, layout.getWidth());
        float height = Math.max(1f, layout.getHeight());
        float x1 = 0f;
        float y1 = 0f;
        if (style.gradientDirection == TextStyleSettings.GRADIENT_HORIZONTAL) {
            // Sorani is RTL, so the visual gradient runs from right to left.
            return new LinearGradient(width, 0f, 0f, 0f,
                    startColor, endColor, Shader.TileMode.CLAMP);
        }
        if (style.gradientDirection == TextStyleSettings.GRADIENT_VERTICAL) {
            y1 = height;
        } else {
            x1 = width;
            y1 = height;
        }
        return new LinearGradient(0f, 0f, x1, y1,
                startColor, endColor, Shader.TileMode.CLAMP);
    }

    private static TextPaint createBasePaint(Segment segment, TypefaceResolver resolver) {
        TextStyleSettings style = segment.ensureStyle();
        TextPaint paint = new TextPaint(
                Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        int color = style.autoTextColor ? segment.detectedInkColor : style.textColor;
        paint.setColor(TextStyleSettings.withOpacity(color, style.textOpacity));
        Typeface typeface = resolver == null ? null : resolver.resolve(style);
        if (typeface == null) {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
        }
        paint.setTypeface(typeface);
        paint.setFakeBoldText(style.fontWeight >= 800 && !typeface.isBold());
        paint.setTextSkewX(style.italic ? -0.16f : 0f);
        paint.setUnderlineText(style.underline);
        paint.setStrikeThruText(style.strikethrough);
        return paint;
    }

    private static void applyTextSizeAndShadow(TextPaint paint,
                                               TextStyleSettings style, float size) {
        paint.setTextSize(size);
        if (style.shadow && style.shadowStrength > 0f) {
            float radius = Math.max(1f, size * 0.10f * style.shadowStrength);
            float dx = size * 0.035f;
            float dy = size * 0.05f;
            int alpha = Math.round(220f * style.shadowStrength);
            int shadow = Color.argb(alpha,
                    Color.red(style.shadowColor),
                    Color.green(style.shadowColor),
                    Color.blue(style.shadowColor));
            paint.setShadowLayer(radius, dx, dy, shadow);
        } else {
            paint.clearShadowLayer();
        }
    }

    private static StaticLayout buildLayout(String text, TextPaint paint, int width,
                                            TextStyleSettings style) {
        Layout.Alignment alignment;
        if (style.alignment == TextStyleSettings.ALIGN_CENTER) {
            alignment = Layout.Alignment.ALIGN_CENTER;
        } else if (style.alignment == TextStyleSettings.ALIGN_END) {
            alignment = Layout.Alignment.ALIGN_OPPOSITE;
        } else {
            alignment = Layout.Alignment.ALIGN_NORMAL;
        }
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(alignment)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setLineSpacing(0f, style.lineSpacing)
                .setIncludePad(false)
                .build();
    }

    private static void drawShape(Canvas canvas, RectF rect, int shape,
                                  float radius, Paint paint) {
        if (shape == TextStyleSettings.SHAPE_ELLIPSE) {
            canvas.drawOval(rect, paint);
        } else if (shape == TextStyleSettings.SHAPE_RECTANGLE) {
            canvas.drawRect(rect, paint);
        } else {
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }

    private static float backgroundRadius(TextStyleSettings style, Rect rect) {
        float minSide = Math.min(rect.width(), rect.height());
        if (style.backgroundShape == TextStyleSettings.SHAPE_PILL) {
            return minSide / 2f;
        }
        if (style.backgroundShape == TextStyleSettings.SHAPE_RECTANGLE) {
            return 0f;
        }
        return Math.max(0f, minSide * style.backgroundRadiusRatio);
    }

    private static void rotateAroundRect(Canvas canvas, Rect rect, float degrees) {
        if (Math.abs(degrees) > 0.01f) {
            canvas.rotate(degrees, rect.exactCenterX(), rect.exactCenterY());
        }
    }

    /**
     * A detected round bubble is always painted as an ellipse so no square
     * corners can stick out of it, whatever shape the style asks for.
     */
    private static int effectiveShape(Segment segment, TextStyleSettings style) {
        return segment.bubbleOval
                ? TextStyleSettings.SHAPE_ELLIPSE : style.backgroundShape;
    }

    /**
     * Checks every laid-out line against the ellipse inscribed in the
     * render rectangle (shrunk by a small safety margin, since the grown
     * rectangle can overshoot the true border by a few pixels). The text
     * block is vertically centred exactly as {@code drawText} centres it.
     */
    private static boolean linesInsideEllipse(Rect rect, StaticLayout layout,
                                              int horizontalPadding,
                                              int verticalPadding) {
        float safety = Math.max(3f, Math.min(rect.width(), rect.height()) * 0.035f);
        float rx = rect.width() / 2f - safety;
        float ry = rect.height() / 2f - safety;
        if (rx <= 4f || ry <= 4f) {
            return false;
        }
        float cx = rect.exactCenterX();
        float cy = rect.exactCenterY();
        int availableHeight = Math.max(0, rect.height() - verticalPadding * 2);
        int yOffset = verticalPadding
                + Math.max(0, (availableHeight - layout.getHeight()) / 2);
        float originX = rect.left + horizontalPadding;
        float originY = rect.top + yOffset;
        for (int i = 0; i < layout.getLineCount(); i++) {
            float lineHalfHeight =
                    (layout.getLineBottom(i) - layout.getLineTop(i)) * 0.40f;
            float lineCentreY = originY
                    + (layout.getLineTop(i) + layout.getLineBottom(i)) / 2f;
            float left = originX + layout.getLineLeft(i);
            float right = originX + layout.getLineRight(i);
            if (!insideEllipse(left, lineCentreY - lineHalfHeight, cx, cy, rx, ry)
                    || !insideEllipse(left, lineCentreY + lineHalfHeight, cx, cy, rx, ry)
                    || !insideEllipse(right, lineCentreY - lineHalfHeight, cx, cy, rx, ry)
                    || !insideEllipse(right, lineCentreY + lineHalfHeight, cx, cy, rx, ry)) {
                return false;
            }
        }
        return true;
    }

    private static boolean insideEllipse(float x, float y, float cx, float cy,
                                         float rx, float ry) {
        float dx = (x - cx) / rx;
        float dy = (y - cy) / ry;
        return dx * dx + dy * dy <= 1f;
    }

    private static float maxLineWidth(StaticLayout layout) {
        float max = 0f;
        for (int i = 0; i < layout.getLineCount(); i++) {
            max = Math.max(max, layout.getLineWidth(i));
        }
        return max;
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
