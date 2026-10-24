package com.kurdman.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Clean erase" for source text: removes only the original glyphs inside a
 * bubble and refills their pixels with the bubble's own interior colour,
 * instead of painting a solid rectangle over the text area. The bubble
 * border and any artwork outside the bubble are never touched, so no
 * white square can stick out of a round balloon.
 *
 * <p>How letters are identified (verified against real pages):</p>
 * <ol>
 *   <li>Pixels close to the bubble fill colour are "background-like".
 *   The bubble interior is the background component around the text.</li>
 *   <li>A flood from the patch edges that may not cross the (slightly
 *   dilated) interior marks everything "outside". The dilation seals
 *   thin JPEG bridges between letter tops and the border.</li>
 *   <li>Ink pixels that are neither outside nor interior, and are more
 *   than a couple of ink-steps away from any outside ink, are letters.
 *   The distance rule protects the inner edge of the border itself.
 *   Letters with holes (O, P, A...) work because their counters count
 *   as interior-coloured seeds, not as barriers.</li>
 *   <li>Erased pixels are refilled from the nearest interior pixels, so
 *   paper tint and texture continue seamlessly.</li>
 * </ol>
 *
 * <p>When cleaning is not possible this returns {@code null} and the
 * caller draws nothing — it never falls back to painting a rectangle.</p>
 */
final class CleanEraser {
    /** Above this many pixels cleaning is skipped (memory bound). */
    private static final int MAX_PIXELS = 2_600_000;
    /** A pixel is background-like within this max-channel distance. */
    private static final int BG_DISTANCE = 40;
    /** Interior dilation (px) that seals thin bridges to the border. */
    private static final int BARRIER_DILATE = 2;
    /** Ink must be more than this many ink-steps from outside ink. */
    private static final int BORDER_PROTECT = 2;
    /** Interior pixels farther than this from the fill count as halo. */
    private static final int HALO_DISTANCE = 13;

    private CleanEraser() {
    }

    /**
     * Returns a bitmap of exactly {@code rect} (already clipped to the
     * source bounds) with the original letters removed, or {@code null}
     * when nothing should be drawn.
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
            return null;
        }
        int n = (int) count;

        int[] pixels = new int[n];
        try {
            source.getPixels(pixels, 0, width,
                    rect.left, rect.top, width, height);
        } catch (RuntimeException unreadable) {
            // Hardware bitmaps cannot be read back on some devices.
            return null;
        }

        // Reference bubble colour: prefer the patch's own dominant colour.
        int refColor = backgroundColor;
        int[] dominant = estimateDominant(pixels, width, height);
        if (dominant != null && dominant[1] >= 250) {
            refColor = dominant[0];
        }
        int bgRed = Color.red(refColor);
        int bgGreen = Color.green(refColor);
        int bgBlue = Color.blue(refColor);

        boolean[] bgLike = new boolean[n];
        for (int i = 0; i < n; i++) {
            int pixel = pixels[i];
            int distance = Math.max(
                    Math.abs(Color.red(pixel) - bgRed),
                    Math.max(Math.abs(Color.green(pixel) - bgGreen),
                            Math.abs(Color.blue(pixel) - bgBlue)));
            bgLike[i] = distance <= BG_DISTANCE;
        }

        int[] queue = new int[n];
        int[] labels = new int[n];
        // Per component: area, minX, minY, maxX, maxY, isBackground(0/1).
        List<int[]> stats = new ArrayList<>();
        stats.add(null);
        labelComponents(bgLike, width, height, labels, queue, stats);

        int interior = pickInterior(stats, width, height);
        if (interior == 0 || stats.get(interior)[0] < n * 0.08) {
            return null;
        }
        int interiorArea = stats.get(interior)[0];

        boolean[] core = new boolean[n];
        for (int i = 0; i < n; i++) {
            core[i] = labels[i] == interior;
        }

        // Reuse the labels buffer as the distance/work buffer from here on.
        int[] work = labels;

        chebyshevDistance(core, work, width, height);
        boolean[] barrier = new boolean[n];
        for (int i = 0; i < n; i++) {
            barrier[i] = work[i] <= BARRIER_DILATE;
        }

        boolean[] outside = floodOutside(barrier, width, height, queue);

        inkDistance(bgLike, outside, work, width, height, queue);

        boolean[] seed = new boolean[n];
        boolean[] erase = new boolean[n];
        int eraseCount = 0;
        for (int i = 0; i < n; i++) {
            boolean zone = !outside[i] && !core[i];
            if (core[i] || (bgLike[i] && zone)) {
                seed[i] = true;
            }
            if (!bgLike[i] && zone && work[i] > BORDER_PROTECT) {
                erase[i] = true;
                eraseCount++;
            }
        }
        if (eraseCount == 0 || eraseCount > interiorArea * 0.60) {
            return null;
        }

        // Grow the erase mask over anti-aliased edges and the fainter JPEG
        // halo beyond them — into interior (seed) pixels only, so the
        // border and artwork can never be eaten away.
        chebyshevDistance(erase, work, width, height);
        int radius = clampInt(Math.round(Math.min(width, height) * 0.03f), 3, 7);
        int haloRadius = radius * 2;
        for (int i = 0; i < n; i++) {
            if (erase[i] || !seed[i]) {
                continue;
            }
            if (work[i] <= radius) {
                erase[i] = true;
            } else if (work[i] <= haloRadius) {
                int pixel = pixels[i];
                int off = Math.max(
                        Math.abs(Color.red(pixel) - bgRed),
                        Math.max(Math.abs(Color.green(pixel) - bgGreen),
                                Math.abs(Color.blue(pixel) - bgBlue)));
                if (off > HALO_DISTANCE) {
                    erase[i] = true;
                }
            }
        }

        fillFromSeeds(pixels, erase, seed, width, height, queue, refColor);
        smooth(pixels, erase, width, height);
        smooth(pixels, erase, width, height);
        return Bitmap.createBitmap(pixels, width, height,
                Bitmap.Config.ARGB_8888);
    }

    // ---- Lettering that sits on artwork instead of in a bubble (SFX) ----

    /**
     * Whether the letters inside {@code rect} can be removed safely when
     * the text is lettering drawn over artwork (outside any bubble).
     * Runs the full mask analysis without producing a bitmap, so callers
     * can validate a block before accepting it for typesetting.
     */
    static boolean outsideErasable(Bitmap source, Rect rect, boolean brightInk) {
        return buildOutside(source, rect, brightInk) != null;
    }

