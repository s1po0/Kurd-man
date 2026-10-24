package com.kurdman.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import java.util.HashMap;
import java.util.Map;

/**
 * "Clean erase" for source text: removes only the original glyphs from a
 * bubble and fills their pixels with the surrounding background, instead
 * of painting one solid rectangle over the whole text area. The result
 * looks like an untouched empty bubble, ready for the Kurdish typesetting.
 *
 * <p>Two details keep the result invisible:</p>
 * <ul>
 *   <li>Only letter-like connected components are erased. The bubble
 *   border and any artwork that the (rectangular) erase box overlaps also
 *   differ from the bubble fill, but they form large/long structures —
 *   painting over them is what used to leave square corners sticking out
 *   of round bubbles.</li>
 *   <li>Erased pixels are refilled from their actual neighbouring pixels,
 *   never from one estimated colour, so paper tint, gradients and JPEG
 *   texture continue seamlessly through the cleaned area.</li>
 * </ul>
 */
final class CleanEraser {
    /** Above this many pixels we fall back to a plain detected-colour fill. */
    private static final int MAX_PIXELS = 1_800_000;
    /** A pixel counts as ink when it differs from the bubble colour by this much. */
    private static final int INK_DISTANCE = 40;

    private CleanEraser() {
    }

    /**
     * Returns a bitmap of exactly {@code rect} (already clipped to the
     * source bounds) with the original letters removed, or a solid
     * background-coloured patch when pixel processing is not possible.
     */
    static Bitmap createPatch(Bitmap source, Rect rect, int backgroundColor) {
        if (source == null || source.isRecycled() || rect == null
                || rect.width() <= 0 || rect.height() <= 0) {
            return null;
        }
        int width = rect.width();
        int height = rect.height();
        long count = (long) width * (long) height;
        if (count > MAX_PIXELS) {
            return solidPatch(width, height, backgroundColor);
        }

        int[] pixels = new int[(int) count];
        try {
            source.getPixels(pixels, 0, width,
                    rect.left, rect.top, width, height);
        } catch (RuntimeException unreadable) {
            // Hardware bitmaps cannot be read back on some devices.
            return solidPatch(width, height, backgroundColor);
        }

        // Reference bubble colour: prefer the patch's own dominant colour.
        // The caller's estimate can be a few levels off, and that alone
        // would make the cleaned area visible as a slightly wrong square.
        int refColor = backgroundColor;
        int[] dominant = estimateDominant(pixels, width, height);
        if (dominant != null && dominant[1] >= 250) {
            refColor = dominant[0];
        }
        int bgRed = Color.red(refColor);
        int bgGreen = Color.green(refColor);
        int bgBlue = Color.blue(refColor);

        boolean[] candidate = new boolean[pixels.length];
        int candidateCount = 0;
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int distance = Math.max(
                    Math.abs(Color.red(pixel) - bgRed),
                    Math.max(Math.abs(Color.green(pixel) - bgGreen),
                            Math.abs(Color.blue(pixel) - bgBlue)));
            if (distance > INK_DISTANCE) {
                candidate[i] = true;
                candidateCount++;
            }
        }
        if (candidateCount == 0) {
            // Nothing to erase: return the untouched region.
            return Bitmap.createBitmap(pixels, width, height,
                    Bitmap.Config.ARGB_8888);
        }
        if (candidateCount >= pixels.length) {
            return solidPatch(width, height, backgroundColor);
        }

        boolean[] erase = selectGlyphComponents(candidate, width, height);
        int eraseCount = 0;
        for (boolean value : erase) {
            if (value) {
                eraseCount++;
            }
        }
        if (eraseCount == 0) {
            // Everything found looks like artwork/border, not letters.
            return Bitmap.createBitmap(pixels, width, height,
                    Bitmap.Config.ARGB_8888);
        }

        dilateIntoBackground(erase, candidate, width, height,
                Math.max(1, Math.min(2, Math.round(Math.min(width, height) / 56f))));

