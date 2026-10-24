package com.kurdman.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** On-device OCR (tiled for long webtoons) and conservative text rendering. */
public class ManhwaRenderer {
    public interface Progress {
        void onProgress(String message);
    }

    /** A second, independent line recognizer (PP-OCR in MainActivity). */
    public interface SecondaryOcr {
        List<LineText> recognizeLines(Bitmap page, List<Rect> boxes) throws Exception;
    }

    public static final class LineText {
        public final String text;
        public final float confidence;

        public LineText(String text, float confidence) {
            this.text = text == null ? "" : text;
            this.confidence = confidence;
        }
    }

    public static class RenderResult {
        public final Bitmap bitmap;
        public final int rendered;
        public final int skippedBackground;
        public final int skippedFit;
        public final int skippedNoTranslation;

        public RenderResult(Bitmap bitmap, int rendered, int skippedBackground,
                            int skippedFit, int skippedNoTranslation) {
            this.bitmap = bitmap;
            this.rendered = rendered;
            this.skippedBackground = skippedBackground;
            this.skippedFit = skippedFit;
            this.skippedNoTranslation = skippedNoTranslation;
        }

        public int skipped() {
            return skippedBackground + skippedFit + skippedNoTranslation;
        }
    }

    private static class Background {
        int color = Color.WHITE;
        int inkColor = Color.BLACK;
        boolean reliable = false;
        Rect region;
        double dominantFraction;
        boolean enclosed;
    }

    private static class DominantColor {
        int color;
        double fraction;
    }

    private static final class OcrLineData {
        String text;
        Rect box;
        float confidence = -1f;

        OcrLineData(String text, Rect box, float confidence) {
            this.text = text == null ? "" : text;
            this.box = box == null ? new Rect() : new Rect(box);
            this.confidence = confidence;
        }

        OcrLineData copy() {
            return new OcrLineData(text, box, confidence);
        }
    }

    private static final class OcrCandidate {
        Segment segment;
        final List<OcrLineData> lines = new ArrayList<>();
        float confidence = -1f;
        double score;
        int pass;
        boolean refined;
        boolean disagreement;

        OcrCandidate(Segment segment, int pass) {
            this.segment = segment;
            this.pass = pass;
        }
    }

    private static class Fit {
        StaticLayout layout;
        int horizontalPadding;
        int verticalPadding;
    }

    private static class Placement {
        Segment segment;
        Rect region;
        Background background;
        StyledTextRenderer.FitResult fit;
    }

    private static TextRecognizer createRecognizer(String sourceLanguage) {
        if ("ko".equals(sourceLanguage)) {
            return TextRecognition.getClient(
                    new KoreanTextRecognizerOptions.Builder().build());
        }
        if ("ja".equals(sourceLanguage)) {
            return TextRecognition.getClient(
                    new JapaneseTextRecognizerOptions.Builder().build());
        }
        if (sourceLanguage != null && sourceLanguage.startsWith("zh")) {
            return TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build());
        }
        return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    public static List<Segment> recognize(Bitmap original, String sourceLanguage,
                                          Progress progress) throws Exception {
        return recognize(original, sourceLanguage, progress, true, null);
    }

    /**
     * High-accuracy OCR used by the app.
     *
     * <p>The old implementation asked ML Kit once per page tile. That was
     * fast, but a single misread stylised manhwa word went straight into
     * translation. This version keeps ML Kit's reliable on-device detector,
     * reads each tile again after contrast/upscale preprocessing, re-reads
     * weak bubbles from enlarged crops, scores the competing readings with
     * ML Kit word confidence, and can then ask PP-OCR to re-read individual
     * lines as an independent second opinion.</p>
     */
    public static List<Segment> recognize(Bitmap original, String sourceLanguage,
                                          Progress progress,
                                          boolean highAccuracy,
                                          SecondaryOcr secondaryOcr) throws Exception {
        if (original == null) {
            throw new IllegalArgumentException("No image selected");
        }
        int width = original.getWidth();
        int height = original.getHeight();
        TextRecognizer recognizer = createRecognizer(sourceLanguage);
        List<OcrCandidate> candidates = new ArrayList<>();

        try {
            int tileHeight = height <= 1500 ? height : 1200;
            int overlap = height <= tileHeight ? 0 : 220;
            int step = tileHeight - overlap;
            int tileCount = height <= tileHeight
                    ? 1
                    : ((height - tileHeight + step - 1) / step) + 1;
            int tileIndex = 0;

            for (int y0 = 0; ; y0 += step) {
                JobRuntime.throwIfStopped();
                tileIndex++;
                int y1 = Math.min(height, y0 + tileHeight);
                if (progress != null) {
                    progress.onProgress((highAccuracy
                            ? "خوێندنەوەی وردی دەق (OCR) "
                            : "خوێندنەوەی دەق (OCR) ")
                            + tileIndex + "/" + tileCount + "…");
                }

                Bitmap tile = Bitmap.createBitmap(original, 0, y0, width, y1 - y0);
                Text visionText = Tasks.await(
                        recognizer.process(InputImage.fromBitmap(tile, 0)));
                JobRuntime.throwIfStopped();

                int coreTop = y0 == 0 ? 0 : y0 + (overlap / 2);
                int coreBottom = y1 == height ? height : y1 - (overlap / 2);
                candidates.addAll(extractCandidates(
                        visionText, 0, y0, 1f, width, height,
                        coreTop, coreBottom, 0, true));

                if (highAccuracy) {
                    float enhancedScale = enhancedScaleFor(tile);
                    double enhancedPixels = (double) tile.getWidth() * enhancedScale
                            * tile.getHeight() * enhancedScale;
                    Bitmap enhanced = enhancedPixels <= 9_000_000.0
                            ? createScaledVariant(tile, enhancedScale, true) : null;
                    if (enhanced != null && enhanced != tile) {
                        try {
                            Text enhancedText = Tasks.await(recognizer.process(
                                    InputImage.fromBitmap(enhanced, 0)));
                            JobRuntime.throwIfStopped();
                            candidates.addAll(extractCandidates(
                                    enhancedText, 0, y0, 1f / enhancedScale,
                                    width, height, coreTop, coreBottom, 1, true));
                        } finally {
                            enhanced.recycle();
                        }
                    }
                }

                if (y1 == height) {
                    break;
                }
            }

            List<OcrCandidate> selected = mergeCandidates(candidates);
            if (highAccuracy && !selected.isEmpty()) {
                refineCandidates(original, recognizer, selected, progress);
            }
            if (highAccuracy && secondaryOcr != null && !selected.isEmpty()) {
                applySecondaryOcr(original, selected, secondaryOcr, progress);
            }

            List<Segment> result = new ArrayList<>();
            for (OcrCandidate candidate : selected) {
                candidate.segment.ocrConfidence = candidate.confidence;
                candidate.segment.lines = Math.max(1,
                        candidate.lines.isEmpty()
                                ? candidate.segment.lines : candidate.lines.size());
                if (isUsableText(candidate.segment.text)) {
                    result.add(candidate.segment);
                }
            }
            sortReadingOrder(result);
            return result;
        } finally {
            try {
                recognizer.close();
            } catch (Exception ignored) {
                // The recognizer is process-scoped; failing to close is not fatal here.
            }
        }
    }