    /**
     * Returns a bitmap of exactly {@code rect} with outside-bubble
     * lettering removed and the artwork behind it smoothly continued,
     * or {@code null} when the letters cannot be separated from the art
     * (the caller then draws nothing — never a rectangle).
     *
     * <p>How letters are identified (verified against real pages, e.g.
     * white handwritten SFX with a black outline over a night sky): the
     * extreme-luminance cores of the ink are labelled, solid slabs that
     * enter the patch broadly from its edges (a neighbouring balloon,
     * hair) are rejected by their density and edge contact, the erase
     * mask then grows a few pixels through the letters' own neutral
     * anti-aliasing and outline only, and the hole is filled by
     * diffusion so gradients like sky continue seamlessly.</p>
     */
    static Bitmap createOutsidePatch(Bitmap source, Rect rect, boolean brightInk) {
        OutsideData data = buildOutside(source, rect, brightInk);
        if (data == null) {
            return null;
        }
        int n = data.pixels.length;
        boolean[] seed = new boolean[n];
        for (int i = 0; i < n; i++) {
            seed[i] = !data.mask[i];
        }
        int[] queue = new int[n];
        fillFromSeeds(data.pixels, data.mask, seed,
                data.width, data.height, queue, Color.BLACK);
        diffuse(data.pixels, data.mask, data.width, data.height,
                n > 600_000 ? 25 : 50);
        return Bitmap.createBitmap(data.pixels, data.width, data.height,
                Bitmap.Config.ARGB_8888);
    }

    private static final class OutsideData {
        int[] pixels;
        boolean[] mask;
        int width;
        int height;
    }

