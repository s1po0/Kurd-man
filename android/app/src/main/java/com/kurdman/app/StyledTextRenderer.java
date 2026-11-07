package com.kurdman.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
        /** Kept for source compatibility; rejected layouts are no longer returned. */
        public boolean overflow;
        private BubbleBoundary boundary;
        private String verifiedSignature;
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

        // A moved/styled box never detaches the actual contour. Intersect it
        // with the editable box; fit the complete glyph raster BEFORE erasure.
        BubbleBoundary traced = tracedBoundary(segment);
        boolean hasMask = traced != null;
        BubbleBoundary boundary = (hasMask ? traced : segment.bubbleOval
                ? BubbleBoundary.ellipse(rect) : BubbleBoundary.rectangle(rect)).intersect(rect);
        int safety = Math.min(128, Math.max(2, Math.round(
                Math.min(rect.width(), rect.height()) * .015f * style.paddingScale)));
        BubbleBoundary safeArea = boundary.inset(safety);
        if (safeArea.isEmpty()) return null;
        String signature = fitSignature(segment);

        // Measured line pitch from the OCR line boxes when available;
        // box/lines is only a fallback because ML Kit pads block boxes
        // unevenly, which made fragments typeset at unrelated sizes.
        float sourceLineHeight = segment.sourceLineHeight > 0f
                ? segment.sourceLineHeight
                : segment.box.height()
                        / (float) Math.max(1, segment.lines);
        if (sourceLineHeight <= 0f) {
            sourceLineHeight = Math.max(12f, rect.height());
        }
        // Start near the source's measured line pitch. Bounds/three-row
        // heuristics below only reject obvious failures; native styled ink
        // (including rotation/effects) is the final authority.
        float startFactor = (hasMask && !allowOverflow) ? 1.20f : 0.97f;
        int startSize = clampInt(Math.round(
                sourceLineHeight * startFactor * style.sizeScale), 8, 220);
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
        // stays inside the bubble; only then fall back to a smaller font.
        float[] widthFractions = (segment.bubbleOval || hasMask || Math.abs(style.rotationDegrees) > .01f)
                ? new float[]{1f, 0.92f, 0.84f, 0.76f, 0.68f, 0.60f}
                : new float[]{1f};
        for (int size = startSize; size >= minSize; size--) {
            if (Thread.currentThread().isInterrupted()) return null;
            applyTextSizeAndShadow(paint, style, size);
            for (float fraction : widthFractions) {
                int layoutWidth = Math.max(16, Math.round(width * fraction));
                int effectivePad = horizontalPadding + (width - layoutWidth) / 2;
                StaticLayout layout = buildLayout(
                        segment.ckb.trim(), paint, layoutWidth, style);
                boolean shapeOk = hasMask
                        ? linesInsideInterior(segment, rect, layout,
                                effectivePad, verticalPadding)
                        : (!segment.bubbleOval || linesInsideEllipse(
                                rect, layout, effectivePad, verticalPadding));
                boolean fits = layout.getHeight() <= maxHeight
                        && maxLineWidth(layout) <= layoutWidth + widthTolerance
                        && shapeOk;
                if (fits) {
                    int pad = effectivePad;
                    if (hasMask) {
                        pad = recentredPad(segment, rect, layout,
                                effectivePad, verticalPadding);
                    }
                    FitResult fit = new FitResult();
                    fit.layout = layout;
                    fit.paint = paint;
                    fit.horizontalPadding = pad;
                    fit.verticalPadding = verticalPadding;
                    fit.textSize = size;
                    fit.overflow = false;
                    fit.boundary = boundary;
                    if (inkInside(segment, fit, safeArea)) {
                        fit.verifiedSignature = signature;
                        return fit;
                    }
                    // Recentring is optional; never lose an otherwise safe fit.
                    if (pad != effectivePad) {
                        fit.horizontalPadding = effectivePad;
                        if (inkInside(segment, fit, safeArea)) {
                            fit.verifiedSignature = signature;
                            return fit;
                        }
                    }
                }
                if (layout.getHeight() > maxHeight) {
                    // Narrower layouts only get taller; shrink the font.
                    break;
                }
            }

        }
        return null;
    }

    public static EditedResult renderEdited(Bitmap original, List<Segment> segments,
                                            TypefaceResolver resolver) {
        Bitmap output = original.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(output);
        List<Segment> accepted = new ArrayList<>();
        List<Segment> erase = new ArrayList<>();
        Map<Segment, FitResult> fits = new IdentityHashMap<>();
        int rendered = 0, skipped = 0;
        if (segments != null) {
            for (Segment segment : segments) {
                segment.rendered = false;
                TextStyleSettings style = segment.ensureStyle();
                if (!style.visible || segment.ckb == null || segment.ckb.trim().isEmpty()) {
                    // Preserve the studio's explicit hidden-block erase control.
                    if (!style.visible && style.forceErase) erase.add(segment);
                    skipped++;
                    continue;
                }
                FitResult fit = createFit(segment, resolver, true);
                if (fit == null) { skipped++; continue; }
                accepted.add(segment); erase.add(segment); fits.put(segment, fit);
            }
        }
        // Resolve ALL fits first. An unplaceable translation must not leave a
        // blank balloon in exports; keep the source and the full editable text.
        eraseOriginals(canvas, original, erase);
        for (Segment segment : accepted) drawBackground(canvas, segment);
        for (Segment segment : accepted) {
            if (drawText(canvas, segment, fits.get(segment))) {
                segment.rendered = true; rendered++;
            } else skipped++;
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
        return segment.autoBackgroundReliable || style.forceErase
                || segment.outsideText;
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
        int save = canvas.save();
        clipSource(canvas, segment);
        drawShape(canvas, new RectF(erase),
                effectiveShape(segment, style), radius, fill);
        canvas.restoreToCount(save);
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
        Bitmap patch = segment.outsideText
                ? CleanEraser.createOutsidePatch(
                        source, erase, segment.box, segment.outsideBrightInk)
                : CleanEraser.createPatch(
                        source, erase, segment.detectedBackgroundColor);
        if (patch != null) {
            int save = canvas.save();
            clipSource(canvas, segment);
            canvas.drawBitmap(patch, erase.left, erase.top, null);
            canvas.restoreToCount(save);
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
        drawingBoundary(segment).clip(canvas);
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
        drawingBoundary(segment).clip(canvas);
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
        if (fit == null || fit.layout == null || fit.paint == null
                || fit.verifiedSignature == null
                || !fit.verifiedSignature.equals(fitSignature(segment))) return false;
        int save = canvas.save();
        // Defence in depth only: acceptance already checked the UNCLIPPED ink.
        // This clip is not used to hide/truncate overflowing dialogue.
        fit.boundary.clip(canvas);
        drawUnclipped(canvas, segment, fit);
        canvas.restoreToCount(save);
        return true;
    }

    private static int yOffset(Rect rect, FitResult fit) {
        int available = Math.max(0, rect.height() - fit.verticalPadding * 2);
        return fit.verticalPadding + Math.max(0, (available - fit.layout.getHeight()) / 2);
    }

    static void drawUnclipped(Canvas canvas, Segment segment, FitResult fit) {
        Rect rect = segment.getRenderBox();
        int save = canvas.save();
        rotateAroundRect(canvas, rect, segment.ensureStyle().rotationDegrees);
        canvas.translate(rect.left + fit.horizontalPadding, rect.top + yOffset(rect, fit));
        drawLayout(canvas, segment, fit);
        canvas.restoreToCount(save);
    }

    private static BubbleBoundary tracedBoundary(Segment segment) {
        if (segment.outsideText) return null;
        if (segment.bubbleBoundary != null) return segment.bubbleBoundary;
        if (segment.interiorLeft != null || segment.interiorRight != null) {
            return BubbleBoundary.fromSpans(segment.interiorTop,
                    segment.interiorLeft, segment.interiorRight);
        }
        return null;
    }

    private static BubbleBoundary drawingBoundary(Segment segment) {
        BubbleBoundary traced = tracedBoundary(segment);
        Rect rect = segment.getRenderBox();
        return (traced != null ? traced : segment.bubbleOval
                ? BubbleBoundary.ellipse(rect) : BubbleBoundary.rectangle(rect)).intersect(rect);
    }

    /** Source-space clip, also used by studio's cached clean patches. */
    static void clipSource(Canvas canvas, Segment segment) {
        BubbleBoundary traced = tracedBoundary(segment);
        if (traced != null) traced.clip(canvas);
        else if (segment.bubbleOval) BubbleBoundary.ellipse(segment.getEraseBox()).clip(canvas);
        else canvas.clipRect(segment.getEraseBox());
    }

    /** Shared by studio and final drawing; every geometry/ink-affecting edit invalidates fits. */
    static String fitSignature(Segment segment) {
        TextStyleSettings s = segment.ensureStyle();
        Rect rect = segment.getRenderBox();
        return segment.ckb + "|" + rect.toShortString() + "|" + segment.box.height()
                + "|" + segment.sourceLineHeight + "|" + segment.lines
                + "|" + segment.bubbleOval + "|" + segment.outsideText
                + "|" + (segment.bubbleBoundary == null ? "legacy" : segment.bubbleBoundary.signature())
                + "|" + segment.interiorTop + "|" + Arrays.hashCode(segment.interiorLeft)
                + "|" + Arrays.hashCode(segment.interiorRight)
                + "|" + s.fontId + "|" + s.fontWeight + "|" + s.italic
                + "|" + s.underline + "|" + s.strikethrough + "|" + s.sizeScale
                + "|" + s.lineSpacing + "|" + s.alignment + "|" + s.paddingScale
                + "|" + s.rotationDegrees + "|" + s.autoTextColor + "|" + s.textColor
                + "|" + segment.detectedInkColor + "|" + s.textOpacity
                + "|" + s.shadow + "|" + s.shadowColor + "|" + s.shadowStrength
                + "|" + s.outline + "|" + s.outlineColor + "|" + s.outlineWidthRatio
                + "|" + s.gradient + "|" + s.gradientColor + "|" + s.gradientDirection;
    }

    /** Rasterize exactly the shaped StaticLayout/effects used for final painting. */
    private static boolean inkInside(Segment segment, FitResult fit, BubbleBoundary safeArea) {
        Rect rect = segment.getRenderBox();
        float margin = Math.max(12f, fit.textSize * 2f + 8f);
        RectF area = new RectF(rect.left + fit.horizontalPadding,
                rect.top + yOffset(rect, fit),
                rect.left + fit.horizontalPadding + fit.layout.getWidth(),
                rect.top + yOffset(rect, fit) + fit.layout.getHeight());
        area.inset(-margin, -margin);
        Matrix rotation = new Matrix();
        rotation.setRotate(segment.ensureStyle().rotationDegrees, rect.exactCenterX(), rect.exactCenterY());
        rotation.mapRect(area);
        int left = (int) Math.floor(area.left), top = (int) Math.floor(area.top);
        int width = (int) Math.ceil(area.right) - left, height = (int) Math.ceil(area.bottom) - top;
        if (width <= 0 || height <= 0 || (long) width * height > BubbleBoundary.MAX_PIXELS
                || Thread.currentThread().isInterrupted()) return false;
        Bitmap raster = null;
        try {
            raster = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas ink = new Canvas(raster);
            ink.translate(-left, -top);
            drawUnclipped(ink, segment, fit);
            int[] row = new int[width];
            boolean painted = false;
            for (int y = 0; y < height; y++) {
                if (Thread.currentThread().isInterrupted()) return false;
                raster.getPixels(row, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x++) {
                    if (Color.alpha(row[x]) == 0) continue;
                    painted = true;
                    // Unlike the upstream 128-alpha threshold, keep even the
                    // antialias/blur fringe inside our padded safe region.
                    if (x == 0 || y == 0 || x == width - 1 || y == height - 1
                            || !safeArea.contains(left + x, top + y)) return false;
                }
            }
            return painted || segment.ensureStyle().textOpacity == 0;
        } catch (OutOfMemoryError | RuntimeException unavailable) {
            // Fail closed. Never erase source text on an unverified raster.
            return false;
        } finally {
            if (raster != null) raster.recycle();
        }
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

    /**
     * Cheap prefilter only (NOT proof of containment). The final full styled
     * raster above validates every nontransparent pixel. At
     * the top, middle and bottom of each line's glyph band, the line's
     * left and right ends must stay between that row's interior edges
     * (minus a small safety margin). Coordinates are absolute page
     * coordinates, exactly as {@code drawText} will place the layout.
     */
    private static boolean linesInsideInterior(Segment segment, Rect rect,
                                               StaticLayout layout,
                                               int horizontalPadding,
                                               int verticalPadding) {
        int[] lefts = segment.interiorLeft;
        int[] rights = segment.interiorRight;
        if (lefts == null || rights == null || segment.interiorTop < 0) {
            return true;
        }
        float safetyX = Math.max(3f, rect.width() * 0.015f);
        int availableHeight = Math.max(0, rect.height() - verticalPadding * 2);
        int yOffset = verticalPadding
                + Math.max(0, (availableHeight - layout.getHeight()) / 2);
        float originX = rect.left + horizontalPadding;
        float originY = rect.top + yOffset;
        for (int i = 0; i < layout.getLineCount(); i++) {
            float lineHeight = layout.getLineBottom(i) - layout.getLineTop(i);
            float topY = originY + layout.getLineTop(i) + lineHeight * 0.10f;
            float bottomY = originY + layout.getLineBottom(i) - lineHeight * 0.10f;
            float left = originX + layout.getLineLeft(i);
            float right = originX + layout.getLineRight(i);
            float[] rows = {topY, (topY + bottomY) / 2f, bottomY};
            for (float yy : rows) {
                int row = Math.round(yy) - segment.interiorTop;
                if (row < 0 || row >= lefts.length || lefts[row] < 0) {
                    return false;
                }
                if (left < lefts[row] + safetyX
                        || right > rights[row] - safetyX) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Shifts a fitting layout sideways so its widest line is centred
     * between the traced bubble edges at that line's own height. The
     * body rectangle of an asymmetric or connected balloon is not
     * always centred on the outline, which made text hug one side of
     * big bubbles. The original padding is kept when the shift would
     * break the fit.
     */
    private static int recentredPad(Segment segment, Rect rect,
                                    StaticLayout layout, int pad,
                                    int verticalPadding) {
        int[] lefts = segment.interiorLeft;
        int[] rights = segment.interiorRight;
        if (lefts == null || rights == null || segment.interiorTop < 0
                || layout.getLineCount() == 0) {
            return pad;
        }
        int widest = 0;
        float widestWidth = -1f;
        for (int i = 0; i < layout.getLineCount(); i++) {
            float lineWidth = layout.getLineRight(i) - layout.getLineLeft(i);
            if (lineWidth > widestWidth) {
                widestWidth = lineWidth;
                widest = i;
            }
        }
        int availableHeight = Math.max(0, rect.height() - verticalPadding * 2);
        int yOffset = verticalPadding
                + Math.max(0, (availableHeight - layout.getHeight()) / 2);
        float centreY = rect.top + yOffset
                + (layout.getLineTop(widest) + layout.getLineBottom(widest)) / 2f;
        long leftSum = 0;
        long rightSum = 0;
        int rows = 0;
        for (int dy = -4; dy <= 4; dy += 2) {
            int row = Math.round(centreY) + dy - segment.interiorTop;
            if (row >= 0 && row < lefts.length && lefts[row] >= 0) {
                leftSum += lefts[row];
                rightSum += rights[row];
                rows++;
            }
        }
        if (rows == 0) {
            return pad;
        }
        float spanCentre = (leftSum / (float) rows + rightSum / (float) rows) / 2f;
        float lineCentre = rect.left + pad
                + (layout.getLineLeft(widest) + layout.getLineRight(widest)) / 2f;
        float delta = spanCentre - lineCentre;
        float maxShift = rect.width() * 0.10f;
        if (delta > maxShift) {
            delta = maxShift;
        }
        if (delta < -maxShift) {
            delta = -maxShift;
        }
        int newPad = pad + Math.round(delta);
        if (newPad == pad || newPad < 0) {
            return pad;
        }
        return linesInsideInterior(segment, rect, layout, newPad, verticalPadding)
                ? newPad : pad;
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