    public static RenderResult render(Bitmap original, List<Segment> segments) {
        return render(original, segments, new TextStyleSettings(),
                style -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
    }

    public static RenderResult render(Bitmap original, List<Segment> segments,
                                      TextStyleSettings globalStyle,
                                      StyledTextRenderer.TypefaceResolver resolver) {
        Bitmap output = original.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(output);
        TextStyleSettings initialStyle = new TextStyleSettings(
                globalStyle == null ? new TextStyleSettings() : globalStyle);
        initialStyle.prepareAsGlobalDefault();

        int skippedBackground = 0;
        int skippedFit = 0;
        int skippedNoTranslation = 0;
        List<Placement> placements = new ArrayList<>();

        for (Segment segment : segments) {
            segment.rendered = false;
            segment.style = new TextStyleSettings(initialStyle);
            segment.ensureStyle().visible = true;
            if (segment.originalCkb == null || segment.originalCkb.trim().isEmpty()) {
                segment.originalCkb = segment.ckb == null ? "" : segment.ckb;
            }

            Rect seed = expandBox(segment.box, original.getWidth(), original.getHeight());
            segment.renderBox = new Rect(seed);
            segment.eraseBox = new Rect(seed);
            if (segment.ckb == null || segment.ckb.trim().isEmpty()) {
                segment.ensureStyle().visible = false;
                skippedNoTranslation++;
                continue;
            }

            Background bg = analyzeBubble(original, segment, seed);
            Rect region = bg.region;
            if (!bg.reliable) {
                // Preserve pages/boxes the previous conservative test handled.
                Background legacy = sampleBackground(original, seed);
                if (legacy.reliable) {
                    bg = legacy;
                    region = seed;
                }
            }
            if (region == null || region.width() <= 0 || region.height() <= 0) {
                region = seed;
            }
            segment.detectedBackgroundColor = bg.color;
            segment.detectedInkColor = bg.inkColor;
            segment.autoBackgroundReliable = bg.reliable;
            segment.renderBox = new Rect(region);
            segment.eraseBox = new Rect(region);

            if (!bg.reliable) {
                // The block remains available in the editor, but automatic
                // rendering stays conservative around faces/artwork.
                segment.ensureStyle().visible = false;
                skippedBackground++;
                continue;
            }

            StyledTextRenderer.FitResult fit = StyledTextRenderer.createFit(
                    segment, resolver, false);
            if (fit == null) {
                segment.ensureStyle().visible = false;
                skippedFit++;
                continue;
            }

            Placement placement = new Placement();
            placement.segment = segment;
            placement.region = new Rect(region);
            placement.background = bg;
            placement.fit = fit;
            placements.add(placement);
        }

        // Clean every accepted source-text area first, then paint styled
        // backgrounds, and only then draw Kurdish text. This keeps nearby
        // bubbles from painting over words that were already placed.
        for (Placement placement : placements) {
            StyledTextRenderer.eraseOriginal(canvas, placement.segment);
        }
        for (Placement placement : placements) {
            StyledTextRenderer.drawBackground(canvas, placement.segment);
        }

        int rendered = 0;
        for (Placement placement : placements) {
            StyledTextRenderer.drawText(canvas, placement.segment, placement.fit);
            placement.segment.rendered = true;
            placement.segment.ensureStyle().visible = true;
            rendered++;
        }
        return new RenderResult(output, rendered, skippedBackground,
                skippedFit, skippedNoTranslation);
    }

    private static Rect expandBox(Rect box, int imageWidth, int imageHeight) {
        int padX = Math.max(10, Math.round(box.width() * 0.18f));
        int padY = Math.max(8, Math.round(box.height() * 0.30f));
        return new Rect(
                Math.max(0, box.left - padX),
                Math.max(0, box.top - padY),
                Math.min(imageWidth, box.right + padX),
                Math.min(imageHeight, box.bottom + padY));
    }

    /**
     * Estimates the bubble/narration fill colour from the text box itself and
     * then grows the rectangle until a contrasting bubble border or artwork
     * edge is found. The old renderer used only the OCR text rectangle, so a
     * longer Sorani translation often did not fit and the English was left in
     * place even though the speech bubble itself had enough room.
     */
    private static Background analyzeBubble(Bitmap original, Segment segment, Rect seed) {
        Background bg = new Background();
        bg.region = new Rect(seed);

        DominantColor seedDominant = estimateDominantColor(original, seed);
        if (seedDominant == null || seedDominant.fraction < 0.24) {
            return bg;
        }
        bg.color = seedDominant.color;
        bg.inkColor = luminance(bg.color) < 150 ? Color.WHITE : Color.BLACK;
        bg.dominantFraction = seedDominant.fraction;

        Rect grown = growRegion(original, seed, segment.box, bg);
        bg.region = grown;

        DominantColor finalDominant = estimateDominantColor(original, grown);
        if (finalDominant != null && finalDominant.fraction >= 0.24
                && colorDistanceSum(finalDominant.color, bg.color) <= 190) {
            bg.color = finalDominant.color;
            bg.dominantFraction = finalDominant.fraction;
        } else {
            bg.dominantFraction = fractionSimilar(original, grown, bg.color);
        }
        bg.inkColor = luminance(bg.color) < 150 ? Color.WHITE : Color.BLACK;
        bg.reliable = seedDominant.fraction >= 0.28
                && bg.dominantFraction >= 0.34
                && (bg.enclosed || seedDominant.fraction >= 0.48
                || bg.dominantFraction >= 0.55);
        return bg;
    }

    private static Rect growRegion(Bitmap original, Rect seed, Rect textBox, Background bg) {
        Rect region = new Rect(seed);
        int maxPadX = Math.max(28, Math.round(textBox.width() * 0.78f) + 36);
        int maxPadY = Math.max(24, Math.round(textBox.height() * 0.90f) + 32);
        int limitLeft = Math.max(0, textBox.left - maxPadX);
        int limitRight = Math.min(original.getWidth(), textBox.right + maxPadX);
        int limitTop = Math.max(0, textBox.top - maxPadY);
        int limitBottom = Math.min(original.getHeight(), textBox.bottom + maxPadY);
        int step = 4;

        boolean leftDone = region.left <= limitLeft;
        boolean rightDone = region.right >= limitRight;
        boolean topDone = region.top <= limitTop;
        boolean bottomDone = region.bottom >= limitBottom;
        boolean leftCap = leftDone && limitLeft > 0;
        boolean rightCap = rightDone && limitRight < original.getWidth();
        boolean topCap = topDone && limitTop > 0;
        boolean bottomCap = bottomDone && limitBottom < original.getHeight();
        int centerX = clampInt(Math.round(textBox.exactCenterX()), 0, original.getWidth() - 1);
        int centerY = clampInt(Math.round(textBox.exactCenterY()), 0, original.getHeight() - 1);

        while (!(leftDone && rightDone && topDone && bottomDone)) {
            boolean moved = false;

            if (!leftDone) {
                int candidate = Math.max(limitLeft, region.left - step);
                if (candidate < region.left && verticalStripMatches(
                        original, candidate, region.left - 1, region, bg.color, centerY)) {
                    region.left = candidate;
                    moved = true;
                    if (candidate == limitLeft) {
                        leftDone = true;
                        leftCap = limitLeft > 0;
                    }
                } else {
                    leftDone = true;
                }
            }

            if (!rightDone) {
                int candidate = Math.min(limitRight, region.right + step);
                if (candidate > region.right && verticalStripMatches(
                        original, region.right, candidate - 1, region, bg.color, centerY)) {
                    region.right = candidate;
                    moved = true;
                    if (candidate == limitRight) {
                        rightDone = true;
                        rightCap = limitRight < original.getWidth();
                    }
                } else {
                    rightDone = true;
                }
            }

            if (!topDone) {
                int candidate = Math.max(limitTop, region.top - step);
                if (candidate < region.top && horizontalStripMatches(
                        original, candidate, region.top - 1, region, bg.color, centerX)) {
                    region.top = candidate;
                    moved = true;
                    if (candidate == limitTop) {
                        topDone = true;
                        topCap = limitTop > 0;
                    }
                } else {
                    topDone = true;
                }
            }

            if (!bottomDone) {
                int candidate = Math.min(limitBottom, region.bottom + step);
                if (candidate > region.bottom && horizontalStripMatches(
                        original, region.bottom, candidate - 1, region, bg.color, centerX)) {
                    region.bottom = candidate;
                    moved = true;
                    if (candidate == limitBottom) {
                        bottomDone = true;
                        bottomCap = limitBottom < original.getHeight();
                    }
                } else {
                    bottomDone = true;
                }
            }

            if (!moved) {
                break;
            }
        }

        int stoppedSides = 0;
        if (!leftCap) stoppedSides++;
        if (!rightCap) stoppedSides++;
        if (!topCap) stoppedSides++;
        if (!bottomCap) stoppedSides++;
        bg.enclosed = stoppedSides >= 3;
        return region;
    }

    private static boolean verticalStripMatches(Bitmap bitmap, int fromX, int toX,
                                                Rect region, int bgColor, int centerY) {
        if (toX < fromX) {
            return false;
        }
        // On an oval bubble the side border may touch only the centre of a
        // vertical sample line. Treat a strong contrasting pixel on the
        // centre ray as the border even if the page beyond it is also pale.
        for (int x = Math.max(0, fromX); x <= Math.min(bitmap.getWidth() - 1, toX); x++) {
            for (int dy = -1; dy <= 1; dy++) {
                int y = clampInt(centerY + dy, 0, bitmap.getHeight() - 1);
                if (isStrongBoundary(bitmap.getPixel(x, y), bgColor)) {
                    return false;
                }
            }
        }
        int inset = Math.max(2, Math.round(region.height() * 0.22f));
        int y0 = region.top + inset;
        int y1 = region.bottom - inset - 1;
        if (y1 < y0) {
            y0 = region.top + (region.height() / 2);
            y1 = y0;
        }
        y0 = clampInt(y0, 0, bitmap.getHeight() - 1);
        y1 = clampInt(y1, 0, bitmap.getHeight() - 1);
        int yStep = Math.max(2, Math.max(1, (y1 - y0) / 18));

        int total = 0;
        int similar = 0;
        for (int x = Math.max(0, fromX); x <= Math.min(bitmap.getWidth() - 1, toX); x++) {
            int lineTotal = 0;
            int lineSimilar = 0;
            for (int y = y0; y <= y1; y += yStep) {
                lineTotal++;
                total++;
                if (similarColor(bitmap.getPixel(x, y), bgColor, 150)) {
                    lineSimilar++;
                    similar++;
                }
            }
            // A single nearly-opposite line is usually the bubble border;
            // do not jump across it just because the page beyond is similar.
            if (lineTotal >= 4 && (lineSimilar / (double) lineTotal) < 0.42) {
                return false;
            }
        }
        return total >= 8 && (similar / (double) total) >= 0.58;
    }

    private static boolean horizontalStripMatches(Bitmap bitmap, int fromY, int toY,
                                                  Rect region, int bgColor, int centerX) {
        if (toY < fromY) {
            return false;
        }
        for (int y = Math.max(0, fromY); y <= Math.min(bitmap.getHeight() - 1, toY); y++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = clampInt(centerX + dx, 0, bitmap.getWidth() - 1);
                if (isStrongBoundary(bitmap.getPixel(x, y), bgColor)) {
                    return false;
                }
            }
        }
        int inset = Math.max(2, Math.round(region.width() * 0.22f));
        int x0 = region.left + inset;
        int x1 = region.right - inset - 1;
        if (x1 < x0) {
            x0 = region.left + (region.width() / 2);
            x1 = x0;
        }
        x0 = clampInt(x0, 0, bitmap.getWidth() - 1);
        x1 = clampInt(x1, 0, bitmap.getWidth() - 1);
        int xStep = Math.max(2, Math.max(1, (x1 - x0) / 18));

        int total = 0;
        int similar = 0;
        for (int y = Math.max(0, fromY); y <= Math.min(bitmap.getHeight() - 1, toY); y++) {
            int lineTotal = 0;
            int lineSimilar = 0;
            for (int x = x0; x <= x1; x += xStep) {
                lineTotal++;
                total++;
                if (similarColor(bitmap.getPixel(x, y), bgColor, 150)) {
                    lineSimilar++;
                    similar++;
                }
            }
            if (lineTotal >= 4 && (lineSimilar / (double) lineTotal) < 0.42) {
                return false;
            }
        }
        return total >= 8 && (similar / (double) total) >= 0.58;
    }