    private static OutsideData buildOutside(Bitmap source, Rect rect,
                                            boolean brightInk) {
        if (source == null || source.isRecycled() || rect == null
                || rect.width() <= 0 || rect.height() <= 0) {
            return null;
        }
        int width = rect.width();
        int height = rect.height();
        long count = (long) width * (long) height;
        if (count > MAX_PIXELS) {
            return null;
        }
        int n = (int) count;
        int[] pixels = new int[n];
        try {
            source.getPixels(pixels, 0, width,
                    rect.left, rect.top, width, height);
        } catch (RuntimeException unreadable) {
            return null;
        }

        boolean[] core = new boolean[n];
        for (int i = 0; i < n; i++) {
            int lum = luminanceOf(pixels[i]);
            core[i] = brightInk ? lum >= 180 : lum <= 75;
        }

        int[] labels = new int[n];
        int[] queue = new int[n];
        List<int[]> stats = new ArrayList<>();
        stats.add(null);
        labelComponents(core, width, height, labels, queue, stats);
        int components = stats.size() - 1;
        if (components <= 0) {
            return null;
        }

        // How much of each patch side every component occupies. A balloon
        // or lock of hair entering the patch covers a long run of one
        // side; a letter only ever clips a side at a point.
        int[] runTop = new int[components + 1];
        int[] runBottom = new int[components + 1];
        int[] runLeft = new int[components + 1];
        int[] runRight = new int[components + 1];
        for (int x = 0; x < width; x++) {
            runTop[labels[x]]++;
            runBottom[labels[(height - 1) * width + x]]++;
        }
        for (int y = 0; y < height; y++) {
            runLeft[labels[y * width]]++;
            runRight[labels[y * width + width - 1]]++;
        }

        boolean[] accepted = new boolean[components + 1];
        for (int id = 1; id <= components; id++) {
            int[] st = stats.get(id);
            if (st[5] == 0) {
                continue; // not an ink-core component
            }
            int area = st[0];
            int boxW = st[3] - st[1] + 1;
            int boxH = st[4] - st[2] + 1;
            double density = area / (double) Math.max(1, boxW * boxH);
            int edgeRun = Math.max(Math.max(runTop[id], runBottom[id]),
                    Math.max(runLeft[id], runRight[id]));
            boolean reject = area > n * 0.45
                    || edgeRun > width * 0.35
                    || (edgeRun > 14 && density > 0.55 && area > n * 0.03);
            accepted[id] = !reject;
        }

        boolean[] keep = new boolean[n];
        int kept = 0;
        for (int i = 0; i < n; i++) {
            if (labels[i] >= 1 && labels[i] <= components
                    && accepted[labels[i]]) {
                keep[i] = true;
                kept++;
            }
        }
        if (kept == 0 || kept < n * 0.004 || kept > n * 0.42) {
            return null;
        }

        // labels buffer is free now; reuse it for the distance transform.
        int[] dist = labels;
        chebyshevDistance(keep, dist, width, height);
        boolean[] mask = new boolean[n];
        int maskCount = 0;
        for (int i = 0; i < n; i++) {
            boolean neutral = channelSpread(pixels[i]) <= 30;
            if (keep[i] || dist[i] <= 1 || (dist[i] <= 6 && neutral)) {
                mask[i] = true;
                maskCount++;
            }
        }
        if (maskCount > n * 0.55) {
            return null;
        }

        OutsideData data = new OutsideData();
        data.pixels = pixels;
        data.mask = mask;
        data.width = width;
        data.height = height;
        return data;
    }

    /**
     * Laplace diffusion restricted to the mask: repeatedly replaces each
     * masked pixel by the average of its neighbours (scanning in
     * alternating directions). This continues smooth gradients (sky,
     * walls) through the erased strokes without visible seams, where a
     * plain nearest-colour fill leaves blocky cells.
     */
    private static void diffuse(int[] pixels, boolean[] mask,
                                int width, int height, int iterations) {
        for (int it = 0; it < iterations; it++) {
            boolean forward = (it % 2 == 0);
            for (int yy = 0; yy < height; yy++) {
                int y = forward ? yy : height - 1 - yy;
                for (int xx = 0; xx < width; xx++) {
                    int x = forward ? xx : width - 1 - xx;
                    int index = y * width + x;
                    if (!mask[index]) {
                        continue;
                    }
                    int red = 0;
                    int green = 0;
                    int blue = 0;
                    int neighbours = 0;
                    if (x > 0) {
                        red += Color.red(pixels[index - 1]);
                        green += Color.green(pixels[index - 1]);
                        blue += Color.blue(pixels[index - 1]);
                        neighbours++;
                    }
                    if (x + 1 < width) {
                        red += Color.red(pixels[index + 1]);
                        green += Color.green(pixels[index + 1]);
                        blue += Color.blue(pixels[index + 1]);
                        neighbours++;
                    }
                    if (y > 0) {
                        red += Color.red(pixels[index - width]);
                        green += Color.green(pixels[index - width]);
                        blue += Color.blue(pixels[index - width]);
                        neighbours++;
                    }
                    if (y + 1 < height) {
                        red += Color.red(pixels[index + width]);
                        green += Color.green(pixels[index + width]);
                        blue += Color.blue(pixels[index + width]);
                        neighbours++;
                    }
                    if (neighbours > 0) {
                        pixels[index] = Color.argb(255,
                                red / neighbours, green / neighbours,
                                blue / neighbours);
                    }
                }
            }
        }
    }

