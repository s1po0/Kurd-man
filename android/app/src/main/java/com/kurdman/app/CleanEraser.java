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
     * Computes the true extent of outside-bubble lettering around an OCR
     * box, or {@code null} when the letters cannot be separated from the
     * artwork safely. OCR boxes for handwritten SFX often clip strokes
     * or a whole line, so the analysis rectangle first grows while ink
     * is cut at its edges; the returned extent is the bounding box of
     * the actual letter components plus a small margin. It also refuses
     * (returns {@code null}) when ink that cannot be erased would remain
     * inside the extent — the half-erased look must never happen.
     */
    static Rect outsideExtent(Bitmap source, Rect ocrBox, boolean brightInk) {
        if (source == null || source.isRecycled() || ocrBox == null) {
            return null;
        }
        Rect box = new Rect(ocrBox);
        if (!box.intersect(0, 0, source.getWidth(), source.getHeight())
                || box.width() <= 0 || box.height() <= 0) {
            return null;
        }
        int padX = Math.max(8, Math.round(box.width() * 0.14f));
        int padY = Math.max(10, Math.round(box.height() * 0.38f));
        Rect rect = expanded(box, padX, padY, source);
        long initialArea = (long) rect.width() * rect.height();
        OutsideAnalysis analysis = null;
        for (int iteration = 0; iteration < 4; iteration++) {
            int[] pixels = readPixels(source, rect);
            if (pixels == null) {
                return null;
            }
            analysis = analyzeOutside(pixels, rect.width(), rect.height(),
                    brightInk, anchorIn(rect, box));
            if (analysis.kept == 0) {
                return null;
            }
            Rect grown = grownRect(rect, analysis.touchSides, box, source);
            if (grown.equals(rect)
                    || (long) grown.width() * grown.height() > initialArea * 4) {
                break;
            }
            rect = grown;
            analysis = null;
        }
        if (analysis == null) {
            int[] pixels = readPixels(source, rect);
            if (pixels == null) {
                return null;
            }
            analysis = analyzeOutside(pixels, rect.width(), rect.height(),
                    brightInk, anchorIn(rect, box));
            if (analysis.kept == 0) {
                return null;
            }
        }
        int n = rect.width() * rect.height();
        if (analysis.kept < n * 0.004 || analysis.kept > n * 0.42) {
            return null;
        }
        int margin = Math.max(8, Math.round(box.height() * 0.18f));
        Rect extent = new Rect(analysis.keepBounds);
        extent.offset(rect.left, rect.top);
        extent.inset(-margin, -margin);
        // Artwork that happens to be accepted as "ink" far from the OCR
        // box (hair spikes below the lettering) must not stretch the
        // extent: cap it to a generous dilation of the OCR box itself.
        Rect cap = expanded(box,
                Math.max(30, Math.round(box.width() * 0.30f)),
                Math.max(36, Math.round(box.height() * 0.60f)), source);
        if (!extent.intersect(cap) || !extent.intersect(rect)) {
            return null;
        }
        Rect extentLocal = new Rect(extent);
        extentLocal.offset(-rect.left, -rect.top);
        if (ghostInkInside(analysis, extentLocal) > analysis.kept * 0.30) {
            return null;
        }
        return extent;
    }

    /**
     * Ink that would survive an erase inside {@code area}: only
     * rejected components lying fully inside it count (a word the
     * analysis refused). Artwork entering from outside — hair spikes,
     * a balloon tail — has a bounding box reaching beyond the area
     * and is supposed to remain; counting its pixels made the guard
     * refuse perfectly erasable SFX next to white hair.
     */
    private static int ghostInkInside(OutsideAnalysis analysis, Rect area) {
        int ghost = 0;
        for (int[] other : analysis.otherBoxes) {
            if (other[0] >= area.left - 2 && other[1] >= area.top - 2
                    && other[2] <= area.right + 2
                    && other[3] <= area.bottom + 2) {
                ghost += other[4];
            }
        }
        return ghost;
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
     * hair) are rejected by density and edge contact, as is ink that
     * does not belong to the OCR block. The erase mask grows a few
     * pixels through the strokes, their neutral outline and the glow
     * right around them. Rejected ink (balloon white, artwork) is
     * blocked: it can neither be erased nor donate its colour into the
     * fill, which is then diffused so gradients like sky continue
     * seamlessly.</p>
     */
    static Bitmap createOutsidePatch(Bitmap source, Rect rect, Rect ocrBox,
                                     boolean brightInk) {
        if (source == null || source.isRecycled() || rect == null
                || rect.width() <= 0 || rect.height() <= 0) {
            return null;
        }
        int[] pixels = readPixels(source, rect);
        if (pixels == null) {
            return null;
        }
        int width = rect.width();
        int height = rect.height();
        int n = pixels.length;
        Rect anchor = ocrBox != null ? anchorIn(rect, ocrBox) : null;
        OutsideAnalysis analysis = analyzeOutside(
                pixels, width, height, brightInk, anchor);
        if (analysis.kept == 0
                || analysis.kept < n * 0.004 || analysis.kept > n * 0.50) {
            return null;
        }
        // The extent was already ghost-checked on a larger rectangle;
        // here only a catastrophic mismatch (most ink unerased, fully
        // inside the patch) refuses. Artwork touching the patch edges
        // is expected and stays.
        Rect inner = new Rect(3, 3, width - 3, height - 3);
        if (ghostInkInside(analysis, inner) > analysis.kept * 0.60) {
            return null;
        }

        int[] dist = new int[n];
        chebyshevDistance(analysis.keep, dist, width, height);
        int bgLuminance = backgroundLuminance(pixels, analysis, dist);
        boolean[] fill = new boolean[n];
        int fillCount = 0;
        for (int i = 0; i < n; i++) {
            boolean mask = analysis.keep[i] || dist[i] <= 3
                    || (dist[i] <= 6 && channelSpread(pixels[i]) <= 30);
            if (!mask && !analysis.otherCore[i] && dist[i] <= 8
                    && Math.abs(luminanceOf(pixels[i]) - bgLuminance) > 55) {
                mask = true; // letter glow/haze right around the strokes
            }
            fill[i] = mask;
            if (mask) {
                fillCount++;
            }
        }
        if (fillCount > n * 0.62) {
            return null;
        }

        boolean[] seed = new boolean[n];
        boolean[] blocked = new boolean[n];
        long redSum = 0;
        long greenSum = 0;
        long blueSum = 0;
        int seedSamples = 0;
        for (int i = 0; i < n; i++) {
            blocked[i] = analysis.otherCore[i] && !fill[i];
            seed[i] = !fill[i] && !analysis.otherCore[i];
            if (seed[i] && (i & 7) == 0) {
                redSum += Color.red(pixels[i]);
                greenSum += Color.green(pixels[i]);
                blueSum += Color.blue(pixels[i]);
                seedSamples++;
            }
        }
        int refColor = seedSamples > 0
                ? Color.rgb((int) (redSum / seedSamples),
                        (int) (greenSum / seedSamples),
                        (int) (blueSum / seedSamples))
                : Color.rgb(bgLuminance, bgLuminance, bgLuminance);
        int[] queue = new int[n];
        fillFromSeeds(pixels, fill, seed, width, height, queue, refColor);
        diffuse(pixels, fill, blocked, width, height,
                n > 600_000 ? 25 : 50);
        return Bitmap.createBitmap(pixels, width, height,
                Bitmap.Config.ARGB_8888);
    }

    private static final class OutsideAnalysis {
        /** Ink cores accepted as the block's letters. */
        boolean[] keep;
        /** Ink cores rejected (artwork, balloon, other blocks' text). */
        boolean[] otherCore;
        int kept;
        Rect keepBounds;
        /** Bit flags: 1 top, 2 bottom, 4 left, 8 right — an accepted
         * component is actually cut at that side of the patch. */
        int touchSides;
        /** Per rejected component: minX, minY, maxX+1, maxY+1, area. */
        final List<int[]> otherBoxes = new ArrayList<>();
    }

    private static OutsideAnalysis analyzeOutside(int[] pixels, int width,
                                                  int height, boolean brightInk,
                                                  Rect anchorLocal) {
        int n = pixels.length;
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

        OutsideAnalysis result = new OutsideAnalysis();
        result.keep = new boolean[n];
        result.otherCore = new boolean[n];
        result.keepBounds = new Rect();
        if (components <= 0) {
            return result;
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
            boolean anchored = anchorLocal == null || Rect.intersects(
                    anchorLocal, new Rect(st[1], st[2], st[3] + 1, st[4] + 1));
            accepted[id] = !reject && anchored;
            if (accepted[id]) {
                if (runTop[id] > 0) {
                    result.touchSides |= 1;
                }
                if (runBottom[id] > 0) {
                    result.touchSides |= 2;
                }
                if (runLeft[id] > 0) {
                    result.touchSides |= 4;
                }
                if (runRight[id] > 0) {
                    result.touchSides |= 8;
                }
            } else {
                result.otherBoxes.add(new int[]{
                        st[1], st[2], st[3] + 1, st[4] + 1, area});
            }
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            int id = labels[i];
            if (id < 1 || id > components || stats.get(id)[5] == 0) {
                continue;
            }
            if (accepted[id]) {
                result.keep[i] = true;
                result.kept++;
                int x = i % width;
                int y = i / width;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            } else {
                result.otherCore[i] = true;
            }
        }
        if (result.kept > 0) {
            result.keepBounds = new Rect(minX, minY, maxX + 1, maxY + 1);
        }
        return result;
    }

    /** OCR box in rect-local coordinates, dilated so the block's own
     * letter components still count as anchored to it. */
    private static Rect anchorIn(Rect rect, Rect box) {
        Rect anchor = new Rect(box);
        anchor.offset(-rect.left, -rect.top);
        int dilateX = Math.max(4, Math.round(box.width() * 0.12f));
        int dilateY = Math.max(6, Math.round(box.height() * 0.50f));
        anchor.inset(-dilateX, -dilateY);
        return anchor;
    }

    private static Rect expanded(Rect box, int padX, int padY, Bitmap source) {
        Rect rect = new Rect(box);
        rect.inset(-padX, -padY);
        rect.intersect(0, 0, source.getWidth(), source.getHeight());
        return rect;
    }

    private static Rect grownRect(Rect rect, int touchSides, Rect box,
                                  Bitmap source) {
        int growX = Math.max(10, Math.round(box.width() * 0.20f));
        int growY = Math.max(12, Math.round(box.height() * 0.45f));
        Rect grown = new Rect(rect);
        if ((touchSides & 1) != 0) {
            grown.top -= growY;
        }
        if ((touchSides & 2) != 0) {
            grown.bottom += growY;
        }
        if ((touchSides & 4) != 0) {
            grown.left -= growX;
        }
        if ((touchSides & 8) != 0) {
            grown.right += growX;
        }
        grown.intersect(0, 0, source.getWidth(), source.getHeight());
        return grown;
    }

    private static int[] readPixels(Bitmap source, Rect rect) {
        int width = rect.width();
        int height = rect.height();
        long count = (long) width * (long) height;
        if (width <= 0 || height <= 0 || count > MAX_PIXELS) {
            return null;
        }
        int[] pixels = new int[(int) count];
        try {
            source.getPixels(pixels, 0, width,
                    rect.left, rect.top, width, height);
        } catch (RuntimeException unreadable) {
            return null;
        }
        return pixels;
    }

    /** Median luminance of pixels far from any ink (the true background). */
    private static int backgroundLuminance(int[] pixels, OutsideAnalysis analysis,
                                           int[] dist) {
        int[] histogram = new int[256];
        int total = 0;
        for (int i = 0; i < pixels.length; i++) {
            if (dist[i] > 10 && !analysis.otherCore[i]) {
                histogram[luminanceOf(pixels[i])]++;
                total++;
            }
        }
        if (total == 0) {
            return 128;
        }
        int target = total / 2;
        int seen = 0;
        for (int value = 0; value < 256; value++) {
            seen += histogram[value];
            if (seen >= target) {
                return value;
            }
        }
        return 128;
    }

    /**
     * Laplace diffusion restricted to the fill set: repeatedly replaces
     * each filled pixel by the average of its (non-blocked) neighbours,
     * scanning in alternating directions. This continues smooth
     * gradients (sky, walls) through the erased strokes without
     * visible seams, where a plain nearest-colour fill leaves cells.
     * Blocked pixels (rejected ink/artwork) never donate colour.
     */
    private static void diffuse(int[] pixels, boolean[] fill, boolean[] blocked,
                                int width, int height, int iterations) {
        for (int it = 0; it < iterations; it++) {
            boolean forward = (it % 2 == 0);
            for (int yy = 0; yy < height; yy++) {
                int y = forward ? yy : height - 1 - yy;
                for (int xx = 0; xx < width; xx++) {
                    int x = forward ? xx : width - 1 - xx;
                    int index = y * width + x;
                    if (!fill[index]) {
                        continue;
                    }
                    int red = 0;
                    int green = 0;
                    int blue = 0;
                    int neighbours = 0;
                    if (x > 0 && !blocked[index - 1]) {
                        red += Color.red(pixels[index - 1]);
                        green += Color.green(pixels[index - 1]);
                        blue += Color.blue(pixels[index - 1]);
                        neighbours++;
                    }
                    if (x + 1 < width && !blocked[index + 1]) {
                        red += Color.red(pixels[index + 1]);
                        green += Color.green(pixels[index + 1]);
                        blue += Color.blue(pixels[index + 1]);
                        neighbours++;
                    }
                    if (y > 0 && !blocked[index - width]) {
                        red += Color.red(pixels[index - width]);
                        green += Color.green(pixels[index - width]);
                        blue += Color.blue(pixels[index - width]);
                        neighbours++;
                    }
                    if (y + 1 < height && !blocked[index + width]) {
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