        int known = 0;
        for (boolean value : erase) {
            if (!value) {
                known++;
            }
        }
        if (known == 0) {
            return solidPatch(width, height, backgroundColor);
        }

        nearestFill(pixels, erase, width, height);
        smooth(pixels, erase, width, height);
        smooth(pixels, erase, width, height);
        return Bitmap.createBitmap(pixels, width, height,
                Bitmap.Config.ARGB_8888);
    }

    private static Bitmap solidPatch(int width, int height, int color) {
        Bitmap patch = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        patch.eraseColor(Color.argb(255,
                Color.red(color), Color.green(color), Color.blue(color)));
        return patch;
    }

    /**
     * Returns {averageColour, permilleOfSamples} of the dominant colour
     * bin, or null when sampling found nothing.
     */
    private static int[] estimateDominant(int[] pixels, int width, int height) {
        int step = Math.max(1, (int) Math.ceil(Math.sqrt(pixels.length / 4000.0)));
        Map<Integer, int[]> bins = new HashMap<>();
        int total = 0;
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int pixel = pixels[y * width + x];
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
        int color = Color.rgb(
                best[1] / best[0], best[2] / best[0], best[3] / best[0]);
        int permille = (int) Math.round(1000.0 * best[0] / total);
        return new int[]{color, permille};
    }

    /**
     * Keeps only the connected components that look like letters. Speech
     * bubbles are round while the erase box is a rectangle, so the box
     * corners contain border/artwork pixels that also differ from the
     * bubble fill; those components are large or long and are preserved.
     */
    private static boolean[] selectGlyphComponents(boolean[] candidate,
                                                   int width, int height) {
        int n = candidate.length;
        int[] labels = new int[n];
        // Per component: area, minX, minY, maxX, maxY, touchesEdge(0/1).
        int[][] stats = new int[n / 8 + 16][];
        int components = 0;
        int[] queue = new int[n];

        for (int start = 0; start < n; start++) {
            if (!candidate[start] || labels[start] != 0) {
                continue;
            }
            components++;
            if (components >= stats.length) {
                int[][] bigger = new int[stats.length * 2][];
                System.arraycopy(stats, 0, bigger, 0, stats.length);
                stats = bigger;
            }
            int[] stat = new int[]{0, width, height, -1, -1, 0};
            stats[components] = stat;
            int head = 0;
            int tail = 0;
            labels[start] = components;
            queue[tail++] = start;
            while (head < tail) {
                int index = queue[head++];
                int x = index % width;
                int y = index / width;
                stat[0]++;
                if (x < stat[1]) stat[1] = x;
                if (y < stat[2]) stat[2] = y;
                if (x > stat[3]) stat[3] = x;
                if (y > stat[4]) stat[4] = y;
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    stat[5] = 1;
                }
                if (x > 0 && candidate[index - 1] && labels[index - 1] == 0) {
                    labels[index - 1] = components;
                    queue[tail++] = index - 1;
                }
                if (x + 1 < width && candidate[index + 1] && labels[index + 1] == 0) {
                    labels[index + 1] = components;
                    queue[tail++] = index + 1;
                }
                if (y > 0 && candidate[index - width] && labels[index - width] == 0) {
                    labels[index - width] = components;
                    queue[tail++] = index - width;
                }
                if (y + 1 < height && candidate[index + width] && labels[index + width] == 0) {
                    labels[index + width] = components;
                    queue[tail++] = index + width;
                }
            }
        }

        boolean[] accepted = new boolean[components + 1];
        for (int id = 1; id <= components; id++) {
            int[] stat = stats[id];
            int area = stat[0];
            int bbW = stat[3] - stat[1] + 1;
            int bbH = stat[4] - stat[2] + 1;
            double areaFraction = area / (double) n;
            double density = area / (double) Math.max(1, bbW * bbH);
            boolean small = areaFraction <= 0.12
                    && bbW <= width * 0.5 && bbH <= height * 0.5
                    && density >= 0.10;
            boolean enclosed = stat[5] == 0 && areaFraction <= 0.35
                    && bbW <= width * 0.75 && bbH <= height * 0.75
                    && density >= 0.18;
            accepted[id] = small || enclosed;
        }

        boolean[] erase = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (labels[i] > 0 && accepted[labels[i]]) {
                erase[i] = true;
            }
        }
        return erase;
    }

    /**
     * Grows the erase mask so anti-aliased letter edges disappear too, but
     * only into background-like pixels so a nearby bubble border or piece
     * of artwork is never eaten away.
     */
    private static void dilateIntoBackground(boolean[] erase, boolean[] candidate,
                                             int width, int height, int radius) {
        if (radius <= 0) {
            return;
        }
        boolean[] grown = erase.clone();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!erase[y * width + x]) {
                    continue;
                }
                int y0 = Math.max(0, y - radius);
                int y1 = Math.min(height - 1, y + radius);
                int x0 = Math.max(0, x - radius);
                int x1 = Math.min(width - 1, x + radius);
                for (int yy = y0; yy <= y1; yy++) {
                    for (int xx = x0; xx <= x1; xx++) {
                        int index = yy * width + xx;
                        if (!candidate[index]) {
                            grown[index] = true;
                        }
                    }
                }
            }
        }
        System.arraycopy(grown, 0, erase, 0, erase.length);
    }

    /**
     * Multi-source BFS: every erased pixel takes the colour of the nearest
     * untouched pixel. Letter strokes are thin, so this reconstructs
     * gradients and simple textures convincingly.
     */
    private static void nearestFill(int[] pixels, boolean[] mask,
                                    int width, int height) {
        int[] queue = new int[pixels.length];
        int head = 0;
        int tail = 0;
        boolean[] visited = new boolean[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            if (!mask[i]) {
                visited[i] = true;
                queue[tail++] = i;
            }
        }
        while (head < tail) {
            int index = queue[head++];
            int x = index % width;
            int y = index / width;
            int color = pixels[index];
            if (x > 0 && !visited[index - 1]) {
                visited[index - 1] = true;
                pixels[index - 1] = color;
                queue[tail++] = index - 1;
            }
            if (x + 1 < width && !visited[index + 1]) {
                visited[index + 1] = true;
                pixels[index + 1] = color;
                queue[tail++] = index + 1;
            }
            if (y > 0 && !visited[index - width]) {
                visited[index - width] = true;
                pixels[index - width] = color;
                queue[tail++] = index - width;
            }
            if (y + 1 < height && !visited[index + width]) {
                visited[index + width] = true;
                pixels[index + width] = color;
                queue[tail++] = index + width;
            }
        }
    }

    /** One gentle blur pass restricted to the filled pixels. */
    private static void smooth(int[] pixels, boolean[] mask,
                               int width, int height) {
        int[] copy = pixels.clone();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (!mask[index]) {
                    continue;
                }
                int red = 0;
                int green = 0;
                int blue = 0;
                int count = 0;
                if (x > 0) {
                    red += Color.red(copy[index - 1]);
                    green += Color.green(copy[index - 1]);
                    blue += Color.blue(copy[index - 1]);
                    count++;
                }
                if (x + 1 < width) {
                    red += Color.red(copy[index + 1]);
                    green += Color.green(copy[index + 1]);
                    blue += Color.blue(copy[index + 1]);
                    count++;
                }
                if (y > 0) {
                    red += Color.red(copy[index - width]);
                    green += Color.green(copy[index - width]);
                    blue += Color.blue(copy[index - width]);
                    count++;
                }
                if (y + 1 < height) {
                    red += Color.red(copy[index + width]);
                    green += Color.green(copy[index + width]);
                    blue += Color.blue(copy[index + width]);
                    count++;
                }
                if (count > 0) {
                    pixels[index] = Color.argb(255,
                            red / count, green / count, blue / count);
                }
            }
        }
    }
}
