package com.kurdman.app;

import android.content.Context;

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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** On-device OCR (tiled for long webtoons) and conservative text rendering. */
public class ManhwaRenderer {

    /**
     * Target language of the job currently rendering (set by the UI
     * before a job starts). Outside-bubble sanitation is script-aware:
     * the Latin-garbage guard only applies to Arabic-script targets.
     */
    public static volatile String activeTargetLanguage = "ckb";

    /** Context used only to localize progress messages. */
    public static volatile Context i18nContext;

    private static String tr(int resId) {
        Context context = i18nContext;
        return context == null ? "" : context.getString(resId);
    }
    public interface Progress {
        void onProgress(String message);
    }

    /** A second, independent line recognizer (PP-OCR in MainActivity). */
    public interface SecondaryOcr {
        List<LineText> recognizeLines(Bitmap page, List<Rect> boxes) throws Exception;
    }

    /**
     * A whole-block re-reader: given one grouped text block (a whole
     * bubble or narration box), return its full text. Backed by the
     * optional Unlimited-OCR server in MainActivity. Returning null or
     * throwing keeps the ML Kit reading.
     */
    public interface BlockOcr {
        String readBlock(Bitmap page, Rect box) throws Exception;
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
        /** Segments actually used; a block that had to be split at a
         * balloon waist appears as its two halves instead of the
         * original entry, so callers should persist this list. */
        public final List<Segment> segments;

        public RenderResult(Bitmap bitmap, int rendered, int skippedBackground,
                            int skippedFit, int skippedNoTranslation,
                            List<Segment> segments) {
            this.bitmap = bitmap;
            this.rendered = rendered;
            this.skippedBackground = skippedBackground;
            this.skippedFit = skippedFit;
            this.skippedNoTranslation = skippedNoTranslation;
            this.segments = segments == null
                    ? new ArrayList<>() : new ArrayList<>(segments);
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
        return recognize(original, sourceLanguage, progress, true, null, null);
    }

    public static List<Segment> recognize(Bitmap original, String sourceLanguage,
                                          Progress progress,
                                          boolean highAccuracy,
                                          SecondaryOcr secondaryOcr) throws Exception {
        return recognize(original, sourceLanguage, progress,
                highAccuracy, secondaryOcr, null);
    }

    /**
     * High-accuracy OCR used by the app.
     *
     * <p>The old implementation asked ML Kit once per page tile. That was
     * fast, but a single misread stylised manhwa word went straight into
     * translation. This version keeps ML Kit's reliable on-device detector,
     * reads each tile again after contrast/upscale preprocessing, and
     * scores the competing readings with ML Kit word confidence.</p>
     *
     * <p>Crucially, the readings are grouped back into whole text blocks
     * (one bubble / narration box) at the LINE level, immediately after
     * candidate selection — before any crop refinement, PP-OCR second
     * opinion, optional Unlimited-OCR block re-read, or translation.
     * ML Kit loves to return one bubble as several blocks (first line
     * alone, heading alone); refining and translating those fragments
     * separately is exactly what produced mixed tiny/large Kurdish words
     * in cramped strips. Grouping first means every later stage sees the
     * whole writing at once, and refinement crops the whole bubble.</p>
     */
    public static List<Segment> recognize(Bitmap original, String sourceLanguage,
                                          Progress progress,
                                          boolean highAccuracy,
                                          SecondaryOcr secondaryOcr,
                                          BlockOcr blockOcr) throws Exception {
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
                            ? tr(R.string.ks_0200)
                            : tr(R.string.ks_0197))
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

                // Light lettering on dark artwork (white SFX over a
                // night sky) is invisible to the normal passes. One
                // inverted re-read of tiles that actually contain dark
                // areas finds it; its candidates are scored below every
                // normal reading, so they only fill real gaps.
                if (tileDarkFraction(tile) >= 0.12f) {
                    float invertedScale = enhancedScaleFor(tile);
                    double invertedPixels = (double) tile.getWidth() * invertedScale
                            * tile.getHeight() * invertedScale;
                    Bitmap inverted = invertedPixels <= 9_000_000.0
                            ? createInvertedVariant(tile, invertedScale) : null;
                    if (inverted != null) {
                        try {
                            Text invertedText = Tasks.await(recognizer.process(
                                    InputImage.fromBitmap(inverted, 0)));
                            JobRuntime.throwIfStopped();
                            candidates.addAll(extractCandidates(
                                    invertedText, 0, y0, 1f / invertedScale,
                                    width, height, coreTop, coreBottom, 4, true));
                        } finally {
                            inverted.recycle();
                        }
                    }
                }

                if (y1 == height) {
                    break;
                }
            }

            List<OcrCandidate> selected = mergeCandidates(candidates);
            // Re-join the fragments of one bubble/box FIRST, while the
            // true per-line boxes are still available, so refinement
            // crops a whole bubble and translation sees whole sentences.
            selected = groupCandidates(original, selected);
            if (highAccuracy && !selected.isEmpty()) {
                refineCandidates(original, recognizer, selected, progress);
            }
            if (highAccuracy && secondaryOcr != null && !selected.isEmpty()) {
                applySecondaryOcr(original, selected, secondaryOcr, progress);
            }
            if (blockOcr != null && !selected.isEmpty()) {
                applyBlockOcr(original, selected, blockOcr, progress);
            }

