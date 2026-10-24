package com.kurdman.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

/**
 * "Clean erase" for source text: removes only the original glyphs from a
 * bubble and fills their pixels with the surrounding background, instead
 * of painting one solid rectangle over the whole text area. The result
 * looks like an untouched empty bubble, ready for the Kurdish typesetting.
 */
final class CleanEraser {
    /** Above this many pixels we fall back to a plain detected-colour fill. */
    private static final int MAX_PIXELS = 1_800_000;
    /** A pixel counts as ink when it differs from the bubble colour by this much. */
    private static final int INK_DISTANCE = 52;
    /** Pixels within this distance of the bubble colour count as "flat". */
    private static final int FLAT_DISTANCE = 20;

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

        int bgRed = Color.red(backgroundColor);
        int bgGreen = Color.green(backgroundColor);
        int bgBlue = Color.blue(backgroundColor);

        boolean[] mask = new boolean[pixels.length];
        int maskedCount = 0;
        int flatCount = 0;
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int distance = Math.max(
                    Math.abs(Color.red(pixel) - bgRed),
                    Math.max(Math.abs(Color.green(pixel) - bgGreen),
                            Math.abs(Color.blue(pixel) - bgBlue)));
            if (distance > INK_DISTANCE) {
                mask[i] = true;
                maskedCount++;
            } else if (distance <= FLAT_DISTANCE) {
                flatCount++;
            }
        }
        if (maskedCount == 0) {
            // Nothing to erase: return the untouched region.
            return Bitmap.createBitmap(pixels, width, height,
                    Bitmap.Config.ARGB_8888);
        }
        if (maskedCount >= pixels.length) {
            return solidPatch(width, height, backgroundColor);
        }

        dilate(mask, width, height,
                Math.max(1, Math.min(3, Math.round(Math.min(width, height) / 48f))));

        int known = 0;
        for (boolean masked : mask) {
            if (!masked) {
                known++;
            }
        }
        if (known == 0) {
            return solidPatch(width, height, backgroundColor);
        }

        // A normal speech bubble is almost flat: filling the glyphs with
        // the exact bubble colour reproduces a pristine empty bubble and
        // keeps all paper texture outside the letters untouched.
        boolean flatBackground = flatCount >= (int) (pixels.length * 0.45)
                && known > 0 && flatCount >= (int) (known * 0.75);
        int opaqueBg = Color.argb(255, bgRed, bgGreen, bgBlue);
        if (flatBackground) {
            for (int i = 0; i < pixels.length; i++) {
                if (mask[i]) {
                    pixels[i] = opaqueBg;
                }
            }
            return Bitmap.createBitmap(pixels, width, height,
                    Bitmap.Config.ARGB_8888);
        }

        nearestFill(pixels, mask, width, height);
        smooth(pixels, mask, width, height);
        smooth(pixels, mask, width, height);
        return Bitmap.createBitmap(pixels, width, height,
                Bitmap.Config.ARGB_8888);
    }

    private static Bitmap solidPatch(int width, int height, int color) {
        Bitmap patch = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        patch.eraseColor(Color.argb(255,
                Color.red(color), Color.green(color), Color.blue(color)));
        return patch;
    }

    /** Grows the ink mask so anti-aliased letter edges disappear too. */
    private static void dilate(boolean[] mask, int width, int height, int radius) {
        if (radius <= 0) {
            return;
        }
        boolean[] grown = mask.clone();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!mask[y * width + x]) {
                    continue;
                }
                int y0 = Math.max(0, y - radius);
                int y1 = Math.min(height - 1, y + radius);
                int x0 = Math.max(0, x - radius);
                int x1 = Math.min(width - 1, x + radius);
                for (int yy = y0; yy <= y1; yy++) {
                    for (int xx = x0; xx <= x1; xx++) {
                        grown[yy * width + xx] = true;
                    }
                }
            }
        }
        System.arraycopy(grown, 0, mask, 0, mask.length);
    }

    /**
     * Multi-source BFS: every masked pixel takes the colour of the nearest
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
