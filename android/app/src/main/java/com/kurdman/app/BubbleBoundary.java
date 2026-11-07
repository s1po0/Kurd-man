package com.kurdman.app;

import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.BitSet;

/**
 * Immutable page-space bubble mask. Each row keeps ALL disjoint half-open runs,
 * not just its left/right extremes. No detector/model or new runtime dependency.
 * Stored with projects so studio/reopened exports use the same contour.
 */
public final class BubbleBoundary {
    static final int MAX_PIXELS = 3_500_000;
    private static final int MAX_SIDE = 65_536;
    private static final int MAX_ENDPOINTS = 500_000;
    private static final int[] NONE = new int[0];
    static final BubbleBoundary EMPTY = new BubbleBoundary(new Rect(0, 0, 1, 1),
            new int[][]{NONE});
    private final Rect bounds;
    private final int[][] rows;
    private final int pixels;
    private final long signature;
    private volatile Path clipPath;
    private volatile Rect anchor;

    private BubbleBoundary(Rect bounds, int[][] rows) {
        this.bounds = new Rect(bounds);
        this.rows = rows;
        int count = 0;
        long hash = bounds.hashCode();
        for (int[] row : rows) {
            for (int i = 0; i < row.length; i += 2) count += row[i + 1] - row[i];
            hash = (hash ^ Arrays.hashCode(row)) * 1099511628211L;
        }
        pixels = count;
        signature = hash;
    }

    private static boolean bounded(Rect rect) {
        return rect != null && rect.left >= 0 && rect.top >= 0
                && rect.right > rect.left && rect.bottom > rect.top
                && rect.width() > 0 && rect.height() > 0
                && rect.width() <= MAX_SIDE && rect.height() <= MAX_SIDE
                && (long) rect.width() * rect.height() <= MAX_PIXELS;
    }

    boolean isEmpty() { return pixels == 0; }
    long signature() { return signature; }
    Rect bounds() { return new Rect(bounds); }

    boolean contains(int x, int y) {
        if (!bounds.contains(x, y)) return false;
        int[] row = rows[y - bounds.top];
        int xx = x - bounds.left;
        for (int i = 0; i < row.length; i += 2) {
            if (xx < row[i]) return false;
            if (xx < row[i + 1]) return true;
        }
        return false;
    }

    /** Reuse the detector's labels/queue; fill enclosed source-letter holes only. */
    static BubbleBoundary fromComponent(Rect region, int[] labels, int component,
                                         Rect sourceText, int[] queue) {
        if (!bounded(region) || labels.length != region.width() * region.height()) return EMPTY;
        int w = region.width(), h = region.height(), n = w * h;
        BitSet exterior = new BitSet(n);
        int tail = 0;
        for (int y = 0; y < h; y++) {
            for (int x : new int[]{0, w - 1}) {
                int index = y * w + x;
                if (labels[index] != component && !exterior.get(index)) {
                    exterior.set(index); queue[tail++] = index;
                }
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y : new int[]{0, h - 1}) {
                int index = y * w + x;
                if (labels[index] != component && !exterior.get(index)) {
                    exterior.set(index); queue[tail++] = index;
                }
            }
        }
        for (int head = 0; head < tail; head++) {
            int index = queue[head], x = index % w, y = index / w;
            if (x > 0 && labels[index - 1] != component && !exterior.get(index - 1)) {
                exterior.set(index - 1); queue[tail++] = index - 1;
            }
            if (x + 1 < w && labels[index + 1] != component && !exterior.get(index + 1)) {
                exterior.set(index + 1); queue[tail++] = index + 1;
            }
            if (y > 0 && labels[index - w] != component && !exterior.get(index - w)) {
                exterior.set(index - w); queue[tail++] = index - w;
            }
            if (y + 1 < h && labels[index + w] != component && !exterior.get(index + w)) {
                exterior.set(index + w); queue[tail++] = index + w;
            }
        }
        Rect letters = new Rect(sourceText);
        letters.inset(-2, -2);
        int[][] rows = new int[h][];
        int[] temp = new int[w + 1];
        int endpoints = 0;
        for (int y = 0; y < h; y++) {
            boolean inRun = false;
            int used = 0;
            for (int x = 0; x <= w; x++) {
                int index = y * w + x;
                boolean inside = x < w && (labels[index] == component
                        || (!exterior.get(index) && letters.contains(region.left + x, region.top + y)));
                if (inside != inRun) { temp[used++] = x; inRun = inside; }
            }
            endpoints += used;
            if (endpoints > MAX_ENDPOINTS) return EMPTY;
            rows[y] = used == 0 ? NONE : Arrays.copyOf(temp, used);
        }
        return new BubbleBoundary(region, rows);
    }