            selected = groupCandidates(original,mergeCandidates(selected));
            List<Segment> result = new ArrayList<>();
            for (OcrCandidate candidate : selected) {
                candidate.segment.ocrConfidence = candidate.confidence;
                candidate.segment.lines = Math.max(1,
                        candidate.lines.isEmpty()
                                ? candidate.segment.lines : candidate.lines.size());
                candidate.segment.sourceLineHeight = estimateLinePitch(candidate);
                if (isUsableText(candidate.segment.text)) {
                    result.add(candidate.segment);
                }
            }
            sortReadingOrder(result);
            return groupSegments(original, result);
        } finally {
            try {
                recognizer.close();
            } catch (Exception ignored) {
                // The recognizer is process-scoped; failing to close is not fatal here.
            }
        }
    }

    /**
     * ML Kit often returns one speech bubble as several blocks — most
     * often the first line on its own. Typeset separately, the
     * fragments get unrelated font sizes (a one-line fragment's "line
     * height" is just its cap height) and each is confined to its own
     * strip of the bubble, which reads as tiny, incomplete, mixed-up
     * writing. Fragments stacked like lines of one block are merged
     * here, before translation, so the whole bubble is translated as
     * one sentence and drawn at one size in one place.
     */
    private static List<Segment> groupSegments(Bitmap original,
                                               List<Segment> segments) {
        List<Segment> list = new ArrayList<>(segments);
        // Always merge the vertically nearest pair first: merging
        // non-adjacent fragments (heading+body) before their middle
        // neighbour (description) would strand the middle one inside
        // the combined box, where no later test can reach it.
        while (true) {
            Segment bestUpper = null;
            Segment bestLower = null;
            int bestGap = Integer.MAX_VALUE;
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    Segment a = list.get(i);
                    Segment b = list.get(j);
                    Segment upper = a.box.top <= b.box.top ? a : b;
                    Segment lower = upper == a ? b : a;
                    if (shouldGroup(original, upper, lower)) {
                        int gap = lower.box.top - upper.box.bottom;
                        if (gap < bestGap) {
                            bestGap = gap;
                            bestUpper = upper;
                            bestLower = lower;
                        }
                    }
                }
            }
            if (bestUpper == null) {
                break;
            }
            list.remove(bestUpper);
            list.remove(bestLower);
            list.add(combineSegments(bestUpper, bestLower));
        }
        sortReadingOrder(list);
        return list;
    }

    private static float segmentLineHeight(Segment segment) {
        if (segment.sourceLineHeight > 0f) {
            return segment.sourceLineHeight;
        }
        return segment.box.height() / (float) Math.max(1, segment.lines);
    }

    private static boolean shouldGroup(Bitmap original, Segment upper,
                                       Segment lower) {
        return shouldGroupPair(original, upper, segmentLineHeight(upper),
                lower, segmentLineHeight(lower));
    }

    /**
     * Shared fragment test used at candidate level (with line-box
     * pitches) and again as a segment-level safety net. The pitch is
     * the distance between line tops, not the glyph height, so ordinary
     * line spacing always counts as a "direct" merge.
     */
    private static boolean shouldGroupPair(Bitmap original, Segment upper,
                                           float lhUpper, Segment lower,
                                           float lhLower) {
        if (lhUpper <= 0f || lhLower <= 0f) {
            return false;
        }
        float minLh = Math.min(lhUpper, lhLower);
        float maxLh = Math.max(lhUpper, lhLower);
        // A narration-box heading is legitimately larger than its body;
        // only wildly different sizes mean different blocks.
        if (maxLh > minLh * 2.2f) {
            return false;
        }
        int minW = Math.min(upper.box.width(), lower.box.width());
        int maxW = Math.max(upper.box.width(), lower.box.width());
        int overlapW = Math.min(upper.box.right, lower.box.right)
                - Math.max(upper.box.left, lower.box.left);
        float centreDx = Math.abs(
                upper.box.exactCenterX() - lower.box.exactCenterX());
        boolean aligned = overlapW >= minW * 0.45f
                || centreDx <= maxW * 0.22f
                || Math.abs(upper.box.left - lower.box.left) <= maxW * 0.18f
                || Math.abs(upper.box.right - lower.box.right) <= maxW * 0.18f;
        if (!aligned) {
            return false;
        }
        int unionW = Math.max(upper.box.right, lower.box.right)
                - Math.min(upper.box.left, lower.box.left);
        if (unionW > maxW * 1.75f) {
            return false;
        }
        int gap = lower.box.top - upper.box.bottom;
        if (gap < 0) {
            // ML Kit pads block boxes, so stacked fragments of one
            // bubble often overlap slightly. Accept a shallow overlap
            // for well-aligned fragments; a deep overlap means
            // side-by-side columns, which must never merge.
            return gap >= -Math.round(minLh * 0.55f)
                    && overlapW >= minW * 0.5f;
        }
        if (gap <= Math.round(maxLh * 0.85f)) {
            return true;
        }
        if (gap > Math.round(maxLh * 3.2f)) {
            return false;
        }
        // Connected double balloons (two joined ovals carrying one
        // speech) pass every background test below: the strip between
        // the lobes is clean shared interior and no border line
        // crosses the join. But the lobes sit diagonally — measured
        // device case (v1.8.1 report): fragment centres 196 px apart
        // on a 369 px block (0.53 × width), interior centre drifting
        // x 253 → 449 through the waist. Merging them creates one
        // block whose single centred column cannot stay inside both
        // lobes, so the typesetter later skips BOTH balloons and the
        // English survives. Fragments of one ordinary bubble or
        // narration box keep roughly the same centre even across a
        // blank-line gap, so a wide-gap merge additionally requires
        // the centres to stay close. Each lobe then stays its own
        // segment and is erased/typeset in its own oval.
        if (centreDx > maxW * 0.38f) {
            return false;
        }
        // Anything wider than tight line spacing (a heading separated
        // from its body, a blank line) merges only when one continuous
        // background — the same bubble interior or narration box —
        // fills the space between the fragments, with no bubble border
        // line crossing it. Two distinct stacked bubbles can sit
        // within one pitch of each other, so the border check must
        // apply well before the old "direct merge" distance.
        return gapBackgroundContinuous(original, upper, lower);
    }

    /**
     * Line-level grouping, run right after candidate selection. Doing
     * this here (instead of only on finished segments) is what makes
     * refinement crop whole bubbles and keeps one pitch per bubble.
     */
    private static List<OcrCandidate> groupCandidates(
            Bitmap original, List<OcrCandidate> candidates) {
        List<OcrCandidate> list = new ArrayList<>(candidates);
        // Nearest pair first, for the same reason as groupSegments.
        while (true) {
            OcrCandidate bestUpper = null;
            OcrCandidate bestLower = null;
            int bestGap = Integer.MAX_VALUE;
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    OcrCandidate a = list.get(i);
                    OcrCandidate b = list.get(j);
                    OcrCandidate upper =
                            a.segment.box.top <= b.segment.box.top ? a : b;
                    OcrCandidate lower = upper == a ? b : a;
                    if (shouldGroupPair(original, upper.segment,
                            estimateLinePitch(upper),
                            lower.segment, estimateLinePitch(lower))) {
                        int gap = lower.segment.box.top
                                - upper.segment.box.bottom;
                        if (gap < bestGap) {
                            bestGap = gap;
                            bestUpper = upper;
                            bestLower = lower;
                        }
                    }
                }
            }
            if (bestUpper == null) {
                break;
            }
            list.remove(bestUpper);
            list.remove(bestLower);
            list.add(combineCandidates(bestUpper, bestLower));
        }
        return list;
    }

    private static OcrCandidate combineCandidates(OcrCandidate upper,
                                                  OcrCandidate lower) {
        Rect union = new Rect(upper.segment.box);
        union.union(lower.segment.box);
        List<OcrLineData> lines = new ArrayList<>();
        if (upper.lines.isEmpty()) {
            lines.add(new OcrLineData(upper.segment.text,
                    upper.segment.box, upper.confidence));
        } else {
            for (OcrLineData line : upper.lines) {
                lines.add(line.copy());
            }
        }
        if (lower.lines.isEmpty()) {
            lines.add(new OcrLineData(lower.segment.text,
                    lower.segment.box, lower.confidence));
        } else {
            for (OcrLineData line : lower.lines) {
                lines.add(line.copy());
            }
        }
        sortLinesReadingOrder(lines);
        StringBuilder text = new StringBuilder();
        for (OcrLineData line : lines) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(line.text);
        }
        Segment segment = new Segment(cleanText(text.toString()), union,
                Math.max(1, lines.size()));
        OcrCandidate combined = new OcrCandidate(segment,
                combinePass(upper, lower));
        combined.lines.addAll(lines);
        combined.refined = upper.refined || lower.refined;
        combined.disagreement = upper.disagreement || lower.disagreement;
        refreshCandidate(combined, false);
        segment.sourceLineHeight = estimateLinePitch(combined);
        return combined;
    }

    private static int combinePass(OcrCandidate a, OcrCandidate b) {
        // The inverted pass (4) stays subordinate: a normal reading of
        // any fragment promotes the combined block out of that class.
        if (a.pass == 4 && b.pass != 4) {
            return b.pass;
        }
        if (b.pass == 4 && a.pass != 4) {
            return a.pass;
        }
        return letterOrDigitCount(a.segment.text)
                >= letterOrDigitCount(b.segment.text) ? a.pass : b.pass;
    }

    private static void sortLinesReadingOrder(List<OcrLineData> lines) {
        Collections.sort(lines, new Comparator<OcrLineData>() {
            @Override
            public int compare(OcrLineData a, OcrLineData b) {
                int rowA = Math.round(a.box.exactCenterY() / 60f);
                int rowB = Math.round(b.box.exactCenterY() / 60f);
                if (rowA != rowB) {
                    return Integer.compare(rowA, rowB);
                }
                int byLeft = Integer.compare(a.box.left, b.box.left);
                if (byLeft != 0) {
                    return byLeft;
                }
                return Integer.compare(a.box.top, b.box.top);
            }
        });
    }

    /**
     * Typical line pitch (top-to-top distance) of a candidate, from
     * its real line boxes. Falls back to glyph height × leading for a
     * single line, and to box/lines when no line boxes exist. This is
     * the single source of "how big is the source font" for grouping,
     * refinement scale, and the final typeset size — previously each
     * stage guessed differently, per fragment.
     */
    private static float estimateLinePitch(OcrCandidate candidate) {
        if (candidate == null || candidate.segment == null) {
            return 0f;
        }
        Segment segment = candidate.segment;
        List<Integer> heights = new ArrayList<>();
        List<Integer> tops = new ArrayList<>();
        for (OcrLineData line : candidate.lines) {
            if (line.box != null && line.box.height() > 4) {
                heights.add(line.box.height());
                tops.add(line.box.top);
            }
        }
        if (heights.isEmpty()) {
            return segment.box.height()
                    / (float) Math.max(1, segment.lines);
        }
        int textLines = 1 + countChar(segment.text, '\n');
        if (heights.size() == 1 && textLines > 1) {
            // One synthetic "line" spanning the whole block: its box
            // height is the block height, not a glyph height.
            return segment.box.height() / (float) textLines;
        }
        float glyph = medianOf(heights);
        if (tops.size() >= 2) {
            Collections.sort(tops);
            List<Integer> deltas = new ArrayList<>();
            for (int i = 1; i < tops.size(); i++) {
                int delta = tops.get(i) - tops.get(i - 1);
                if (delta > 4) {
                    deltas.add(delta);
                }
            }
            if (!deltas.isEmpty()) {
                float pitch = medianOf(deltas);
                return Math.max(glyph * 0.85f,
                        Math.min(pitch, glyph * 2.4f));
            }
        }
        return glyph * 1.35f;
    }

    /**
     * Is the space between two fragments one continuous interior?
     *
     * <p>The background colour is taken from the strip itself, then
     * required to be a major colour inside PADDED versions of both
     * fragment boxes. The previous version estimated each box's
     * dominant colour directly — but a tight box around one line of
     * bold light-on-dark lettering is mostly INK (white), so the
     * "background" came out white, the black strip matched neither
     * side, and narration-box fragments never merged (measured on the
     * user's Shadow Slave page: heading box dominant = white ink at
     * 47%, strip match 0%). Padding lets the true background win.</p>
     */
    private static boolean gapBackgroundContinuous(Bitmap original,
                                                   Segment upper,
                                                   Segment lower) {
        try {
            int left = Math.max(upper.box.left, lower.box.left);
            int right = Math.min(upper.box.right, lower.box.right);
            if (right - left < 16) {
                int cx = Math.round((upper.box.exactCenterX()
                        + lower.box.exactCenterX()) / 2f);
                left = cx - 20;
                right = cx + 20;
            }
            Rect strip = new Rect(left, upper.box.bottom,
                    right, lower.box.top);
            if (!strip.intersect(0, 0,
                    original.getWidth(), original.getHeight())
                    || strip.width() < 8 || strip.height() < 2) {
                return false;
            }
            DominantColor stripBg = estimateDominantColor(original, strip);
            if (stripBg == null || stripBg.fraction < 0.50) {
                return false; // strip is artwork/clutter, not interior
            }
            Rect upperPad = expandBox(upper.box,
                    original.getWidth(), original.getHeight());
            Rect lowerPad = expandBox(lower.box,
                    original.getWidth(), original.getHeight());
            if (fractionSimilar(original, upperPad, stripBg.color) < 0.38
                    || fractionSimilar(original, lowerPad, stripBg.color)
                            < 0.38) {
                return false; // strip colour belongs to neither side
            }
            // A bubble border crossing the strip means two different
            // bubbles, even when both interiors share the page-white
            // colour: the thin border line is only a few percent of
            // the strip area, so the fraction tests above cannot see
            // it. Refuse when a contrasting line spans most of the
            // strip width on any sampled row.
            return !stripHasBorderLine(original, strip, stripBg.color);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static boolean stripHasBorderLine(Bitmap original, Rect strip,
                                              int bgColor) {
        int bgLuminance = luminance(bgColor);
        int stepX = Math.max(1, strip.width() / 60);
        int stepY = Math.max(1, strip.height() / 40);
        for (int y = strip.top; y < strip.bottom; y += stepY) {
            int total = 0;
            int foreign = 0;
            for (int x = strip.left; x < strip.right; x += stepX) {
                int pixel = original.getPixel(x, y);
                total++;
                if (colorDistanceSum(pixel, bgColor) > 260
                        || Math.abs(luminance(pixel) - bgLuminance) > 90) {
                    foreign++;
                }
            }
            if (total >= 8 && foreign >= total * 0.55) {
                return true;
            }
        }
        return false;
    }

    private static Segment combineSegments(Segment upper, Segment lower) {
        Rect union = new Rect(upper.box);
        union.union(lower.box);
        Segment combined = new Segment(
                upper.text + "\n" + lower.text, union,
                upper.lines + lower.lines);
        float ca = upper.ocrConfidence;
        float cb = lower.ocrConfidence;
        int lettersA = letterOrDigitCount(upper.text);
        int lettersB = letterOrDigitCount(lower.text);
        if (ca >= 0f && cb >= 0f && lettersA + lettersB > 0) {
            combined.ocrConfidence =
                    (ca * lettersA + cb * lettersB) / (lettersA + lettersB);
        } else if (ca >= 0f) {
            combined.ocrConfidence = ca;
        } else {
            combined.ocrConfidence = cb;
        }
        int linesA = Math.max(1, upper.lines);
        int linesB = Math.max(1, lower.lines);
        combined.sourceLineHeight =
                (segmentLineHeight(upper) * linesA
                        + segmentLineHeight(lower) * linesB) / (linesA + linesB);
        return combined;
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
        // Work on a copy: a block that cannot be typeset because it
        // spans two balloon lobes is replaced in-place by its halves
        // (see splitAtWaist), and the caller persists result.segments.
        List<Segment> work = new ArrayList<>(segments);
        Set<Segment> splitHalves = Collections.newSetFromMap(
                new IdentityHashMap<>());

        for (int workIndex = 0; workIndex < work.size(); workIndex++) {
            Segment segment = work.get(workIndex);
            segment.rendered = false;
            segment.outsideText = false;
            segment.bubbleBoundary = null;
            segment.interiorTop = -1;
            segment.interiorLeft = null;
            segment.interiorRight = null;
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
            segment.bubbleOval = looksOval(original, region, bg.color);

            if (!bg.reliable) {
                // Not inside a bubble: this may be lettering over artwork
                // (handwritten SFX). Accept it only when the glyph-level
                // outside eraser proves it can remove the letters
                // cleanly; otherwise the block remains available in the
                // editor, but automatic rendering stays conservative
                // around faces/artwork.
                if (!prepareOutsideText(original, segment)) {
                    segment.ensureStyle().visible = false;
                    skippedBackground++;
                    continue;
                }
            }

            // Measure the real bubble outline so the fit can guarantee no
            // line crosses the border, and typeset inside the bubble body
            // (the grown rectangle may include the tail or, for connected
            // double balloons, part of the sister balloon).
            Rect bubbleBody = segment.outsideText ? null
                    : computeInteriorSpans(original, segment, work);
            if (bubbleBody != null) {
                segment.renderBox = bubbleBody;
            }

            StyledTextRenderer.FitResult fit = StyledTextRenderer.createFit(
                    segment, resolver, false);
            if (fit == null && !segment.outsideText && !segment.bubbleOval
                    && segment.bubbleBoundary == null && segment.interiorLeft == null
                    && prepareOutsideText(original, segment)) {
                // The bubble assumptions failed for this block; it may
                // be lettering over artwork after all.
                fit = StyledTextRenderer.createFit(segment, resolver, false);
            }
            if (fit == null) {
                // Last rescue before leaving the original language on
                // the page: a block spanning two connected balloon
                // lobes cannot fit one centred column inside both.
                // Split it at the traced waist and typeset each half
                // in its own lobe instead of skipping both balloons.
                Segment[] halves = splitHalves.contains(segment)
                        ? null : splitAtWaist(segment);
                if (halves != null) {
                    splitHalves.add(halves[0]);
                    splitHalves.add(halves[1]);
                    work.remove(workIndex);
                    work.add(workIndex, halves[1]);
                    work.add(workIndex, halves[0]);
                    workIndex--;
                    continue;
                }
                segment.outsideText = false;
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
            StyledTextRenderer.eraseOriginal(canvas, original, placement.segment);
        }
        for (Placement placement : placements) {
            StyledTextRenderer.drawBackground(canvas, placement.segment);
        }

        int rendered = 0;
        for (Placement placement : placements) {
            if (StyledTextRenderer.drawText(canvas, placement.segment, placement.fit)) {
                placement.segment.rendered = true;
                placement.segment.ensureStyle().visible = true;
                rendered++;
            } else skippedFit++;
        }
        return new RenderResult(output, rendered, skippedBackground,
                skippedFit, skippedNoTranslation, work);
    }

    /**
     * Splits a segment whose traced bubble interior has two lobes —
     * connected double balloons whose centres drift apart, or an
     * interior that pinches at a waist — into an upper and a lower
     * segment at the waist row, dividing the source and Kurdish text
     * by words in proportion to the interior area above/below the
     * waist. Returns null when the interior looks like one ordinary
     * bubble. Only called after the normal fit already failed, so a
     * wrong split can never make a previously-rendered block worse:
     * the worst case is the previous behaviour (block skipped).
     */
    private static Segment[] splitAtWaist(Segment segment) {
        int[] lefts = segment.interiorLeft;
        int[] rights = segment.interiorRight;
        if (lefts == null || rights == null || segment.lines < 2
                || segment.interiorTop < 0) {
            return null;
        }
        String ckb = segment.ckb == null ? "" : segment.ckb.trim();
        String source = segment.text == null ? "" : segment.text.trim();
        String[] ckbWords = ckb.isEmpty() ? new String[0] : ckb.split("\\s+");
        String[] sourceWords = source.isEmpty()
                ? new String[0] : source.split("\\s+");
        if (ckbWords.length < 2 || sourceWords.length < 2) {
            return null;
        }
        int row0 = Math.max(0, segment.box.top - segment.interiorTop);
        int row1 = Math.min(lefts.length - 1,
                segment.box.bottom - 1 - segment.interiorTop);
        List<Integer> rows = new ArrayList<>();
        for (int r = row0; r <= row1; r++) {
            if (lefts[r] >= 0 && rights[r] > lefts[r]) {
                rows.add(r);
            }
        }
        if (rows.size() < 24) {
            return null;
        }
        int n = rows.size();
        int third = Math.max(1, n / 3);
        float topCentre = medianCentre(lefts, rights, rows, 0, third);
        float bottomCentre = medianCentre(lefts, rights, rows, n - third, n);
        float medWidth = medianWidth(lefts, rights, rows, 0, n);
        if (medWidth <= 0f) {
            return null;
        }
        int waistIdx = -1;
        int waistWidth = Integer.MAX_VALUE;
        for (int i = n / 4; i < n - n / 4; i++) {
            int r = rows.get(i);
            int w = rights[r] - lefts[r];
            if (w < waistWidth) {
                waistWidth = w;
                waistIdx = i;
            }
        }
        boolean twoLobes = Math.abs(bottomCentre - topCentre) > medWidth * 0.30f
                || waistWidth < medWidth * 0.55f;
        if (!twoLobes || waistIdx < 0) {
            return null;
        }
        int waistY = segment.interiorTop + rows.get(waistIdx);
        if (waistY <= segment.box.top + 4 || waistY >= segment.box.bottom - 4) {
            return null;
        }
        long areaAbove = 0;
        long areaBelow = 0;
        int waistRow = rows.get(waistIdx);
        for (int i = 0; i < n; i++) {
            int r = rows.get(i);
            if (r < waistRow) {
                areaAbove += rights[r] - lefts[r];
            } else {
                areaBelow += rights[r] - lefts[r];
            }
        }
        if (areaAbove <= 0 || areaBelow <= 0) {
            return null;
        }
        float frac = areaAbove / (float) (areaAbove + areaBelow);
        int kCkb = splitIndex(ckbWords.length, frac);
        int kSource = splitIndex(sourceWords.length, frac);
        int upperLines = Math.max(1, Math.min(segment.lines - 1,
                Math.round(segment.lines * frac)));
        Segment upper = new Segment(joinWords(sourceWords, 0, kSource),
                new Rect(segment.box.left, segment.box.top,
                        segment.box.right, waistY), upperLines);
        Segment lower = new Segment(
                joinWords(sourceWords, kSource, sourceWords.length),
                new Rect(segment.box.left, waistY,
                        segment.box.right, segment.box.bottom),
                segment.lines - upperLines);
        upper.ckb = joinWords(ckbWords, 0, kCkb);
        lower.ckb = joinWords(ckbWords, kCkb, ckbWords.length);
        upper.originalCkb = upper.ckb;
        lower.originalCkb = lower.ckb;
        upper.translationMethod = lower.translationMethod = segment.translationMethod;
        upper.translationWarnings = lower.translationWarnings = segment.translationWarnings;
        upper.ocrConfidence = segment.ocrConfidence;
        lower.ocrConfidence = segment.ocrConfidence;
        upper.sourceLineHeight = segment.sourceLineHeight;
        lower.sourceLineHeight = segment.sourceLineHeight;
        return new Segment[]{upper, lower};
    }

    private static float medianCentre(int[] lefts, int[] rights,
                                      List<Integer> rows, int from, int to) {
        List<Float> values = new ArrayList<>();
        for (int i = from; i < to; i++) {
            int r = rows.get(i);
            values.add((lefts[r] + rights[r]) / 2f);
        }
        return median(values);
    }

    private static float medianWidth(int[] lefts, int[] rights,
                                     List<Integer> rows, int from, int to) {
        List<Float> values = new ArrayList<>();
        for (int i = from; i < to; i++) {
            int r = rows.get(i);
            values.add((float) (rights[r] - lefts[r]));
        }
        return median(values);
    }

    private static float median(List<Float> values) {
        if (values.isEmpty()) {
            return 0f;
        }
        Collections.sort(values);
        int mid = values.size() / 2;
        if (values.size() % 2 == 1) {
            return values.get(mid);
        }
        return (values.get(mid - 1) + values.get(mid)) / 2f;
    }

    private static int splitIndex(int count, float frac) {
        return Math.max(1, Math.min(count - 1, Math.round(count * frac)));
    }

    private static String joinWords(String[] words, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(words[i]);
        }
        return out.toString();
    }

    /**
     * Validates and prepares lettering that sits on artwork instead of
     * inside a speech bubble (handwritten SFX, signs). The block is
     * accepted only when it is small and legible and the glyph-level
     * outside eraser confirms it can remove the original letters; the
     * Kurdish text is then drawn in the same spot using the original
     * ink colour with a contrasting outline, so it reads like the
     * lettering it replaces. Anything less certain stays untouched
     * (and editable in the studio).
     */
    private static boolean prepareOutsideText(Bitmap original, Segment segment) {
        try {
            Rect box = new Rect(segment.box);
            if (!box.intersect(0, 0, original.getWidth(), original.getHeight())
                    || box.width() < 24 || box.height() < 14) {
                return false;
            }
            long pageArea = (long) original.getWidth() * original.getHeight();
            if ((long) box.width() * box.height() > pageArea * 0.08) {
                return false;
            }
            if (segment.lines > 4 || segment.text == null
                    || letterOrDigitCount(segment.text) < 3) {
                return false;
            }
            if (segment.ocrConfidence >= 0f && segment.ocrConfidence < 0.22f) {
                return false;
            }
            int padX = Math.max(6, Math.round(box.width() * 0.08f));
            int padY = Math.max(6, Math.round(box.height() * 0.18f));
            Rect rect = new Rect(
                    Math.max(0, box.left - padX),
                    Math.max(0, box.top - padY),
                    Math.min(original.getWidth(), box.right + padX),
                    Math.min(original.getHeight(), box.bottom + padY));
            int width = rect.width();
            int height = rect.height();
            long count = (long) width * (long) height;
            if (count > 2_600_000L) {
                return false;
            }
            int[] pixels = new int[(int) count];
            original.getPixels(pixels, 0, width,
                    rect.left, rect.top, width, height);

            // Ink statistics decide bright-on-dark vs dark-on-light and
            // give the replacement text the original ink colour.
            int bright = 0;
            int dark = 0;
            for (int pixel : pixels) {
                int lum = luminance(pixel);
                if (lum >= 180) {
                    bright++;
                } else if (lum <= 75) {
                    dark++;
                }
            }
            boolean brightInk = bright >= dark;
            long redSum = 0;
            long greenSum = 0;
            long blueSum = 0;
            int inkSamples = 0;
            for (int pixel : pixels) {
                int lum = luminance(pixel);
                if (brightInk ? lum >= 205 : lum <= 55) {
                    redSum += Color.red(pixel);
                    greenSum += Color.green(pixel);
                    blueSum += Color.blue(pixel);
                    inkSamples++;
                }
            }
            int inkColor = brightInk ? Color.WHITE : Color.BLACK;
            if (inkSamples >= 20) {
                inkColor = Color.rgb(
                        (int) (redSum / inkSamples),
                        (int) (greenSum / inkSamples),
                        (int) (blueSum / inkSamples));
            }

            // The eraser decides the true extent of the lettering (OCR
            // boxes clip handwritten strokes) and refuses blocks whose
            // letters cannot be removed completely.
            Rect extent = CleanEraser.outsideExtent(original, box, brightInk);
            if (extent == null) {
                return false;
            }
            if (!sanitizeOutsideTranslation(segment)) {
                return false;
            }

            segment.outsideText = true;
            segment.bubbleBoundary = null;
            segment.outsideBrightInk = brightInk;
            segment.bubbleOval = false;
            segment.interiorTop = -1;
            segment.interiorLeft = null;
            segment.interiorRight = null;
            segment.detectedInkColor = inkColor;
            segment.renderBox = new Rect(extent);
            segment.eraseBox = new Rect(extent);

            TextStyleSettings style = segment.ensureStyle();
            style.visible = true;
            style.eraseOriginal = true;
            style.backgroundMode = TextStyleSettings.BG_CLEAN;
            style.autoTextColor = false;
            style.textColor = inkColor;
            style.outline = true;
            style.outlineColor = brightInk ? Color.BLACK : Color.WHITE;
            style.outlineWidthRatio = Math.max(0.13f, style.outlineWidthRatio);
            style.fontWeight = Math.max(700, style.fontWeight);
            style.sizeScale = Math.max(1.0f, style.sizeScale * 1.25f);
            return true;
        } catch (RuntimeException failed) {
            return false;
        }
    }

    /**
     * Outside lettering is usually handwriting, which OCR reads badly;
     * translators then echo the misread Latin words back unchanged.
     * Strip Latin tokens from the translation and require real Kurdish
     * (Arabic script) to remain — otherwise accepting the block would
     * typeset garbage like "tsfustto" over the artwork.
     */
    private static boolean isArabicScriptTarget() {
        switch (activeTargetLanguage == null ? "ckb" : activeTargetLanguage) {
            case "ckb": case "ar": case "fa": case "ur":
            case "ps": case "sd": case "ug":
                return true;
            default:
                return false;
        }
    }

    private static boolean sanitizeOutsideTranslation(Segment segment) {
        if (!isArabicScriptTarget()) {
            // Latin/other-script targets: keep the translation as-is;
            // the guard below exists only to stop un-translated Latin
            // OCR garbage leaking into Arabic-script output.
            return segment.ckb != null
                    && !segment.ckb.trim().isEmpty();
        }
        String ckb = segment.ckb == null ? "" : segment.ckb;
        String cleaned = ckb.replaceAll("[A-Za-z][A-Za-z'’\\-]*", " ")
                .replaceAll("\\s+", " ").trim();
        int arabic = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            char ch = cleaned.charAt(i);
            if (ch >= 0x0600 && ch <= 0x06FF) {
                arabic++;
            }
        }
        if (arabic < 3) {
            return false;
        }
        segment.ckb = cleaned;
        segment.originalCkb = cleaned;
        return true;
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

    /**
     * Decides whether the grown rectangle wraps a round/oval bubble. On an
     * oval bubble the rectangle's corners stick out into the artwork, so
     * they do not match the bubble fill colour; on a rectangular narration
     * box they do. When the bubble sits on a plain white page the corners
     * match too, so a second test casts rays down from the top edge: on an
     * oval the border is met near the centre long before the sides. The
     * result lets the typesetter fit text to the ellipse instead of the
     * rectangle, so no line can cross the bubble border.
     */
    static boolean looksOval(Bitmap original, Rect region, int bgColor) {
        if (region.width() < 24 || region.height() < 24) {
            return false;
        }
        int outsideCorners = 0;
        int[][] corners = {
                {0, 0}, {1, 0}, {0, 1}, {1, 1}
        };
        for (int[] corner : corners) {
            int total = 0;
            int foreign = 0;
            for (int i = 0; i < 5; i++) {
                float fx = 0.03f + i * 0.022f;
                float fy = 0.03f + (4 - i) * 0.022f;
                int x = corner[0] == 0
                        ? region.left + Math.round(region.width() * fx)
                        : region.right - 1 - Math.round(region.width() * fx);
                int y = corner[1] == 0
                        ? region.top + Math.round(region.height() * fy)
                        : region.bottom - 1 - Math.round(region.height() * fy);
                x = clampInt(x, 0, original.getWidth() - 1);
                y = clampInt(y, 0, original.getHeight() - 1);
                int pixel = original.getPixel(x, y);
                total++;
                if (colorDistanceSum(pixel, bgColor) > 170
                        || Math.abs(luminance(pixel) - luminance(bgColor)) > 70) {
                    foreign++;
                }
            }
            if (total > 0 && foreign >= 3) {
                outsideCorners++;
            }
        }
        if (outsideCorners >= 2) {
            return true;
        }
        float hitCentre = borderRay(original, region, bgColor, 0.50f);
        float hitLeft = borderRay(original, region, bgColor, 0.12f);
        float hitRight = borderRay(original, region, bgColor, 0.88f);
        return hitCentre <= 0.15f
                && hitLeft - hitCentre > 0.12f
                && hitRight - hitCentre > 0.12f;
    }

    /** Fraction of the region height at which a downward ray first hits a
     * strongly contrasting pixel (the border), or 1 when none is found. */
    private static float borderRay(Bitmap original, Rect region, int bgColor, float fx) {
        int x = clampInt(region.left + Math.round(region.width() * fx),
                0, original.getWidth() - 1);
        int limit = Math.min(original.getHeight(),
                region.top + region.height() / 2);
        for (int y = Math.max(0, region.top); y < limit; y++) {
            if (colorDistanceSum(original.getPixel(x, y), bgColor) > 300) {
                return (y - region.top) / (float) region.height();
            }
        }
        return 1f;
    }

    /**
     * Traces the actual bubble interior inside the segment's erase box:
     * for every row, the leftmost/rightmost interior pixels. The interior
     * is the background-like component around the OCR text, found with the
     * same colour rule the clean eraser uses. On success the spans are
     * stored on the segment (so the typesetter can keep every line inside
     * the real outline, even for connected double balloons whose grown
     * rectangle covers two circles) and the bubble "body" rectangle —
     * the interior bounds with narrow tail rows trimmed — is returned.
     * Returns {@code null} when no reliable body rectangle is found. A valid
     * traced mask is still kept even if body selection fails; callers must not
     * silently relax that contour into an unconstrained rectangle.
     *
     * <p>The body rectangle is taken from the band of rows that belongs
     * to this text's own balloon (see {@link #bubbleBandBody}); the full
     * span bounds are only a fallback, because on connected double
     * balloons they cover the union of both circles.</p>
     */
    static Rect computeInteriorSpans(Bitmap original, Segment segment,
                                     List<Segment> allSegments) {
        if (original == null || original.isRecycled() || segment == null) {
            return null;
        }
        Rect region = new Rect(segment.getEraseBox());
        if (!region.intersect(0, 0, original.getWidth(), original.getHeight())
                || region.width() < 16 || region.height() < 12) {
            return null;
        }
        int width = region.width();
        int height = region.height();
        long count = (long) width * (long) height;
        if (count > 3_500_000L) {
            return null;
        }
        int n = (int) count;
        int[] pixels = new int[n];
        try {
            original.getPixels(pixels, 0, width,
                    region.left, region.top, width, height);
        } catch (RuntimeException unreadable) {
            return null;
        }
        int bgColor = segment.detectedBackgroundColor;
        int bgRed = Color.red(bgColor);
        int bgGreen = Color.green(bgColor);
        int bgBlue = Color.blue(bgColor);
        boolean[] bgLike = new boolean[n];
        for (int i = 0; i < n; i++) {
            int pixel = pixels[i];
            int distance = Math.max(
                    Math.abs(Color.red(pixel) - bgRed),
                    Math.max(Math.abs(Color.green(pixel) - bgGreen),
                            Math.abs(Color.blue(pixel) - bgBlue)));
            bgLike[i] = distance <= 40;
        }

        int[] labels = new int[n];
        int[] queue = new int[n];
        // Per component: area, minX, minY, maxX, maxY.
        List<int[]> stats = new ArrayList<>();
        stats.add(null);
        int components = 0;
        for (int start = 0; start < n; start++) {
            if (labels[start] != 0 || !bgLike[start]) {
                continue;
            }
            components++;
            int[] st = new int[]{0, width, height, -1, -1};
            stats.add(st);
            int head = 0;
            int tail = 0;
            labels[start] = components;
            queue[tail++] = start;
            while (head < tail) {
                int index = queue[head++];
                int x = index % width;
                int y = index / width;
                st[0]++;
                if (x < st[1]) st[1] = x;
                if (y < st[2]) st[2] = y;
                if (x > st[3]) st[3] = x;
                if (y > st[4]) st[4] = y;
                if (x > 0 && labels[index - 1] == 0 && bgLike[index - 1]) {
                    labels[index - 1] = components;
                    queue[tail++] = index - 1;
                }
                if (x + 1 < width && labels[index + 1] == 0 && bgLike[index + 1]) {
                    labels[index + 1] = components;
                    queue[tail++] = index + 1;
                }
                if (y > 0 && labels[index - width] == 0 && bgLike[index - width]) {
                    labels[index - width] = components;
                    queue[tail++] = index - width;
                }
                if (y + 1 < height && labels[index + width] == 0 && bgLike[index + width]) {
                    labels[index + width] = components;
                    queue[tail++] = index + width;
                }
            }
        }
        if (components == 0) {
            return null;
        }

        int cx = clampInt(Math.round(segment.box.exactCenterX()) - region.left,
                0, width - 1);
        int cy = clampInt(Math.round(segment.box.exactCenterY()) - region.top,
                0, height - 1);
        int interior = 0;
        if (bgLike[cy * width + cx]) {
            interior = labels[cy * width + cx];
            // The text centre can land inside a letter counter (the hole
            // in an "O"); that pocket is far too small to be the bubble.
            if (stats.get(interior)[0] < n * 0.18) {
                interior = 0;
            }
        }
        if (interior == 0) {
            int best = -1;
            int fallback = 0;
            int fallbackBest = -1;
            for (int id = 1; id <= components; id++) {
                int[] st = stats.get(id);
                if (st[0] > fallbackBest) {
                    fallbackBest = st[0];
                    fallback = id;
                }
                if (st[1] <= cx && cx <= st[3] && st[2] <= cy && cy <= st[4]
                        && st[0] > best) {
                    best = st[0];
                    interior = id;
                }
            }
            if (interior == 0) {
                interior = fallback;
            }
        }
        int interiorArea = stats.get(interior)[0];
        if (interiorArea < n * 0.18) {
            return null;
        }

        int[] left = new int[height];
        int[] right = new int[height];
        for (int y = 0; y < height; y++) {
            left[y] = -1;
            right[y] = -1;
            int base = y * width;
            for (int x = 0; x < width; x++) {
                if (labels[base + x] == interior) {
                    if (left[y] < 0) {
                        left[y] = region.left + x;
                    }
                    right[y] = region.left + x;
                }
            }
        }

        // Keep the real connected component, including disjoint row runs.
        // Source-letter holes can be filled, but exterior notches/concavities
        // must never be bridged by the legacy per-row extremes.
        segment.bubbleBoundary = BubbleBoundary.fromComponent(
                region, labels, interior, segment.box, queue);
        segment.interiorTop = region.top;
        segment.interiorLeft = left;
        segment.interiorRight = right;

        Rect body = bubbleBandBody(
                left, right, height, region, segment, allSegments);
        if (body == null) {
            body = bubbleSpanBody(left, right, height, region, segment, count);
        }
        if (body == null) {
            return null;
        }

        segment.interiorTop = region.top;
        segment.interiorLeft = left;
        segment.interiorRight = right;
        return body;
    }

    /**
     * The balloon that actually owns this text. Starting from the OCR
     * text rows, walk up and down while the traced outline stays wide
     * and roughly centred on the text, stopping at rows that belong to
     * another block or narrow into a neck/tail. Connected double
     * balloons trace as one interior, so without this band the previous
     * body covered both circles and the translation was typeset small
     * in the middle of the union instead of centred in its own balloon.
     */
    private static Rect bubbleBandBody(int[] left, int[] right, int height,
                                       Rect region, Segment segment,
                                       List<Segment> allSegments) {
        int textTop = clampInt(segment.box.top - region.top, 0, height - 1);
        int textBottom = clampInt(
                segment.box.bottom - 1 - region.top, 0, height - 1);
        if (textBottom < textTop) {
            return null;
        }
        List<Integer> widths = new ArrayList<>();
        List<Integer> centres = new ArrayList<>();
        for (int y = textTop; y <= textBottom; y++) {
            if (left[y] >= 0) {
                widths.add(right[y] - left[y]);
                centres.add((left[y] + right[y]) / 2);
            }
        }
        if (widths.size() < 3) {
            return null;
        }
        int refWidth = medianOf(widths);
        int refCentre = medianOf(centres);
        if (refWidth < 24) {
            return null;
        }
        int bandTop = textTop;
        while (bandTop > 0 && bandRowOk(left, right, bandTop - 1,
                refWidth, refCentre, region, segment, allSegments)) {
            bandTop--;
        }
        int bandBottom = textBottom;
        while (bandBottom + 1 < height && bandRowOk(left, right,
                bandBottom + 1, refWidth, refCentre,
                region, segment, allSegments)) {
            bandBottom++;
        }
        int bodyLeft = Integer.MAX_VALUE;
        int bodyRight = Integer.MIN_VALUE;
        for (int y = bandTop; y <= bandBottom; y++) {
            if (left[y] >= 0) {
                bodyLeft = Math.min(bodyLeft, left[y]);
                bodyRight = Math.max(bodyRight, right[y]);
            }
        }
        if (bodyLeft == Integer.MAX_VALUE) {
            return null;
        }
        Rect body = new Rect(bodyLeft, region.top + bandTop,
                bodyRight + 1, region.top + bandBottom + 1);
        if (body.width() < 24 || body.height() < 16
                || body.width() < segment.box.width() * 0.7f
                || body.height() < segment.box.height()) {
            return null;
        }
        return body;
    }

    private static boolean bandRowOk(int[] left, int[] right, int y,
                                     int refWidth, int refCentre,
                                     Rect region, Segment segment,
                                     List<Segment> allSegments) {
        if (left[y] < 0) {
            return false;
        }
        int spanWidth = right[y] - left[y];
        if (spanWidth < Math.max(24, Math.round(refWidth * 0.45f))) {
            return false;
        }
        int centre = (left[y] + right[y]) / 2;
        if (Math.abs(centre - refCentre) > refWidth * 0.35f) {
            return false;
        }
        if (allSegments != null) {
            int pageY = region.top + y;
            for (Segment other : allSegments) {
                if (other == null || other == segment || other.box == null) {
                    continue;
                }
                if (pageY >= other.box.top - 6
                        && pageY <= other.box.bottom + 6) {
                    float otherCentreX = other.box.exactCenterX();
                    if (otherCentreX >= left[y] && otherCentreX <= right[y]) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Original body estimate: full span bounds minus narrow rows. */
    private static Rect bubbleSpanBody(int[] left, int[] right, int height,
                                       Rect region, Segment segment,
                                       long regionPixels) {
        int maxSpan = 0;
        for (int y = 0; y < height; y++) {
            if (left[y] >= 0) {
                maxSpan = Math.max(maxSpan, right[y] - left[y]);
            }
        }
        int threshold = Math.max(12, Math.round(maxSpan * 0.35f));
        int bodyTopRow = -1;
        int bodyBottomRow = -1;
        for (int y = 0; y < height; y++) {
            if (left[y] >= 0 && right[y] - left[y] >= threshold) {
                if (bodyTopRow < 0) {
                    bodyTopRow = y;
                }
                bodyBottomRow = y;
            }
        }
        if (bodyTopRow < 0) {
            return null;
        }
        int bodyLeft = Integer.MAX_VALUE;
        int bodyRight = Integer.MIN_VALUE;
        for (int y = bodyTopRow; y <= bodyBottomRow; y++) {
            if (left[y] >= 0) {
                bodyLeft = Math.min(bodyLeft, left[y]);
                bodyRight = Math.max(bodyRight, right[y]);
            }
        }
        Rect body = new Rect(bodyLeft, region.top + bodyTopRow,
                bodyRight + 1, region.top + bodyBottomRow + 1);
        if (body.width() < 24 || body.height() < 16
                || body.width() < segment.box.width() * 0.7f
                || (long) body.width() * body.height() < regionPixels * 0.30) {
            return null;
        }
        return body;
    }

    private static int medianOf(List<Integer> values) {
        List<Integer> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return copy.get(copy.size() / 2);
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
        } else if (pass == 3) {
            score += 8.0;
        } else if (pass == 4) {
            // Inverted (light-on-dark) pass: it only fills gaps the
            // normal passes missed and must never outrank them.
            score -= 6.0;
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
        // A short re-read of a real paragraph line is not a second block.
        // Whole-block similarity and centre distance miss first/last rows of
        // long blocks. Require actual line geometry AND covered words, not
        // just a substring elsewhere on the page.
        if (coveredCandidateLines(a,b) || coveredCandidateLines(b,a)) return true;
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

    private static boolean coveredCandidateLines(OcrCandidate smaller, OcrCandidate larger) {
        if(smaller.lines.isEmpty() || larger.lines.size()<=smaller.lines.size()) return false;
        for(OcrLineData line:smaller.lines) {
            if(line.box==null || line.box.isEmpty())return false;
            String text=lineWords(line.text);
            if(text.length()<8)return false;
            boolean covered=false;
            for(OcrLineData other:larger.lines) {
                if(other.box==null || other.box.isEmpty())continue;
                Rect overlap=new Rect(line.box);
                if(!overlap.intersect(other.box))continue;
                float minHeight=Math.min(line.box.height(),other.box.height());
                float minWidth=Math.min(line.box.width(),other.box.width());
                if(overlap.height()<minHeight*.80f || overlap.width()<minWidth*.80f
                        || Math.abs(line.box.exactCenterY()-other.box.exactCenterY())>Math.max(3,minHeight*.35f))continue;
                String existing=lineWords(other.text);
                // No guessed spelling/number/negation changes. A longer line
                // can cover a multiword fragment only at complete word edges.
                if(existing.equals(text) || (text.length()>=12 && text.indexOf(' ')>=0
                        && (" "+existing+" ").contains(" "+text+" "))) {
                    covered=true;break;
                }
            }
            if(!covered)return false;
        }
        return true;
    }

    private static String lineWords(String text) {
        return text==null?"":text.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+"," ").trim();
    }

    private static void mergeIntoCandidate(OcrCandidate existing,
                                           OcrCandidate challenger) {
        if (existing == null || challenger == null || existing == challenger) {
            return;
        }
        if (challenger.pass >= 1 && challenger.pass <= 3) {
            // A normal-pass re-read also promotes an inverted-only
            // candidate (pass 4) out of its subordinate score class.
            existing.pass = existing.pass == 4
                    ? challenger.pass
                    : Math.max(existing.pass, challenger.pass);
        }
        existing.refined = existing.refined || challenger.refined;
        if (textSimilarity(existing.segment.text, challenger.segment.text) < 0.92) {
            existing.disagreement = true;
        }
        boolean linesChanged = false;

        if (!existing.lines.isEmpty() && !challenger.lines.isEmpty()) {
            // Match only against the lines that existed when this merge
            // started. Complementary lines are collected separately and
            // appended after matching: growing existing.lines here made
            // the next inner-loop iteration read used[initialSize]
            // (ArrayIndexOutOfBoundsException "length=4; index=4").
            int initialLineCount = existing.lines.size();
            boolean[] used = new boolean[initialLineCount];
            List<OcrLineData> appendedLines = new ArrayList<>();
            for (OcrLineData challengerLine : challenger.lines) {
                int bestIndex = -1;
                float bestIou = 0f;
                for (int i = 0; i < initialLineCount; i++) {
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
                    // A complementary line the selected reading missed
                    // (the crop re-read saw one more line of the
                    // bubble). Keep it when it sits in a free vertical
                    // band with new text; dropping it while still
                    // unioning the boxes below used to inflate the box
                    // without its text, corrupting the pitch estimate
                    // and the grouping decisions.
                    if (isNewLineBand(existing, appendedLines, challengerLine)) {
                        appendedLines.add(challengerLine.copy());
                    }
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
            if (!appendedLines.isEmpty()) {
                existing.lines.addAll(appendedLines);
                linesChanged = true;
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
        } else {
            sortLinesReadingOrder(existing.lines);
        }
        refreshCandidate(existing, linesChanged);
    }

    private static boolean isNewLineBand(OcrCandidate existing,
                                         List<OcrLineData> pendingLines,
                                         OcrLineData line) {
        String newText = comparisonText(line.text);
        if (newText.isEmpty() || line.box == null
                || line.box.height() <= 4) {
            return false;
        }
        return !lineConflictsWith(existing.lines, line, newText)
                && !lineConflictsWith(pendingLines, line, newText);
    }

    private static boolean lineConflictsWith(List<OcrLineData> lines,
                                             OcrLineData line,
                                             String newText) {
        if (lines == null) {
            return false;
        }
        float centreY = line.box.exactCenterY();
        for (OcrLineData other : lines) {
            if (other == null || other.box == null) {
                continue;
            }
            String otherText = comparisonText(other.text);
            if (!otherText.isEmpty()
                    && (otherText.contains(newText)
                            || newText.contains(otherText))) {
                return true;
            }
            float slack = Math.max(3f, other.box.height() * 0.25f);
            if (centreY >= other.box.top - slack
                    && centreY <= other.box.bottom + slack) {
                // Same row band: a different segmentation of a line
                // we already have, not an additional line.
                return true;
            }
        }
        return false;
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

    /**
     * Fraction of sampled pixels that are dark. Only tiles with a real
     * dark area can carry light-on-dark lettering, so only they pay for
     * the extra inverted OCR pass.
     */
    private static float tileDarkFraction(Bitmap tile) {
        try {
            int stepX = Math.max(1, tile.getWidth() / 90);
            int stepY = Math.max(1, tile.getHeight() / 90);
            int total = 0;
            int dark = 0;
            for (int y = stepY / 2; y < tile.getHeight(); y += stepY) {
                for (int x = stepX / 2; x < tile.getWidth(); x += stepX) {
                    total++;
                    if (luminance(tile.getPixel(x, y)) < 90) {
                        dark++;
                    }
                }
            }
            return total == 0 ? 0f : dark / (float) total;
        } catch (RuntimeException unreadable) {
            return 0f;
        }
    }

    /** Grayscale + inverted copy so light-on-dark text reads as dark-on-light. */
    private static Bitmap createInvertedVariant(Bitmap source, float scale) {
        if (source == null || scale <= 0f) {
            return null;
        }
        int targetWidth = Math.max(1, Math.round(source.getWidth() * scale));
        int targetHeight = Math.max(1, Math.round(source.getHeight() * scale));
        Bitmap output = Bitmap.createBitmap(
                targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        ColorMatrix invert = new ColorMatrix(new float[]{
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f});
        matrix.postConcat(invert);
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
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
                progress.onProgress(tr(R.string.ks_0364)
                        + done + "/" + limit + "…");
            }

            Bitmap crop = Bitmap.createBitmap(original,
                    cropRect.left, cropRect.top,
                    cropRect.width(), cropRect.height());
            float averageLineHeight = estimateLinePitch(candidate);
            float scale = 52f / Math.max(8f, averageLineHeight);
            scale = Math.max(1.25f, Math.min(3.5f, scale));
            while (scale > 1.25f
                    && (double) cropRect.width() * scale
                            * cropRect.height() * scale > 7_000_000.0) {
                scale -= 0.2f;
            }

            List<OcrCandidate> cropCandidates = new ArrayList<>();
            // Weak blocks on dark artwork (light-on-dark SFX) also get an
            // inverted re-read of the crop; the normal variants cannot
            // see that lettering at all.
            boolean darkCrop = tileDarkFraction(crop) >= 0.12f;
            for (int variant = 0; variant < 3; variant++) {
                if (variant == 2 && !darkCrop) {
                    break;
                }
                Bitmap input = variant == 2
                        ? createInvertedVariant(crop, scale)
                        : createScaledVariant(crop, scale, variant == 1);
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
        float averageLineHeight = estimateLinePitch(candidate);
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
            progress.onProgress(tr(R.string.ks_0414));
        }

        List<LineText> results;
        try {
            results = secondaryOcr.recognizeLines(original, boxes);
        } catch (Exception e) {
            if (JobRuntime.wasStopped(e)) {
                throw e;
            }
            if (progress != null) {
                progress.onProgress(tr(R.string.ks_0113));
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

    /**
     * Optional third opinion from a whole-block reader (Unlimited-OCR
     * server): grouped blocks that still look weak are re-read as ONE
     * crop — the entire bubble at once, never line fragments — and the
     * result replaces the ML Kit text only when it is clean and its
     * length is plausible. Any failure keeps the on-device reading.
     */
    private static void applyBlockOcr(Bitmap original,
                                      List<OcrCandidate> selected,
                                      BlockOcr blockOcr,
                                      Progress progress) throws Exception {
        List<OcrCandidate> targets = new ArrayList<>();
        for (OcrCandidate candidate : selected) {
            Rect box = candidate.segment.box;
            if (box.width() >= 24 && box.height() >= 14
                    && needsRefinement(candidate)) {
                targets.add(candidate);
            }
        }
        int total = Math.min(targets.size(), 40);
        for (int i = 0; i < total; i++) {
            JobRuntime.throwIfStopped();
            OcrCandidate candidate = targets.get(i);
            if (progress != null) {
                progress.onProgress(tr(R.string.ks_0118)
                        + (i + 1) + "/" + total + "…");
            }
            String read;
            try {
                read = blockOcr.readBlock(original,
                        new Rect(candidate.segment.box));
            } catch (Exception e) {
                if (JobRuntime.wasStopped(e)) {
                    throw e;
                }
                read = null;
            }
            if (read == null) {
                continue;
            }
            String cleaned = cleanText(read);
            if (!isUsableText(cleaned) || isSuspiciousText(cleaned)) {
                continue;
            }
            if (comparisonText(cleaned).equals(
                    comparisonText(candidate.segment.text))) {
                candidate.disagreement = false;
                continue;
            }
            int oldLetters = letterOrDigitCount(candidate.segment.text);
            int newLetters = letterOrDigitCount(cleaned);
            boolean lengthSane = oldLetters == 0
                    || (newLetters >= oldLetters * 0.45
                            && newLetters <= oldLetters * 2.2);
            if (!lengthSane
                    && !isSuspiciousText(candidate.segment.text)) {
                continue;
            }
            candidate.segment.text = cleaned;
            candidate.disagreement = false;
            refreshCandidate(candidate, false);
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