    private static int luminanceOf(int pixel) {
        return (299 * Color.red(pixel) + 587 * Color.green(pixel)
                + 114 * Color.blue(pixel)) / 1000;
    }

    private static int channelSpread(int pixel) {
        int r = Color.red(pixel);
        int g = Color.green(pixel);
        int b = Color.blue(pixel);
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
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

    /** Labels maximal 4-connected regions of equal background-likeness. */
    private static void labelComponents(boolean[] bgLike, int width, int height,
                                        int[] labels, int[] queue,
                                        List<int[]> stats) {
        int n = bgLike.length;
        int components = 0;
        for (int start = 0; start < n; start++) {
            if (labels[start] != 0) {
                continue;
            }
            components++;
            boolean kind = bgLike[start];
            int[] st = new int[]{0, width, height, -1, -1, kind ? 1 : 0};
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
                if (x > 0 && labels[index - 1] == 0 && bgLike[index - 1] == kind) {
                    labels[index - 1] = components;
                    queue[tail++] = index - 1;
                }
                if (x + 1 < width && labels[index + 1] == 0 && bgLike[index + 1] == kind) {
                    labels[index + 1] = components;
                    queue[tail++] = index + 1;
                }
                if (y > 0 && labels[index - width] == 0 && bgLike[index - width] == kind) {
                    labels[index - width] = components;
                    queue[tail++] = index - width;
                }
                if (y + 1 < height && labels[index + width] == 0 && bgLike[index + width] == kind) {
                    labels[index + width] = components;
                    queue[tail++] = index + width;
                }
            }
        }
    }

    /**
     * The bubble interior: the largest background component whose bounding
     * box contains the patch centre (the patch is grown around the OCR
     * text box), falling back to the largest background component.
     */
    private static int pickInterior(List<int[]> stats, int width, int height) {
        int cx = width / 2;
        int cy = height / 2;
        int interior = 0;
        int best = -1;
        int fallback = 0;
        int fallbackBest = -1;
        for (int id = 1; id < stats.size(); id++) {
            int[] st = stats.get(id);
            if (st[5] == 0) {
                continue;
            }
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
        return interior != 0 ? interior : fallback;
    }

    /** Two-pass Chebyshev (square) distance to the nearest true pixel. */
    private static void chebyshevDistance(boolean[] source, int[] out,
                                          int width, int height) {
        final int inf = Integer.MAX_VALUE / 4;
        for (int i = 0; i < source.length; i++) {
            out[i] = source[i] ? 0 : inf;
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                int best = out[index];
                if (x > 0) best = Math.min(best, out[index - 1] + 1);
                if (y > 0) {
                    best = Math.min(best, out[index - width] + 1);
                    if (x > 0) best = Math.min(best, out[index - width - 1] + 1);
                    if (x + 1 < width) best = Math.min(best, out[index - width + 1] + 1);
                }
                out[index] = best;
            }
        }
        for (int y = height - 1; y >= 0; y--) {
            for (int x = width - 1; x >= 0; x--) {
                int index = y * width + x;
                int best = out[index];
                if (x + 1 < width) best = Math.min(best, out[index + 1] + 1);
                if (y + 1 < height) {
                    best = Math.min(best, out[index + width] + 1);
                    if (x + 1 < width) best = Math.min(best, out[index + width + 1] + 1);
                    if (x > 0) best = Math.min(best, out[index + width - 1] + 1);
                }
                out[index] = best;
            }
        }
    }