    /** Legacy saved projects get conservative span masks until re-traced on source. */
    static BubbleBoundary fromSpans(int top, int[] left, int[] right) {
        if (left == null || right == null || left.length != right.length
                || left.length == 0 || left.length > MAX_SIDE || top < 0) return EMPTY;
        int min = Integer.MAX_VALUE, max = -1;
        for (int i = 0; i < left.length; i++) {
            if (left[i] >= 0 && right[i] >= left[i]) {
                min = Math.min(min, left[i]); max = Math.max(max, right[i]);
            }
        }
        if (max < min || (long) max - min + 1 > MAX_SIDE
                || (long) top + left.length > Integer.MAX_VALUE) return EMPTY;
        Rect bounds = new Rect(min, top, max + 1, top + left.length);
        if (!bounded(bounds)) return EMPTY;
        int[][] rows = new int[left.length][];
        for (int y = 0; y < rows.length; y++) rows[y] = left[y] < 0 || right[y] < left[y]
                ? NONE : new int[]{left[y] - min, right[y] - min + 1};
        return new BubbleBoundary(bounds, rows);
    }

    static BubbleBoundary rectangle(Rect rect) {
        if (!bounded(rect)) return EMPTY;
        int[][] rows = new int[rect.height()][];
        Arrays.fill(rows, new int[]{0, rect.width()});
        return new BubbleBoundary(rect, rows);
    }

    static BubbleBoundary ellipse(Rect rect) {
        if (!bounded(rect)) return EMPTY;
        int[][] rows = new int[rect.height()][];
        double rx = rect.width() / 2.0, ry = rect.height() / 2.0;
        for (int y = 0; y < rows.length; y++) {
            double dy = (y + .5 - ry) / ry;
            double half = rx * Math.sqrt(Math.max(0, 1 - dy * dy));
            int left = Math.max(0, (int) Math.ceil(rx - half - .5));
            int right = Math.min(rect.width(), (int) Math.floor(rx + half - .5) + 1);
            rows[y] = right > left ? new int[]{left, right} : NONE;
        }
        return new BubbleBoundary(rect, rows);
    }

    BubbleBoundary intersect(Rect rect) {
        Rect overlap = new Rect(bounds);
        if (!overlap.intersect(rect) || isEmpty()) return EMPTY;
        int[][] result = new int[overlap.height()][];
        for (int y = 0; y < result.length; y++) {
            int[] old = rows[overlap.top - bounds.top + y], temp = new int[old.length];
            int used = 0;
            for (int i = 0; i < old.length; i += 2) {
                int start = Math.max(bounds.left + old[i], overlap.left);
                int end = Math.min(bounds.left + old[i + 1], overlap.right);
                if (end > start) { temp[used++] = start - overlap.left; temp[used++] = end - overlap.left; }
            }
            result[y] = used == 0 ? NONE : Arrays.copyOf(temp, used);
        }
        return new BubbleBoundary(overlap, result);
    }

    /** Square-kernel erosion keeps padding at every notch, hole and inward spike. */
    BubbleBoundary inset(int radius) {
        if (radius <= 0 || isEmpty()) return this;
        if ((long) radius * 2 >= bounds.width() || (long) radius * 2 >= bounds.height()) return EMPTY;
        int[][] result = new int[rows.length][];
        Arrays.fill(result, NONE);
        for (int y = radius; y < rows.length - radius; y++) {
            int[] common = rows[y];
            for (int yy = y - radius; yy <= y + radius && common.length > 0; yy++) {
                if (yy != y) common = intersection(common, rows[yy]);
            }
            int[] temp = new int[common.length]; int used = 0;
            for (int i = 0; i < common.length; i += 2) {
                int start = common[i] + radius, end = common[i + 1] - radius;
                if (end > start) { temp[used++] = start; temp[used++] = end; }
            }
            result[y] = used == 0 ? NONE : Arrays.copyOf(temp, used);
        }
        return new BubbleBoundary(bounds, result);
    }