    private static boolean isStrongBoundary(int pixel, int bgColor) {
        return colorDistanceSum(pixel, bgColor) > 210
                && Math.abs(luminance(pixel) - luminance(bgColor)) > 85;
    }

    private static DominantColor estimateDominantColor(Bitmap bitmap, Rect rect) {
        if (rect == null || rect.width() <= 0 || rect.height() <= 0) {
            return null;
        }
        Rect safe = new Rect(rect);
        if (!safe.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight())) {
            return null;
        }
        double area = (double) safe.width() * (double) safe.height();
        int step = Math.max(2, (int) Math.ceil(Math.sqrt(area / 1000.0)));
        Map<Integer, int[]> bins = new HashMap<>();
        int total = 0;

        for (int y = safe.top; y < safe.bottom; y += step) {
            for (int x = safe.left; x < safe.right; x += step) {
                int pixel = bitmap.getPixel(x, y);
                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);
                int key = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
                int[] data = bins.get(key);
                if (data == null) {
                    data = new int[4];
                    bins.put(key, data);
                }
                data[0]++;
                data[1] += r;
                data[2] += g;
                data[3] += b;
                total++;
            }
        }
        if (total == 0 || bins.isEmpty()) {
            return null;
        }

        int[] best = null;
        for (int[] data : bins.values()) {
            if (best == null || data[0] > best[0]) {
                best = data;
            }
        }
        if (best == null || best[0] <= 0) {
            return null;
        }
        int candidate = Color.rgb(best[1] / best[0], best[2] / best[0], best[3] / best[0]);

        int similar = 0;
        long sumR = 0;
        long sumG = 0;
        long sumB = 0;
        for (int y = safe.top; y < safe.bottom; y += step) {
            for (int x = safe.left; x < safe.right; x += step) {
                int pixel = bitmap.getPixel(x, y);
                if (similarColor(pixel, candidate, 125)) {
                    similar++;
                    sumR += Color.red(pixel);
                    sumG += Color.green(pixel);
                    sumB += Color.blue(pixel);
                }
            }
        }

        DominantColor result = new DominantColor();
        if (similar > 0) {
            result.color = Color.rgb((int) (sumR / similar),
                    (int) (sumG / similar), (int) (sumB / similar));
            result.fraction = similar / (double) total;
        } else {
            result.color = candidate;
            result.fraction = best[0] / (double) total;
        }
        return result;
    }

    private static double fractionSimilar(Bitmap bitmap, Rect rect, int bgColor) {
        if (rect == null || rect.width() <= 0 || rect.height() <= 0) {
            return 0.0;
        }
        Rect safe = new Rect(rect);
        if (!safe.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight())) {
            return 0.0;
        }
        double area = (double) safe.width() * (double) safe.height();
        int step = Math.max(2, (int) Math.ceil(Math.sqrt(area / 800.0)));
        int total = 0;
        int similar = 0;
        for (int y = safe.top; y < safe.bottom; y += step) {
            for (int x = safe.left; x < safe.right; x += step) {
                total++;
                if (similarColor(bitmap.getPixel(x, y), bgColor, 125)) {
                    similar++;
                }
            }
        }
        return total == 0 ? 0.0 : similar / (double) total;
    }

    private static boolean similarColor(int pixel, int color, int toleranceSum) {
        return colorDistanceSum(pixel, color) <= toleranceSum;
    }

    private static int colorDistanceSum(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b))
                + Math.abs(Color.green(a) - Color.green(b))
                + Math.abs(Color.blue(a) - Color.blue(b));
    }

    private static int luminance(int color) {
        return Math.round(0.299f * Color.red(color)
                + 0.587f * Color.green(color)
                + 0.114f * Color.blue(color));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Only overwrite a text area when the pixels around it look like a fairly
     * flat bubble/narration background. Varied artwork is left untouched and
     * the translation is still shown in the text list.
     */
    private static Background sampleBackground(Bitmap original, Rect rect) {
        Background bg = new Background();
        List<Integer> reds = new ArrayList<>();
        List<Integer> greens = new ArrayList<>();
        List<Integer> blues = new ArrayList<>();
        List<Integer> lums = new ArrayList<>();

        int step = Math.max(3, Math.min(rect.width(), rect.height()) / 10);
        int left = Math.max(0, rect.left - 3);
        int right = Math.min(original.getWidth() - 1, rect.right + 2);
        int top = Math.max(0, rect.top - 3);
        int bottom = Math.min(original.getHeight() - 1, rect.bottom + 2);

        for (int x = rect.left; x < rect.right; x += step) {
            addSample(original, x, top, reds, greens, blues, lums);
            addSample(original, x, bottom, reds, greens, blues, lums);
        }
        for (int y = rect.top; y < rect.bottom; y += step) {
            addSample(original, left, y, reds, greens, blues, lums);
            addSample(original, right, y, reds, greens, blues, lums);
        }

        if (lums.size() < 8) {
            return bg;
        }

        Collections.sort(reds);
        Collections.sort(greens);
        Collections.sort(blues);
        Collections.sort(lums);
        int mid = lums.size() / 2;
        bg.color = Color.rgb(reds.get(mid), greens.get(mid), blues.get(mid));
        int medianLum = lums.get(mid);
        bg.inkColor = medianLum < 140 ? Color.WHITE : Color.BLACK;

        double mean = 0;
        for (int lum : lums) {
            mean += lum;
        }
        mean /= lums.size();
        double variance = 0;
        int nearMedian = 0;
        for (int lum : lums) {
            double diff = lum - mean;
            variance += diff * diff;
            if (Math.abs(lum - medianLum) <= 35) {
                nearMedian++;
            }
        }
        double std = Math.sqrt(variance / lums.size());
        bg.reliable = std <= 55 || (nearMedian / (double) lums.size()) >= 0.62;
        return bg;
    }

    private static void addSample(Bitmap bitmap, int x, int y,
                                  List<Integer> reds, List<Integer> greens,
                                  List<Integer> blues, List<Integer> lums) {
        if (x < 0 || y < 0 || x >= bitmap.getWidth() || y >= bitmap.getHeight()) {
            return;
        }
        int pixel = bitmap.getPixel(x, y);
        int r = Color.red(pixel);
        int g = Color.green(pixel);
        int b = Color.blue(pixel);
        reds.add(r);
        greens.add(g);
        blues.add(b);
        lums.add(Math.round(0.299f * r + 0.587f * g + 0.114f * b));
    }

    /**
     * Android's StaticLayout shapes Arabic-script Sorani natively, so words
     * stay joined without a manual reshaper.
     */
    private static Fit fitText(String text, Rect rect, int inkColor, Segment segment) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(inkColor);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));

        int horizontalPadding = clampInt(Math.round(rect.width() * 0.065f), 4, 20);
        int verticalPadding = clampInt(Math.round(rect.height() * 0.07f), 3, 16);
        int width = Math.max(24, rect.width() - (horizontalPadding * 2));
        int maxHeight = Math.max(16, rect.height() - (verticalPadding * 2));
        float originalLineHeight = segment.box.height() / (float) Math.max(1, segment.lines);
        int startSize = clampInt(Math.round(originalLineHeight * 0.97f), 12, 84);
        int minSize = clampInt(Math.round(originalLineHeight * 0.38f), 8, startSize);

        Fit fit = tryFitText(text, paint, width, maxHeight, startSize, minSize,
                1.02f, horizontalPadding, verticalPadding);
        if (fit != null) {
            return fit;
        }
        // One tighter retry rescues translations that only just overflowed the
        // bubble height; before this, those bubbles were left in English.
        return tryFitText(text, paint, width, maxHeight, startSize, minSize,
                0.98f, horizontalPadding, verticalPadding);
    }

    private static Fit tryFitText(String text, TextPaint paint, int width, int maxHeight,
                                  int startSize, int minSize, float lineSpacing,
                                  int horizontalPadding, int verticalPadding) {
        float widthTolerance = Math.max(2f, width * 0.02f);
        for (int size = startSize; size >= minSize; size--) {
            paint.setTextSize(size);
            StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setLineSpacing(0f, lineSpacing)
                    .setIncludePad(false)
                    .build();
            if (layout.getHeight() <= maxHeight
                    && maxLineWidth(layout) <= width + widthTolerance) {
                Fit fit = new Fit();
                fit.layout = layout;
                fit.horizontalPadding = horizontalPadding;
                fit.verticalPadding = verticalPadding;
                return fit;
            }
        }
        return null;
    }

    private static float maxLineWidth(StaticLayout layout) {
        float max = 0f;
        for (int i = 0; i < layout.getLineCount(); i++) {
            max = Math.max(max, layout.getLineWidth(i));
        }
        return max;
    }

    private static List<OcrCandidate> extractCandidates(
            Text visionText,
            int offsetX,
            int offsetY,
            float coordinateScale,
            int imageWidth,
            int imageHeight,
            int coreTop,
            int coreBottom,
            int pass,
            boolean filterCore) {
        List<OcrCandidate> out = new ArrayList<>();
        if (visionText == null || visionText.getTextBlocks() == null) {
            return out;
        }

        for (Text.TextBlock block : visionText.getTextBlocks()) {
            List<OcrLineData> lines = new ArrayList<>();
            Rect lineUnion = new Rect();
            boolean hasLineUnion = false;
            if (block.getLines() != null) {
                for (Text.Line line : block.getLines()) {
                    Rect lineBox = transformRect(line.getBoundingBox(),
                            offsetX, offsetY, coordinateScale,
                            imageWidth, imageHeight);
                    String lineText = cleanText(line.getText());
                    if (lineBox == null || lineText.isEmpty()
                            || letterOrDigitCount(lineText) == 0) {
                        continue;
                    }
                    lines.add(new OcrLineData(
                            lineText, lineBox, lineConfidence(line)));
                    if (!hasLineUnion) {
                        lineUnion.set(lineBox);
                        hasLineUnion = true;
                    } else {
                        lineUnion.union(lineBox);
                    }
                }
            }

            Rect blockBox = transformRect(block.getBoundingBox(),
                    offsetX, offsetY, coordinateScale, imageWidth, imageHeight);
            if (blockBox == null && hasLineUnion) {
                blockBox = new Rect(lineUnion);
            }
            if (blockBox == null) {
                continue;
            }
            if (hasLineUnion) {
                blockBox.union(lineUnion);
            }

            String blockText = cleanText(block.getText());
            if (isUsableText(blockText)) {
                if (filterCore && !centerInsideCore(blockBox, coreTop, coreBottom)) {
                    continue;
                }
                Segment segment = new Segment(blockText, blockBox,
                        Math.max(1, lines.isEmpty() ? 1 : lines.size()));
                OcrCandidate candidate = new OcrCandidate(segment, pass);
                if (lines.isEmpty()) {
                    candidate.lines.add(new OcrLineData(blockText, blockBox, -1f));
                } else {
                    candidate.lines.addAll(lines);
                }
                refreshCandidate(candidate, false);
                out.add(candidate);
                continue;
            }

            // A whole block can be rejected because of punctuation or one
            // noisy line; keep any clean individual lines as candidates.
            for (OcrLineData line : lines) {
                if (!isUsableText(line.text)
                        || (filterCore && !centerInsideCore(
                                line.box, coreTop, coreBottom))) {
                    continue;
                }
                Segment segment = new Segment(line.text, line.box, 1);
                OcrCandidate candidate = new OcrCandidate(segment, pass);
                candidate.lines.add(line.copy());
                refreshCandidate(candidate, false);
                out.add(candidate);
            }
        }
        return out;
    }

    private static Rect transformRect(Rect source, int offsetX, int offsetY,
                                      float coordinateScale,
                                      int imageWidth, int imageHeight) {
        if (source == null || source.width() <= 0 || source.height() <= 0
                || coordinateScale <= 0f) {
            return null;
        }
        Rect out = new Rect(
                offsetX + Math.round(source.left * coordinateScale),
                offsetY + Math.round(source.top * coordinateScale),
                offsetX + Math.round(source.right * coordinateScale),
                offsetY + Math.round(source.bottom * coordinateScale));
        if (!out.intersect(0, 0, imageWidth, imageHeight)
                || out.width() <= 0 || out.height() <= 0) {
            return null;
        }
        return out;
    }

    private static boolean centerInsideCore(Rect box, int coreTop, int coreBottom) {
        float centerY = box.exactCenterY();
        return centerY >= coreTop && centerY < coreBottom;
    }

    private static float lineConfidence(Text.Line line) {
        try {
            if (line == null || line.getElements() == null) {
                return -1f;
            }
            double weighted = 0.0;
            int weights = 0;
            boolean anyPositive = false;
            for (Text.Element element : line.getElements()) {
                float confidence = element.getConfidence();
                if (confidence > 0f) {
                    anyPositive = true;
                }
                int weight = Math.max(1,
                        element.getText() == null ? 1 : element.getText().length());
                weighted += confidence * weight;
                weights += weight;
            }
            // ML Kit reports 0 when confidence is unavailable on some
            // bundled-model/device combinations, which is not the same as a
            // genuinely certain-to-be-wrong reading.
            return anyPositive && weights > 0
                    ? (float) (weighted / (double) weights) : -1f;
        } catch (RuntimeException ignored) {
            return -1f;
        }
    }

    private static float weightedConfidence(List<OcrLineData> lines) {
        double weighted = 0.0;
        int weights = 0;
        boolean any = false;
        for (OcrLineData line : lines) {
            if (line.confidence < 0f) {
                continue;
            }
            int weight = Math.max(1, letterOrDigitCount(line.text));
            weighted += line.confidence * weight;
            weights += weight;
            any = true;
        }
        return any && weights > 0 ? (float) (weighted / (double) weights) : -1f;
    }

    private static void refreshCandidate(OcrCandidate candidate, boolean rebuildText) {
        if (candidate == null || candidate.segment == null) {
            return;
        }
        if (rebuildText && !candidate.lines.isEmpty()) {
            StringBuilder joined = new StringBuilder();
            Rect union = new Rect(candidate.segment.box);
            for (OcrLineData line : candidate.lines) {
                if (joined.length() > 0) {
                    joined.append('\n');
                }
                joined.append(line.text);
                union.union(line.box);
            }
            String rebuilt = cleanText(joined.toString());
            if (!rebuilt.isEmpty()) {
                candidate.segment.text = rebuilt;
            }
            candidate.segment.lines = Math.max(1, candidate.lines.size());
            candidate.segment.box.set(union);
            candidate.segment.renderBox.set(union);
            candidate.segment.eraseBox.set(union);
            candidate.confidence = weightedConfidence(candidate.lines);
        } else {
            float computed = weightedConfidence(candidate.lines);
            if (computed >= 0f) {
                candidate.confidence = computed;
            }
        }
        candidate.segment.ocrConfidence = candidate.confidence;
        candidate.score = candidateScore(
                candidate.segment.text, candidate.confidence, candidate.pass);
    }

    private static double candidateScore(String text, float confidence, int pass) {
        if (text == null) {
            return -100.0;
        }
        int lettersDigits = 0;
        int nonSpace = 0;
        int weird = 0;
        int repeatedPenalty = 0;
        int previous = -1;
        int run = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (!Character.isWhitespace(codePoint)) {
                nonSpace++;
            }
            if (Character.isLetterOrDigit(codePoint)) {
                lettersDigits++;
            } else if (!Character.isWhitespace(codePoint)
                    && ".,!?…:;'\"“”‘’()[]{}<>-–—/\\%&*+=#@~^_|".indexOf(codePoint) < 0) {
                weird++;
            }
            if (codePoint == previous) {
                run++;
                if (run > 3) {
                    repeatedPenalty++;
                }
            } else {
                previous = codePoint;
                run = 1;
            }
            offset += Character.charCount(codePoint);
        }
        double ratio = nonSpace == 0 ? 0.0 : lettersDigits / (double) nonSpace;
        double score = confidence >= 0f ? confidence * 100.0 : 54.0;
        score += Math.min(18.0, lettersDigits * 0.45);
        score += ratio * 12.0;
        score -= weird * 5.0;
        score -= repeatedPenalty * 1.5;
        if (lettersDigits <= 1) {
            score -= 4.0;
        }
        if (pass == 1) {
            score += 2.0;
        } else if (pass == 2) {
            score += 6.0;
        } else if (pass >= 3) {
            score += 8.0;
        }
        return score;
    }

    private static double lineScore(String text, float confidence, int pass) {
        return candidateScore(text, confidence, pass);
    }

    private static int letterOrDigitCount(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) {
                count++;
            }
            offset += Character.charCount(codePoint);
        }
        return count;
    }

    private static List<OcrCandidate> mergeCandidates(List<OcrCandidate> input) {
        List<OcrCandidate> sorted = new ArrayList<>(input);
        Collections.sort(sorted, new Comparator<OcrCandidate>() {
            @Override
            public int compare(OcrCandidate a, OcrCandidate b) {
                return Double.compare(b.score, a.score);
            }
        });
        List<OcrCandidate> selected = new ArrayList<>();
        for (OcrCandidate candidate : sorted) {
            OcrCandidate duplicate = null;
            for (OcrCandidate existing : selected) {
                if (isDuplicateCandidate(existing, candidate)) {
                    duplicate = existing;
                    break;
                }
            }
            if (duplicate == null) {
                selected.add(candidate);
            } else {
                mergeIntoCandidate(duplicate, candidate);
            }
        }
        return selected;
    }

    private static boolean isDuplicateCandidate(OcrCandidate a, OcrCandidate b) {
        Rect first = a.segment.box;
        Rect second = b.segment.box;
        if (iou(first, second) > 0.36f) {
            return true;
        }
        Rect intersection = new Rect(first);
        if (!intersection.intersect(second)) {
            String firstText = comparisonText(a.segment.text);
            String secondText = comparisonText(b.segment.text);
            if (!firstText.isEmpty() && firstText.equals(secondText)) {
                float dy = Math.abs(first.exactCenterY() - second.exactCenterY());
                return dy <= Math.max(28f,
                        Math.min(first.height(), second.height()));
            }
            return false;
        }
        double overlap = (double) intersection.width() * intersection.height();
        double minArea = Math.min(
                (double) first.width() * first.height(),
                (double) second.width() * second.height());
        double overlapOfSmaller = minArea <= 0 ? 0 : overlap / minArea;
        if (overlapOfSmaller < 0.52) {
            return false;
        }
        double similarity = textSimilarity(a.segment.text, b.segment.text);
        if (similarity >= 0.52) {
            return true;
        }
        float centerDistanceY = Math.abs(
                first.exactCenterY() - second.exactCenterY());
        return overlapOfSmaller >= 0.82
                && centerDistanceY <= Math.max(18f,
                        Math.min(first.height(), second.height()) * 0.75f);
    }

    private static void mergeIntoCandidate(OcrCandidate existing,
                                           OcrCandidate challenger) {
        if (existing == null || challenger == null || existing == challenger) {
            return;
        }
        existing.pass = Math.max(existing.pass, challenger.pass);
        existing.refined = existing.refined || challenger.refined;
        if (textSimilarity(existing.segment.text, challenger.segment.text) < 0.92) {
            existing.disagreement = true;
        }
        boolean linesChanged = false;

        if (!existing.lines.isEmpty() && !challenger.lines.isEmpty()) {
            boolean[] used = new boolean[existing.lines.size()];
            for (OcrLineData challengerLine : challenger.lines) {
                int bestIndex = -1;
                float bestIou = 0f;
                for (int i = 0; i < existing.lines.size(); i++) {
                    if (used[i]) {
                        continue;
                    }
                    float value = iou(existing.lines.get(i).box, challengerLine.box);
                    if (value > bestIou) {
                        bestIou = value;
                        bestIndex = i;
                    }
                }
                if (bestIndex < 0 || bestIou < 0.16f) {
                    continue;
                }
                used[bestIndex] = true;
                OcrLineData existingLine = existing.lines.get(bestIndex);
                double challengerScore = lineScore(
                        challengerLine.text, challengerLine.confidence,
                        challenger.pass);
                double existingScore = lineScore(
                        existingLine.text, existingLine.confidence, existing.pass);
                if (challengerScore >= existingScore + 3.0) {
                    existing.lines.set(bestIndex, challengerLine.copy());
                    linesChanged = true;
                } else if (comparisonText(existingLine.text).equals(
                        comparisonText(challengerLine.text))) {
                    existingLine.confidence = Math.max(
                            existingLine.confidence, challengerLine.confidence);
                    linesChanged = true;
                }
            }
        }

        if (!linesChanged && challenger.score >= existing.score + 5.0) {
            int existingLength = letterOrDigitCount(existing.segment.text);
            int challengerLength = letterOrDigitCount(challenger.segment.text);
            double lengthRatio = existingLength == 0 ? 0
                    : Math.min(existingLength, challengerLength)
                            / (double) Math.max(existingLength, challengerLength);
            if (lengthRatio >= 0.45) {
                existing.lines.clear();
                for (OcrLineData line : challenger.lines) {
                    existing.lines.add(line.copy());
                }
                existing.segment.text = challenger.segment.text;
                linesChanged = true;
            }
        }

        Rect union = new Rect(existing.segment.box);
        union.union(challenger.segment.box);
        existing.segment.box.set(union);
        existing.segment.renderBox.set(union);
        existing.segment.eraseBox.set(union);
        if (!linesChanged) {
            existing.confidence = Math.max(
                    existing.confidence, challenger.confidence);
        }
        refreshCandidate(existing, linesChanged);
    }

    private static String comparisonText(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        String lowered = text.toLowerCase(java.util.Locale.ROOT);
        for (int offset = 0; offset < lowered.length();) {
            int codePoint = lowered.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) {
                out.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return out.toString();
    }

    private static double textSimilarity(String firstText, String secondText) {
        String first = comparisonText(firstText);
        String second = comparisonText(secondText);
        if (first.isEmpty() || second.isEmpty()) {
            return 0.0;
        }
        if (first.equals(second)) {
            return 1.0;
        }
        if (first.contains(second) || second.contains(first)) {
            return Math.min(first.length(), second.length())
                    / (double) Math.max(first.length(), second.length());
        }
        if (first.length() > 240) {
            first = first.substring(0, 240);
        }
        if (second.length() > 240) {
            second = second.substring(0, 240);
        }
        int[][] distance = new int[first.length() + 1][second.length() + 1];
        for (int i = 0; i <= first.length(); i++) {
            distance[i][0] = i;
        }
        for (int j = 0; j <= second.length(); j++) {
            distance[0][j] = j;
        }
        for (int i = 1; i <= first.length(); i++) {
            for (int j = 1; j <= second.length(); j++) {
                int cost = first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1;
                distance[i][j] = Math.min(
                        Math.min(distance[i - 1][j] + 1, distance[i][j - 1] + 1),
                        distance[i - 1][j - 1] + cost);
            }
        }
        int maxLength = Math.max(first.length(), second.length());
        return maxLength == 0 ? 1.0
                : 1.0 - (distance[first.length()][second.length()]
                        / (double) maxLength);
    }

    private static float enhancedScaleFor(Bitmap tile) {
        float scale = tile.getWidth() <= 900 ? 2f
                : (tile.getWidth() <= 1400 ? 1.6f : 1.3f);
        while (scale > 1.2f
                && (double) tile.getWidth() * scale
                        * tile.getHeight() * scale > 9_000_000.0) {
            scale -= 0.15f;
        }
        return Math.max(1.2f, scale);
    }

    private static Bitmap createScaledVariant(Bitmap source, float scale,
                                              boolean enhance) {
        if (source == null || scale <= 0f) {
            return null;
        }
        int targetWidth = Math.max(1, Math.round(source.getWidth() * scale));
        int targetHeight = Math.max(1, Math.round(source.getHeight() * scale));
        if (targetWidth == source.getWidth()
                && targetHeight == source.getHeight() && !enhance) {
            return source;
        }
        Bitmap output = Bitmap.createBitmap(
                targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        if (enhance) {
            ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(0f);
            ColorMatrix contrast = new ColorMatrix(new float[]{
                    1.28f, 0f, 0f, 0f, -22f,
                    0f, 1.28f, 0f, 0f, -22f,
                    0f, 0f, 1.28f, 0f, -22f,
                    0f, 0f, 0f, 1f, 0f});
            matrix.postConcat(contrast);
            paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        }
        canvas.drawBitmap(source,
                new Rect(0, 0, source.getWidth(), source.getHeight()),
                new RectF(0f, 0f, targetWidth, targetHeight), paint);
        return output;
    }

    private static void refineCandidates(Bitmap original,
                                         TextRecognizer recognizer,
                                         List<OcrCandidate> selected,
                                         Progress progress) throws Exception {
        List<OcrCandidate> weak = new ArrayList<>();
        for (OcrCandidate candidate : selected) {
            if (needsRefinement(candidate)) {
                weak.add(candidate);
            }
        }
        Collections.sort(weak, new Comparator<OcrCandidate>() {
            @Override
            public int compare(OcrCandidate a, OcrCandidate b) {
                return Float.compare(a.confidence, b.confidence);
            }
        });
        int limit = Math.min(weak.size(), 90);
        int done = 0;
        for (int index = 0; index < limit; index++) {
            JobRuntime.throwIfStopped();
            OcrCandidate candidate = weak.get(index);
            Rect cropRect = paddedCropRect(candidate.segment.box, original);
            if (cropRect == null) {
                continue;
            }
            done++;
            if (progress != null) {
                progress.onProgress("وردکردنەوەی OCR بۆ دەقە لاوازەکان "
                        + done + "/" + limit + "…");
            }

            Bitmap crop = Bitmap.createBitmap(original,
                    cropRect.left, cropRect.top,
                    cropRect.width(), cropRect.height());
            float averageLineHeight = candidate.segment.box.height()
                    / (float) Math.max(1, candidate.lines.size());
            float scale = 52f / Math.max(8f, averageLineHeight);
            scale = Math.max(1.25f, Math.min(3.5f, scale));
            while (scale > 1.25f
                    && (double) cropRect.width() * scale
                            * cropRect.height() * scale > 7_000_000.0) {
                scale -= 0.2f;
            }

            List<OcrCandidate> cropCandidates = new ArrayList<>();
            for (int variant = 0; variant < 2; variant++) {
                Bitmap input = createScaledVariant(
                        crop, scale, variant == 1);
                if (input == null) {
                    continue;
                }
                try {
                    Text cropText = Tasks.await(recognizer.process(
                            InputImage.fromBitmap(input, 0)));
                    JobRuntime.throwIfStopped();
                    cropCandidates.addAll(extractCandidates(
                            cropText,
                            cropRect.left, cropRect.top, 1f / scale,
                            original.getWidth(), original.getHeight(),
                            0, original.getHeight(), 2, false));
                } finally {
                    if (input != crop) {
                        input.recycle();
                    }
                }
            }

            for (OcrCandidate cropCandidate : mergeCandidates(cropCandidates)) {
                if (isRelevantCropCandidate(candidate, cropCandidate)) {
                    mergeIntoCandidate(candidate, cropCandidate);
                }
            }
            candidate.refined = true;
            refreshCandidate(candidate, true);
        }
    }

    private static boolean needsRefinement(OcrCandidate candidate) {
        if (candidate.disagreement) {
            return true;
        }
        if (candidate.confidence >= 0f && candidate.confidence < 0.93f) {
            return true;
        }
        for (OcrLineData line : candidate.lines) {
            if (line.confidence >= 0f && line.confidence < 0.90f) {
                return true;
            }
        }
        float averageLineHeight = candidate.segment.box.height()
                / (float) Math.max(1, candidate.lines.size());
        return averageLineHeight < 28f || isSuspiciousText(candidate.segment.text);
    }

    private static boolean isSuspiciousText(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        int lettersDigits = 0;
        int nonSpace = 0;
        int weird = 0;
        int latinLetters = 0;
        int latinVowels = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (!Character.isWhitespace(codePoint)) {
                nonSpace++;
            }
            if (Character.isLetterOrDigit(codePoint)) {
                lettersDigits++;
            } else if (!Character.isWhitespace(codePoint)
                    && ".,!?…:;'\"“”‘’()[]{}<>-–—/\\%&*+=#@~^_|".indexOf(codePoint) < 0) {
                weird++;
            }
            if ((codePoint >= 'A' && codePoint <= 'Z')
                    || (codePoint >= 'a' && codePoint <= 'z')) {
                latinLetters++;
                int lower = Character.toLowerCase(codePoint);
                if (lower == 'a' || lower == 'e' || lower == 'i'
                        || lower == 'o' || lower == 'u' || lower == 'y') {
                    latinVowels++;
                }
            }
            offset += Character.charCount(codePoint);
        }
        if (nonSpace > 0 && weird / (double) nonSpace > 0.16) {
            return true;
        }
        if (text.indexOf('\uFFFD') >= 0 || text.indexOf('|') >= 0) {
            return true;
        }
        return latinLetters >= 6 && latinVowels / (double) latinLetters < 0.07;
    }

    private static Rect paddedCropRect(Rect box, Bitmap original) {
        if (box == null || box.width() < 10 || box.height() < 7
                || box.width() > 2600 || box.height() > 1900) {
            return null;
        }
        int padX = Math.max(14, Math.round(box.width() * 0.16f));
        int padY = Math.max(12, Math.round(box.height() * 0.38f));
        Rect out = new Rect(
                Math.max(0, box.left - padX),
                Math.max(0, box.top - padY),
                Math.min(original.getWidth(), box.right + padX),
                Math.min(original.getHeight(), box.bottom + padY));
        return out.width() > 0 && out.height() > 0 ? out : null;
    }

    private static boolean isRelevantCropCandidate(OcrCandidate original,
                                                   OcrCandidate crop) {
        Rect expanded = new Rect(original.segment.box);
        int padX = Math.max(18, Math.round(expanded.width() * 0.22f));
        int padY = Math.max(14, Math.round(expanded.height() * 0.30f));
        expanded.inset(-padX, -padY);
        if (expanded.contains(
                Math.round(crop.segment.box.exactCenterX()),
                Math.round(crop.segment.box.exactCenterY()))) {
            return true;
        }
        if (iou(original.segment.box, crop.segment.box) > 0.08f) {
            return true;
        }
        Rect intersection = new Rect(original.segment.box);
        if (!intersection.intersect(crop.segment.box)) {
            return false;
        }
        double overlap = (double) intersection.width() * intersection.height();
        double minArea = Math.min(
                (double) original.segment.box.width()
                        * original.segment.box.height(),
                (double) crop.segment.box.width()
                        * crop.segment.box.height());
        return minArea > 0 && overlap / minArea > 0.25;
    }

    private static void applySecondaryOcr(Bitmap original,
                                          List<OcrCandidate> selected,
                                          SecondaryOcr secondaryOcr,
                                          Progress progress) throws Exception {
        List<Rect> boxes = new ArrayList<>();
        List<OcrCandidate> owners = new ArrayList<>();
        List<Integer> lineIndexes = new ArrayList<>();
        for (OcrCandidate candidate : selected) {
            if (candidate.lines.isEmpty()) {
                candidate.lines.add(new OcrLineData(
                        candidate.segment.text, candidate.segment.box,
                        candidate.confidence));
            }
            for (int i = 0; i < candidate.lines.size() && boxes.size() < 180; i++) {
                OcrLineData line = candidate.lines.get(i);
                Rect box = line.box;
                boolean needsSecondOpinion = candidate.disagreement
                        || line.confidence < 0f
                        || line.confidence < 0.96f
                        || box.height() < 30
                        || isSuspiciousText(line.text);
                if (!needsSecondOpinion) {
                    continue;
                }
                // PP-OCR's recognizer expects one roughly horizontal line.
                if (box.width() < Math.max(12, box.height() * 0.75f)) {
                    continue;
                }
                boxes.add(new Rect(box));
                owners.add(candidate);
                lineIndexes.add(i);
            }
        }
        if (boxes.isEmpty()) {
            return;
        }
        if (progress != null) {
            progress.onProgress("پشکنینی دووەمی ورد بە PP-OCR…");
        }

        List<LineText> results;
        try {
            results = secondaryOcr.recognizeLines(original, boxes);
        } catch (Exception e) {
            if (JobRuntime.wasStopped(e)) {
                throw e;
            }
            if (progress != null) {
                progress.onProgress("PP-OCR بەردەست نەبوو؛ بە OCRی سەرەکی بەردەوام دەبین…");
            }
            return;
        }
        if (results == null) {
            return;
        }

        int count = Math.min(results.size(), boxes.size());
        for (int i = 0; i < count; i++) {
            LineText result = results.get(i);
            if (result == null) {
                continue;
            }
            String ppText = cleanText(result.text);
            if (!isUsableText(ppText)) {
                continue;
            }
            OcrCandidate candidate = owners.get(i);
            int lineIndex = lineIndexes.get(i);
            if (lineIndex < 0 || lineIndex >= candidate.lines.size()) {
                continue;
            }
            OcrLineData existing = candidate.lines.get(lineIndex);
            boolean changed = false;
            if (comparisonText(existing.text).equals(comparisonText(ppText))) {
                existing.confidence = Math.max(
                        existing.confidence, result.confidence);
                changed = true;
            } else {
                double ppScore = lineScore(ppText, result.confidence, 3);
                double mlScore = lineScore(existing.text,
                        existing.confidence, candidate.refined ? 2 : 0);
                int existingSpaces = countChar(existing.text, ' ');
                int ppSpaces = countChar(ppText, ' ');
                if (existingSpaces >= 2 && ppSpaces == 0) {
                    ppScore -= 8.0;
                }
                if (result.confidence >= 0.86f && ppScore >= mlScore + 6.0) {
                    existing.text = ppText;
                    existing.confidence = result.confidence;
                    changed = true;
                }
            }
            if (changed) {
                refreshCandidate(candidate, true);
            }
        }
    }

    private static int countChar(String text, char target) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }

    private static String cleanText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u00A0', ' ')
                .replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{2,}", "\n")
                .trim();
    }

    private static boolean isUsableText(String value) {
        int alphaNumeric = 0;
        int cjk = 0;
        int nonSpace = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint)) {
                alphaNumeric++;
            }
            if (isCjkCodePoint(codePoint)) {
                cjk++;
            }
            if (!Character.isWhitespace(codePoint)) {
                nonSpace++;
            }
            offset += Character.charCount(codePoint);
        }
        if (nonSpace == 0) {
            return false;
        }
        float ratio = alphaNumeric / (float) nonSpace;
        if (cjk > 0) {
            // One Han/kana/Hangul character can already be a complete word.
            return ratio >= 0.30f;
        }
        // Accept short dialogue such as "No!" or "Go…" as well as normal lines.
        return alphaNumeric >= 2 && nonSpace >= 2 && ratio >= 0.34f;
    }

    private static boolean isCjkCodePoint(int codePoint) {
        return (codePoint >= 0x3040 && codePoint <= 0x30FF)      // Hiragana/Katakana
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)  // CJK Ext. A
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)  // CJK Unified
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF)  // Hangul syllables
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF); // Compatibility ideographs
    }

    private static List<Segment> dedupe(List<Segment> raw) {
        sortReadingOrder(raw);
        List<Segment> kept = new ArrayList<>();
        for (Segment candidate : raw) {
            int duplicateIndex = -1;
            for (int i = 0; i < kept.size(); i++) {
                Segment existing = kept.get(i);
                boolean sameText = candidate.normalized().equals(existing.normalized());
                float dx = candidate.centerX() - existing.centerX();
                float dy = candidate.centerY() - existing.centerY();
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                float allowed = Math.max(42f,
                        Math.min(candidate.box.height(), existing.box.height()) * 1.25f);
                if ((sameText && distance <= allowed) || iou(candidate.box, existing.box) > 0.35f) {
                    duplicateIndex = i;
                    break;
                }
            }
            if (duplicateIndex == -1) {
                kept.add(candidate);
            } else if (candidate.text.length() > kept.get(duplicateIndex).text.length()) {
                kept.set(duplicateIndex, candidate);
            }
        }
        return kept;
    }

    private static float iou(Rect a, Rect b) {
        Rect intersection = new Rect(a);
        if (!intersection.intersect(b)) {
            return 0f;
        }
        float intersectionArea = (float) intersection.width() * intersection.height();
        float unionArea = (float) a.width() * a.height()
                + (float) b.width() * b.height() - intersectionArea;
        return unionArea <= 0f ? 0f : intersectionArea / unionArea;
    }

    private static void sortReadingOrder(List<Segment> segments) {
        Collections.sort(segments, new Comparator<Segment>() {
            @Override
            public int compare(Segment a, Segment b) {
                int rowA = Math.round(a.centerY() / 60f);
                int rowB = Math.round(b.centerY() / 60f);
                int byRow = Integer.compare(rowA, rowB);
                if (byRow != 0) {
                    return byRow;
                }
                int byLeft = Integer.compare(a.box.left, b.box.left);
                if (byLeft != 0) {
                    return byLeft;
                }
                return Integer.compare(a.box.top, b.box.top);
            }
        });
    }
}
