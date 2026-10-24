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
import java.util.List;

/** On-device OCR (tiled for long webtoons) and conservative text rendering. */
public class ManhwaRenderer {
    public interface Progress {
        void onProgress(String message);
    }

    public static class RenderResult {
        public final Bitmap bitmap;
        public final int rendered;

        public RenderResult(Bitmap bitmap, int rendered) {
            this.bitmap = bitmap;
            this.rendered = rendered;
        }
    }

    private static class Background {
        int color = Color.WHITE;
        int inkColor = Color.BLACK;
        boolean reliable = false;
    }

    private static class Fit {
        StaticLayout layout;
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

        int rendered = 0;
        for (Segment segment : segments) {
            if (segment.ckb == null || segment.ckb.trim().isEmpty()) {
                continue;
            }
            Rect expanded = expandBox(segment.box, original.getWidth(), original.getHeight());
            Background bg = sampleBackground(original, expanded);
            if (!bg.reliable) {
                continue;
            }
            Fit fit = fitText(segment.ckb.trim(), expanded, bg.inkColor, segment);
            if (fit == null) {
                continue;
            }

            fill.setColor(bg.color);
            float radius = Math.max(6f,
                    Math.min(24f, Math.min(expanded.width(), expanded.height()) * 0.18f));
            canvas.drawRoundRect(new RectF(expanded), radius, radius, fill);

            int yOffset = Math.max(0, (expanded.height() - fit.layout.getHeight()) / 2);
            canvas.save();
            canvas.translate(expanded.left + 4, expanded.top + yOffset);
            fit.layout.draw(canvas);
            canvas.restore();
            rendered++;
        }
        return new RenderResult(output, rendered);
    }

    private static Rect expandBox(Rect box, int imageWidth, int imageHeight) {
        int padX = Math.max(8, Math.round(box.width() * 0.12f));
        int padY = Math.max(6, Math.round(box.height() * 0.22f));
        return new Rect(
                Math.max(0, box.left - padX),
                Math.max(0, box.top - padY),
                Math.min(imageWidth, box.right + padX),
                Math.min(imageHeight, box.bottom + padY));
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

        int width = Math.max(24, rect.width() - 8);
        int maxHeight = Math.max(18, rect.height() - 6);
        float originalLineHeight = segment.box.height() / (float) Math.max(1, segment.lines);
        int startSize = Math.round(originalLineHeight * 0.92f);
        startSize = Math.max(14, Math.min(76, startSize));

        for (int size = startSize; size >= 11; size--) {
            paint.setTextSize(size);
            StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                    .setLineSpacing(0f, 1.06f)
                    .setIncludePad(false)
                    .build();
            if (layout.getHeight() <= maxHeight && maxLineWidth(layout) <= width + 1f) {
                Fit fit = new Fit();
                fit.layout = layout;
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
        return alphaNumeric >= 3 && nonSpace > 0
                && (alphaNumeric / (float) nonSpace) >= 0.40f;
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