    private static int[] intersection(int[] a, int[] b) {
        int[] temp = new int[a.length + b.length]; int used = 0, i = 0, j = 0;
        while (i < a.length && j < b.length) {
            int start = Math.max(a[i], b[j]), end = Math.min(a[i + 1], b[j + 1]);
            if (end > start) { temp[used++] = start; temp[used++] = end; }
            if (a[i + 1] < b[j + 1]) i += 2; else j += 2;
        }
        return used == 0 ? NONE : Arrays.copyOf(temp, used);
    }

    /** Largest real interior rectangle (histogram scan), not a centre/corner sample. */
    Rect placementAnchor() {
        Rect cached = anchor;
        if (cached != null) return new Rect(cached);
        int w = bounds.width();
        int[] heights = new int[w + 1], stack = new int[w + 1];
        Rect best = new Rect();
        long bestArea = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int y = 0; y < rows.length; y++) {
            if (Thread.currentThread().isInterrupted()) return new Rect();
            int[] row = rows[y]; int run = 0;
            for (int x = 0; x < w; x++) {
                while (run < row.length && x >= row[run + 1]) run += 2;
                heights[x] = run < row.length && x >= row[run] ? heights[x] + 1 : 0;
            }
            int top = -1;
            for (int x = 0; x <= w; x++) {
                while (top >= 0 && heights[stack[top]] > heights[x]) {
                    int height = heights[stack[top--]];
                    int left = top < 0 ? 0 : stack[top] + 1;
                    long area = (long) (x - left) * height;
                    double dx = (left + x) / 2.0 - w / 2.0;
                    double dy = y + 1 - height / 2.0 - rows.length / 2.0;
                    double distance = dx * dx + dy * dy;
                    if (area > bestArea || (area == bestArea && distance < bestDistance)) {
                        bestArea = area; bestDistance = distance;
                        best.set(bounds.left + left, bounds.top + y + 1 - height,
                                bounds.left + x, bounds.top + y + 1);
                    }
                }
                stack[++top] = x;
            }
        }
        anchor = best;
        return new Rect(best);
    }

    void clip(Canvas canvas) {
        Path path = clipPath;
        if (path == null) {
            path = new Path();
            for (int y = 0; y < rows.length;) {
                int bottom = y + 1;
                while (bottom < rows.length && Arrays.equals(rows[y], rows[bottom])) bottom++;
                for (int i = 0; i < rows[y].length; i += 2) path.addRect(
                        bounds.left + rows[y][i], bounds.top + y,
                        bounds.left + rows[y][i + 1], bounds.top + bottom, Path.Direction.CW);
                y = bottom;
            }
            clipPath = path;
        }
        canvas.clipPath(path);
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("v", 1); json.put("x", bounds.left); json.put("y", bounds.top);
        json.put("w", bounds.width()); json.put("h", bounds.height());
        JSONArray data = new JSONArray();
        for (int[] row : rows) {
            JSONArray values = new JSONArray();
            for (int value : row) values.put(value);
            data.put(values);
        }
        return json.put("runs", data);
    }

    /** Malformed/present masks fail CLOSED, rather than silently losing protection. */
    static BubbleBoundary fromJson(JSONObject json) {
        try {
            if (json == null || json.getInt("v") != 1) return EMPTY;
            int x = json.getInt("x"), y = json.getInt("y");
            int w = json.getInt("w"), h = json.getInt("h");
            if (x < 0 || y < 0 || (long) x + w > Integer.MAX_VALUE
                    || (long) y + h > Integer.MAX_VALUE) return EMPTY;
            Rect rect = new Rect(x, y, x + w, y + h);
            if (!bounded(rect)) return EMPTY;
            JSONArray data = json.getJSONArray("runs");
            if (data.length() != h) return EMPTY;
            int endpoints = 0; int[][] rows = new int[h][];
            for (int yy = 0; yy < h; yy++) {
                JSONArray values = data.getJSONArray(yy);
                int length = values.length(); endpoints += length;
                if (length % 2 != 0 || endpoints > MAX_ENDPOINTS || length > w + 1) return EMPTY;
                int[] row = new int[length]; int previousEnd = -1;
                for (int i = 0; i < length; i += 2) {
                    int start = values.getInt(i), end = values.getInt(i + 1);
                    if (start < 0 || start < previousEnd || end <= start || end > w) return EMPTY;
                    row[i] = start; row[i + 1] = end; previousEnd = end;
                }
                rows[yy] = row;
            }
            return new BubbleBoundary(rect, rows);
        } catch (JSONException | RuntimeException invalid) {
            return EMPTY;
        }
    }
}
