package com.kurdman.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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

    private static class Fit {
        StaticLayout layout;
        int horizontalPadding;
        int verticalPadding;
    }

    private static class Placement {
        Segment segment;
        Rect region;
        Background background;
        Fit fit;
    }

    public static List<Segment> recognize(Bitmap original, boolean korean, Progress progress)
            throws Exception {
        if (original == null) {
            throw new IllegalArgumentException("No image selected");
        }
        int width = original.getWidth();
        int height = original.getHeight();

        TextRecognizer recognizer = korean
                ? TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build())
                : TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        List<Segment> raw = new ArrayList<>();
        try {
            int tileHeight = height <= 1600 ? height : 1400;
            int overlap = height <= tileHeight ? 0 : 220;
            int step = tileHeight - overlap;
            int tileCount = height <= tileHeight
                    ? 1
                    : ((height - tileHeight + step - 1) / step) + 1;
            int tileIndex = 0;

            for (int y0 = 0; ; y0 += step) {
                tileIndex++;
                int y1 = Math.min(height, y0 + tileHeight);
                if (progress != null) {
                    progress.onProgress("خوێندنەوەی دەق (OCR) " + tileIndex + "/" + tileCount + "…");
                }

                Bitmap tile = Bitmap.createBitmap(original, 0, y0, width, y1 - y0);
                Text visionText = Tasks.await(
                        recognizer.process(InputImage.fromBitmap(tile, 0)));

                int coreTop = y0 == 0 ? 0 : y0 + (overlap / 2);
                int coreBottom = y1 == height ? height : y1 - (overlap / 2);

                for (Text.TextBlock block : visionText.getTextBlocks()) {
                    Rect local = block.getBoundingBox();
                    if (local == null || local.width() <= 0 || local.height() <= 0) {
                        continue;
                    }
                    String blockText = cleanText(block.getText());
                    if (!isUsableText(blockText)) {
                        // A whole block can be rejected because of punctuation
                        // or one noisy line; keep any clean individual lines.
                        if (block.getLines() != null) {
                            for (Text.Line line : block.getLines()) {
                                Rect lineLocal = line.getBoundingBox();
                                String lineText = cleanText(line.getText());
                                if (lineLocal == null || lineLocal.width() <= 0
                                        || lineLocal.height() <= 0
                                        || !isUsableText(lineText)) {
                                    continue;
                                }
                                Rect fullLine = new Rect(lineLocal.left,
                                        lineLocal.top + y0, lineLocal.right,
                                        lineLocal.bottom + y0);
                                if (!fullLine.intersect(0, 0, width, height)
                                        || fullLine.width() <= 0 || fullLine.height() <= 0) {
                                    continue;
                                }
                                float lineCenterY = fullLine.exactCenterY();
                                if (lineCenterY < coreTop || lineCenterY >= coreBottom) {
                                    continue;
                                }
                                raw.add(new Segment(lineText, fullLine, 1));
                            }
                        }
                        continue;
                    }
                    Rect full = new Rect(local.left, local.top + y0, local.right, local.bottom + y0);
                    if (!full.intersect(0, 0, width, height) || full.width() <= 0 || full.height() <= 0) {
                        continue;
                    }
                    float centerY = full.exactCenterY();
                    if (centerY < coreTop || centerY >= coreBottom) {
                        continue;
                    }
                    int lines = block.getLines() == null ? 1 : Math.max(1, block.getLines().size());
                    raw.add(new Segment(blockText, full, lines));
                }

                if (y1 == height) {
                    break;
                }
            }
        } finally {
            try {
                recognizer.close();
            } catch (Exception ignored) {
                // The recognizer is process-scoped; failing to close is not fatal here.
            }
        }

        List<Segment> result = dedupe(raw);
        sortReadingOrder(result);
        return result;
    }

    public static RenderResult render(Bitmap original, List<Segment> segments) {
        Bitmap output = original.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(output);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);

        int skippedBackground = 0;
        int skippedFit = 0;
        int skippedNoTranslation = 0;
        List<Placement> placements = new ArrayList<>();

        for (Segment segment : segments) {
            segment.rendered = false;
            if (segment.ckb == null || segment.ckb.trim().isEmpty()) {
                skippedNoTranslation++;
                continue;
            }

            Rect seed = expandBox(segment.box, original.getWidth(), original.getHeight());
            Background bg = analyzeBubble(original, segment, seed);
            Rect region = bg.region;
            if (!bg.reliable) {
                // Preserve pages/boxes the previous conservative test handled.
                Background legacy = sampleBackground(original, seed);
                if (legacy.reliable) {
                    bg = legacy;
                    region = seed;
                } else {
                    skippedBackground++;
                    continue;
                }
            }
            if (region == null || region.width() <= 0 || region.height() <= 0) {
                region = seed;
            }

            Fit fit = fitText(segment.ckb.trim(), region, bg.inkColor, segment);
            if (fit == null) {
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

        // Erase every accepted source-text area before drawing any Sorani.
        // With nearby bubbles this prevents one segment's fill from painting
        // over the previous segment's newly drawn Kurdish text.
        for (Placement placement : placements) {
            fill.setColor(placement.background.color);
            float radius = Math.max(6f, Math.min(34f,
                    Math.min(placement.region.width(), placement.region.height()) * 0.20f));
            canvas.drawRoundRect(new RectF(placement.region), radius, radius, fill);
        }

        int rendered = 0;
        for (Placement placement : placements) {
            int availableHeight = Math.max(0,
                    placement.region.height() - (placement.fit.verticalPadding * 2));
            int yOffset = placement.fit.verticalPadding
                    + Math.max(0, (availableHeight - placement.fit.layout.getHeight()) / 2);
            canvas.save();
            canvas.translate(placement.region.left + placement.fit.horizontalPadding,
                    placement.region.top + yOffset);
            placement.fit.layout.draw(canvas);
            canvas.restore();
            placement.segment.rendered = true;
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
        int nonSpace = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                alphaNumeric++;
            }
            if (!Character.isWhitespace(c)) {
                nonSpace++;
            }
        }
        // Accept short dialogue such as "No!" or "Go…" as well as normal lines.
        return alphaNumeric >= 2 && nonSpace >= 2
                && (alphaNumeric / (float) nonSpace) >= 0.34f;
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