    /** Marks every pixel reachable from the patch edge without crossing the barrier. */
    private static boolean[] floodOutside(boolean[] barrier, int width, int height,
                                          int[] queue) {
        boolean[] outside = new boolean[barrier.length];
        int head = 0;
        int tail = 0;
        for (int x = 0; x < width; x++) {
            int top = x;
            int bottom = (height - 1) * width + x;
            if (!barrier[top] && !outside[top]) {
                outside[top] = true;
                queue[tail++] = top;
            }
            if (!barrier[bottom] && !outside[bottom]) {
                outside[bottom] = true;
                queue[tail++] = bottom;
            }
        }
        for (int y = 0; y < height; y++) {
            int left = y * width;
            int right = y * width + width - 1;
            if (!barrier[left] && !outside[left]) {
                outside[left] = true;
                queue[tail++] = left;
            }
            if (!barrier[right] && !outside[right]) {
                outside[right] = true;
                queue[tail++] = right;
            }
        }
        while (head < tail) {
            int index = queue[head++];
            int x = index % width;
            int y = index / width;
            if (x > 0 && !outside[index - 1] && !barrier[index - 1]) {
                outside[index - 1] = true;
                queue[tail++] = index - 1;
            }
            if (x + 1 < width && !outside[index + 1] && !barrier[index + 1]) {
                outside[index + 1] = true;
                queue[tail++] = index + 1;
            }
            if (y > 0 && !outside[index - width] && !barrier[index - width]) {
                outside[index - width] = true;
                queue[tail++] = index - width;
            }
            if (y + 1 < height && !outside[index + width] && !barrier[index + width]) {
                outside[index + width] = true;
                queue[tail++] = index + width;
            }
        }
        return outside;
    }

    /**
     * BFS distance through ink pixels only, starting from ink that is
     * already outside (border/artwork). Letters enclosed by the interior
     * keep a large or infinite distance.
     */
    private static void inkDistance(boolean[] bgLike, boolean[] outside, int[] dist,
                                    int width, int height, int[] queue) {
        final int inf = Integer.MAX_VALUE / 4;
        int head = 0;
        int tail = 0;
        for (int i = 0; i < dist.length; i++) {
            if (!bgLike[i] && outside[i]) {
                dist[i] = 0;
                queue[tail++] = i;
            } else {
                dist[i] = inf;
            }
        }
        while (head < tail) {
            int index = queue[head++];
            int x = index % width;
            int y = index / width;
            int next = dist[index] + 1;
            if (x > 0 && !bgLike[index - 1] && dist[index - 1] == inf) {
                dist[index - 1] = next;
                queue[tail++] = index - 1;
            }
            if (x + 1 < width && !bgLike[index + 1] && dist[index + 1] == inf) {
                dist[index + 1] = next;
                queue[tail++] = index + 1;
            }
            if (y > 0 && !bgLike[index - width] && dist[index - width] == inf) {
                dist[index - width] = next;
                queue[tail++] = index - width;
            }
            if (y + 1 < height && !bgLike[index + width] && dist[index + width] == inf) {
                dist[index + width] = next;
                queue[tail++] = index + width;
            }
        }
    }

    /**
     * Multi-source BFS fill: erased pixels take the colour of the nearest
     * interior (seed) pixel. Non-seed artwork/border pixels are blocked
     * and never donate colour.
     */
    private static void fillFromSeeds(int[] pixels, boolean[] erase, boolean[] seed,
                                      int width, int height, int[] queue, int refColor) {
        // 0 = erased & unfilled, 1 = filled/seed, 2 = blocked (kept artwork)
        byte[] state = new byte[pixels.length];
        int head = 0;
        int tail = 0;
        for (int i = 0; i < pixels.length; i++) {
            if (seed[i] && !erase[i]) {
                state[i] = 1;
                queue[tail++] = i;
            } else if (!erase[i]) {
                state[i] = 2;
            }
        }
        while (head < tail) {
            int index = queue[head++];
            int x = index % width;
            int y = index / width;
            int color = pixels[index];
            if (x > 0 && state[index - 1] == 0) {
                state[index - 1] = 1;
                pixels[index - 1] = color;
                queue[tail++] = index - 1;
            }
            if (x + 1 < width && state[index + 1] == 0) {
                state[index + 1] = 1;
                pixels[index + 1] = color;
                queue[tail++] = index + 1;
            }
            if (y > 0 && state[index - width] == 0) {
                state[index - width] = 1;
                pixels[index - width] = color;
                queue[tail++] = index - width;
            }
            if (y + 1 < height && state[index + width] == 0) {
                state[index + width] = 1;
                pixels[index + width] = color;
                queue[tail++] = index + width;
            }
        }
        int opaqueRef = Color.argb(255,
                Color.red(refColor), Color.green(refColor), Color.blue(refColor));
        for (int i = 0; i < pixels.length; i++) {
            if (erase[i] && state[i] == 0) {
                pixels[i] = opaqueRef;
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

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
