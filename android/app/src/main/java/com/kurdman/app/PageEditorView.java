package com.kurdman.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Touch canvas for moving/resizing/rotating translated blocks on a page.
 *
 * <p>One finger moves a block (or pans empty space), the square corner
 * handle resizes, the round top handle rotates, and two fingers pinch-zoom
 * the page. The view draws the original page, the accepted source-text
 * cleaning, and the live styled Kurdish text underneath the editing guides.</p>
 */
public class PageEditorView extends View {
    public interface Listener {
        void onSelectionChanged(int index);

        void onGeometryGestureStarted();

        void onGeometryGestureFinished();

        void onDoubleTapSegment(int index);

        /** Return true when a tap was consumed, for example by the sampler. */
        boolean onImageTap(float imageX, float imageY);
    }

    private enum Mode {
        NONE, MOVE, RESIZE, ROTATE, PAN
    }

    private Bitmap bitmap;
    private List<Segment> segments = new ArrayList<>();
    private int selectedIndex = -1;
    private StyledTextRenderer.TypefaceResolver resolver;
    private Listener listener;
    private boolean peekOriginal = false;
    private boolean snapEnabled = true;

    private final Matrix imageMatrix = new Matrix();
    private final Matrix inverseMatrix = new Matrix();
    private boolean matrixInitialized = false;
    private final ScaleGestureDetector scaleDetector;

    private Mode mode = Mode.NONE;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private final PointF downView = new PointF();
    private final PointF downImage = new PointF();
    private final PointF lastPanView = new PointF();
    private Rect initialBox = new Rect();
    private float initialRotation = 0f;
    private float initialPointerAngle = 0f;
    private long lastTapTime = 0L;
    private float lastTapX = 0f;
    private float lastTapY = 0f;

    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Map<Segment, CachedFit> fitCache = new HashMap<>();

    private static final class CachedFit {
        String signature;
        StyledTextRenderer.FitResult fit;
    }

    public PageEditorView(Context context) {
        this(context, null);
    }

    public PageEditorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float factor = detector.getScaleFactor();
                        if (factor <= 0f || Float.isNaN(factor)) {
                            return false;
                        }
                        imageMatrix.postScale(factor, factor,
                                detector.getFocusX(), detector.getFocusY());
                        invalidate();
                        return true;
                    }
                });
        overlayPaint.setStyle(Paint.Style.STROKE);
        overlayPaint.setStrokeWidth(dp(1.4f));
        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStrokeWidth(dp(2.6f));
        selectedPaint.setColor(Color.rgb(45, 212, 191));
        handlePaint.setStyle(Paint.Style.FILL);
        handleStrokePaint.setStyle(Paint.Style.STROKE);
        handleStrokePaint.setStrokeWidth(dp(2f));
        handleStrokePaint.setColor(Color.WHITE);
        badgePaint.setStyle(Paint.Style.FILL);
        badgeTextPaint.setColor(Color.WHITE);
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);
        badgeTextPaint.setTextSize(dp(11f));
        badgeTextPaint.setFakeBoldText(true);
        setFocusable(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setResolver(StyledTextRenderer.TypefaceResolver resolver) {
        this.resolver = resolver;
        fitCache.clear();
        invalidate();
    }

    public void setPage(Bitmap newBitmap, List<Segment> newSegments, int newSelected) {
        bitmap = newBitmap;
        segments = newSegments == null ? new ArrayList<>() : newSegments;
        selectedIndex = clampSelection(newSelected);
        matrixInitialized = false;
        fitCache.clear();
        if (getWidth() > 0 && getHeight() > 0) {
            fitToScreen();
        }
        invalidate();
    }

    public void setSegments(List<Segment> newSegments, int newSelected) {
        segments = newSegments == null ? new ArrayList<>() : newSegments;
        selectedIndex = clampSelection(newSelected);
        fitCache.clear();
        invalidate();
    }

    public void setSelectedIndex(int index, boolean notify) {
        int next = clampSelection(index);
        if (next != selectedIndex) {
            selectedIndex = next;
            if (notify && listener != null) {
                listener.onSelectionChanged(selectedIndex);
            }
        }
        invalidate();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public Segment getSelectedSegment() {
        return selectedIndex >= 0 && selectedIndex < segments.size()
                ? segments.get(selectedIndex) : null;
    }

    public Bitmap getBitmap() {
        return bitmap;
    }

    public void setPeekOriginal(boolean peek) {
        peekOriginal = peek;
        invalidate();
    }

    public void setSnapEnabled(boolean enabled) {
        snapEnabled = enabled;
    }

    public void refreshStyles() {
        fitCache.clear();
        invalidate();
    }

    public void fitToScreen() {
        if (bitmap == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        float scale = Math.min(
                getWidth() / (float) bitmap.getWidth(),
                getHeight() / (float) bitmap.getHeight());
        imageMatrix.reset();
        imageMatrix.postScale(scale, scale);
        float dx = (getWidth() - bitmap.getWidth() * scale) / 2f;
        float dy = (getHeight() - bitmap.getHeight() * scale) / 2f;
        imageMatrix.postTranslate(dx, dy);
        matrixInitialized = true;
        invalidate();
    }

    public void zoomBy(float factor) {
        imageMatrix.postScale(factor, factor, getWidth() / 2f, getHeight() / 2f);
        invalidate();
    }

    public void centerOnSegment(int index) {
        if (bitmap == null || index < 0 || index >= segments.size()
                || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        Rect box = segments.get(index).getRenderBox();
        float fitScale = Math.min(
                getWidth() / (float) bitmap.getWidth(),
                getHeight() / (float) bitmap.getHeight());
        float desired = Math.min(
                getWidth() * 0.82f / Math.max(1f, box.width()),
                getHeight() * 0.66f / Math.max(1f, box.height()));
        float scale = Math.max(fitScale, Math.min(desired, fitScale * 8f));
        imageMatrix.reset();
        imageMatrix.postScale(scale, scale);
        float dx = getWidth() / 2f - box.exactCenterX() * scale;
        float dy = getHeight() / 2f - box.exactCenterY() * scale;
        imageMatrix.postTranslate(dx, dy);
        matrixInitialized = true;
        invalidate();
    }

    public PointF visibleImageCenter() {
        return viewToImage(getWidth() / 2f, getHeight() / 2f);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (!matrixInitialized && bitmap != null) {
            fitToScreen();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(2, 6, 23));
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        if (!matrixInitialized) {
            fitToScreen();
        }

        canvas.save();
        canvas.concat(imageMatrix);
        canvas.drawBitmap(bitmap, 0f, 0f, null);
        if (!peekOriginal) {
            StyledTextRenderer.eraseOriginals(canvas, segments);
            for (Segment segment : segments) {
                if (segment.ensureStyle().visible) {
                    StyledTextRenderer.drawBackground(canvas, segment);
                }
            }
            for (Segment segment : segments) {
                if (!segment.ensureStyle().visible
                        || segment.ckb == null || segment.ckb.trim().isEmpty()) {
                    continue;
                }
                StyledTextRenderer.FitResult fit = fitFor(segment);
                if (fit != null) {
                    StyledTextRenderer.drawText(canvas, segment, fit);
                }
            }
        }
        canvas.restore();

        if (!peekOriginal) {
            drawOverlays(canvas);
        }
    }

    private StyledTextRenderer.FitResult fitFor(Segment segment) {
        String signature = fitSignature(segment);
        CachedFit cached = fitCache.get(segment);
        if (cached != null && signature.equals(cached.signature)) {
            return cached.fit;
        }
        StyledTextRenderer.FitResult fit = StyledTextRenderer.createFit(
                segment, resolver, true);
        CachedFit next = new CachedFit();
        next.signature = signature;
        next.fit = fit;
        fitCache.put(segment, next);
        return fit;
    }

    private String fitSignature(Segment segment) {
        TextStyleSettings style = segment.ensureStyle();
        Rect box = segment.getRenderBox();
        return segment.ckb + "|" + box.width() + "x" + box.height()
                + "|" + segment.box.width() + "x" + segment.box.height()
                + "|" + segment.lines
                + "|" + style.fontId + "|" + style.fontWeight
                + "|" + style.italic + "|" + style.underline
                + "|" + style.strikethrough + "|" + style.sizeScale
                + "|" + style.lineSpacing + "|" + style.alignment
                + "|" + style.paddingScale + "|" + style.autoTextColor
                + "|" + style.textColor + "|" + segment.detectedInkColor
                + "|" + style.textOpacity + "|" + style.shadow
                + "|" + style.shadowColor + "|" + style.shadowStrength;
    }

    private void drawOverlays(Canvas canvas) {
        // Draw in list order, then redraw the selected block on top.
        for (int i = 0; i < segments.size(); i++) {
            if (i != selectedIndex) {
                drawOverlay(canvas, i, false);
            }
        }
        if (selectedIndex >= 0) {
            drawOverlay(canvas, selectedIndex, true);
        }
    }

    private void drawOverlay(Canvas canvas, int index, boolean selected) {
        Segment segment = segments.get(index);
        TextStyleSettings style = segment.ensureStyle();
        List<PointF> corners = mappedCorners(segment);
        if (corners.size() != 4) {
            return;
        }
        Path path = new Path();
        path.moveTo(corners.get(0).x, corners.get(0).y);
        for (int i = 1; i < corners.size(); i++) {
            path.lineTo(corners.get(i).x, corners.get(i).y);
        }
        path.close();

        if (selected) {
            canvas.drawPath(path, selectedPaint);
        } else {
            overlayPaint.setColor(style.visible
                    ? Color.argb(190, 148, 163, 184)
                    : Color.argb(220, 251, 146, 60));
            canvas.drawPath(path, overlayPaint);
        }

        PointF topLeft = corners.get(0);
        float radius = dp(11f);
        badgePaint.setColor(selected
                ? Color.rgb(13, 148, 136)
                : (style.visible ? Color.rgb(71, 85, 105)
                        : Color.rgb(194, 65, 12)));
        canvas.drawCircle(topLeft.x, topLeft.y, radius, badgePaint);
        Paint.FontMetrics fm = badgeTextPaint.getFontMetrics();
        float textY = topLeft.y - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(String.valueOf(index + 1), topLeft.x, textY, badgeTextPaint);

        if (selected && !style.locked) {
            PointF resize = resizeHandle(segment);
            float half = dp(10f);
            handlePaint.setColor(Color.rgb(45, 212, 191));
            RectF handleRect = new RectF(
                    resize.x - half, resize.y - half, resize.x + half, resize.y + half);
            canvas.drawRoundRect(handleRect, dp(4f), dp(4f), handlePaint);
            canvas.drawRoundRect(handleRect, dp(4f), dp(4f), handleStrokePaint);

            PointF rotate = rotateHandle(segment);
            PointF topCenter = topCenter(segment);
            Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(1.5f));
            line.setColor(Color.WHITE);
            canvas.drawLine(topCenter.x, topCenter.y, rotate.x, rotate.y, line);
            handlePaint.setColor(Color.rgb(250, 204, 21));
            canvas.drawCircle(rotate.x, rotate.y, dp(10f), handlePaint);
            canvas.drawCircle(rotate.x, rotate.y, dp(10f), handleStrokePaint);
            badgeTextPaint.setTextSize(dp(12f));
            canvas.drawText("↻", rotate.x,
                    rotate.y - (fm.ascent + fm.descent) / 2f, badgeTextPaint);
            badgeTextPaint.setTextSize(dp(11f));
        }
        if (style.locked) {
            badgeTextPaint.setTextSize(dp(12f));
            PointF topRight = corners.get(1);
            canvas.drawText("🔒", topRight.x, topRight.y + dp(4f), badgeTextPaint);
            badgeTextPaint.setTextSize(dp(11f));
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) {
            return false;
        }
        getParent().requestDisallowInterceptTouchEvent(true);
        scaleDetector.onTouchEvent(event);

        int action = event.getActionMasked();
        if (event.getPointerCount() > 1) {
            if (mode != Mode.PAN) {
                finishGeometryMode();
                mode = Mode.PAN;
            }
            lastPanView.set(event.getX(), event.getY());
            return true;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                activePointerId = event.getPointerId(0);
                downView.set(event.getX(), event.getY());
                PointF imagePoint = viewToImage(event.getX(), event.getY());
                downImage.set(imagePoint.x, imagePoint.y);
                if (listener != null && listener.onImageTap(imagePoint.x, imagePoint.y)) {
                    mode = Mode.NONE;
                    return true;
                }
                handleDown(event.getX(), event.getY(), imagePoint);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    return true;
                }
                handleMove(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                finishGeometryMode();
                mode = Mode.NONE;
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == activePointerId) {
                    finishGeometryMode();
                    mode = Mode.PAN;
                    activePointerId = MotionEvent.INVALID_POINTER_ID;
                }
                return true;
            default:
                return true;
        }
    }

    private void handleDown(float viewX, float viewY, PointF imagePoint) {
        long now = System.currentTimeMillis();
        boolean doubleTap = now - lastTapTime < 340
                && Math.hypot(viewX - lastTapX, viewY - lastTapY) < dp(44f);
        lastTapTime = now;
        lastTapX = viewX;
        lastTapY = viewY;

        Segment selected = getSelectedSegment();
        if (selected != null && !selected.ensureStyle().locked) {
            if (distance(viewX, viewY, resizeHandle(selected)) <= dp(30f)) {
                beginGeometry(Mode.RESIZE, selected, imagePoint);
                return;
            }
            if (distance(viewX, viewY, rotateHandle(selected)) <= dp(30f)) {
                beginGeometry(Mode.ROTATE, selected, imagePoint);
                return;
            }
        }

        int hit = hitSegment(imagePoint);
        if (hit >= 0) {
            boolean changed = hit != selectedIndex;
            selectedIndex = hit;
            if (changed && listener != null) {
                listener.onSelectionChanged(selectedIndex);
            }
            if (doubleTap && listener != null) {
                listener.onDoubleTapSegment(hit);
            }
            Segment hitSegment = segments.get(hit);
            if (hitSegment.ensureStyle().locked) {
                mode = Mode.PAN;
                lastPanView.set(viewX, viewY);
            } else {
                beginGeometry(Mode.MOVE, hitSegment, imagePoint);
            }
            invalidate();
        } else {
            mode = Mode.PAN;
            lastPanView.set(viewX, viewY);
        }
    }

    private void beginGeometry(Mode newMode, Segment segment, PointF imagePoint) {
        mode = newMode;
        initialBox = new Rect(segment.getRenderBox());
        initialRotation = segment.ensureStyle().rotationDegrees;
        initialPointerAngle = angleDegrees(
                imagePoint.x - initialBox.exactCenterX(),
                imagePoint.y - initialBox.exactCenterY());
        if (listener != null) {
            listener.onGeometryGestureStarted();
        }
    }

    private void handleMove(float viewX, float viewY) {
        if (mode == Mode.PAN) {
            imageMatrix.postTranslate(viewX - lastPanView.x, viewY - lastPanView.y);
            lastPanView.set(viewX, viewY);
            invalidate();
            return;
        }
        Segment segment = getSelectedSegment();
        if (segment == null || mode == Mode.NONE) {
            return;
        }
        PointF current = viewToImage(viewX, viewY);
        if (mode == Mode.MOVE) {
            moveSegment(segment, current);
        } else if (mode == Mode.RESIZE) {
            resizeSegment(segment, current);
        } else if (mode == Mode.ROTATE) {
            rotateSegment(segment, current);
        }
        fitCache.remove(segment);
        invalidate();
    }

    private void moveSegment(Segment segment, PointF current) {
        int dx = Math.round(current.x - downImage.x);
        int dy = Math.round(current.y - downImage.y);
        Rect next = new Rect(initialBox);
        next.offset(dx, dy);
        if (bitmap != null) {
            int minLeft = -next.width() / 2;
            int maxLeft = bitmap.getWidth() - next.width() / 2;
            int minTop = -next.height() / 2;
            int maxTop = bitmap.getHeight() - next.height() / 2;
            next.offsetTo(
                    clampInt(next.left, minLeft, maxLeft),
                    clampInt(next.top, minTop, maxTop));
            if (snapEnabled) {
                float threshold = Math.max(6f, dp(8f) / Math.max(0.01f, currentScale()));
                if (Math.abs(next.exactCenterX() - bitmap.getWidth() / 2f) <= threshold) {
                    next.offset(Math.round(bitmap.getWidth() / 2f - next.exactCenterX()), 0);
                }
                if (Math.abs(next.exactCenterY() - bitmap.getHeight() / 2f) <= threshold) {
                    next.offset(0, Math.round(bitmap.getHeight() / 2f - next.exactCenterY()));
                }
                if (Math.abs(next.exactCenterX() - segment.box.exactCenterX()) <= threshold) {
                    next.offset(Math.round(segment.box.exactCenterX() - next.exactCenterX()), 0);
                }
                if (Math.abs(next.exactCenterY() - segment.box.exactCenterY()) <= threshold) {
                    next.offset(0, Math.round(segment.box.exactCenterY() - next.exactCenterY()));
                }
            }
        }
        segment.getRenderBox().set(next);
    }

    private void resizeSegment(Segment segment, PointF current) {
        Rect next = new Rect(initialBox);
        float rotation = segment.ensureStyle().rotationDegrees;
        if (Math.abs(rotation) < 0.1f) {
            next.right = Math.round(current.x);
            next.bottom = Math.round(current.y);
            next.right = Math.max(next.left + 28, next.right);
            next.bottom = Math.max(next.top + 20, next.bottom);
        } else {
            // For a rotated block, resize around its centre in local axes.
            PointF local = rotatePoint(
                    current.x - initialBox.exactCenterX(),
                    current.y - initialBox.exactCenterY(),
                    -rotation);
            int width = Math.max(28, Math.round(Math.abs(local.x * 2f)));
            int height = Math.max(20, Math.round(Math.abs(local.y * 2f)));
            int centerX = initialBox.centerX();
            int centerY = initialBox.centerY();
            next.left = centerX - width / 2;
            next.top = centerY - height / 2;
            next.right = next.left + width;
            next.bottom = next.top + height;
        }
        if (bitmap != null) {
            int maxWidth = Math.round(bitmap.getWidth() * 1.5f);
            int maxHeight = Math.round(bitmap.getHeight() * 1.5f);
            if (next.width() > maxWidth) {
                next.right = next.left + maxWidth;
            }
            if (next.height() > maxHeight) {
                next.bottom = next.top + maxHeight;
            }
        }
        segment.getRenderBox().set(next);
    }

    private void rotateSegment(Segment segment, PointF current) {
        float angle = angleDegrees(
                current.x - initialBox.exactCenterX(),
                current.y - initialBox.exactCenterY());
        float next = normalizeDegrees(initialRotation + angle - initialPointerAngle);
        if (snapEnabled) {
            float[] snaps = {-180f, -90f, 0f, 90f, 180f};
            for (float snap : snaps) {
                if (Math.abs(next - snap) <= 3f) {
                    next = snap;
                    break;
                }
            }
        }
        segment.ensureStyle().rotationDegrees = next;
    }

    private void finishGeometryMode() {
        if ((mode == Mode.MOVE || mode == Mode.RESIZE || mode == Mode.ROTATE)
                && listener != null) {
            listener.onGeometryGestureFinished();
        }
    }

    private int hitSegment(PointF imagePoint) {
        // Topmost blocks are drawn last, so search from the end.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            order.add(i);
        }
        Collections.reverse(order);
        for (int index : order) {
            if (pointInSegment(segments.get(index), imagePoint)) {
                return index;
            }
        }
        return -1;
    }

    private boolean pointInSegment(Segment segment, PointF imagePoint) {
        Rect box = segment.getRenderBox();
        PointF local = rotatePoint(
                imagePoint.x - box.exactCenterX(),
                imagePoint.y - box.exactCenterY(),
                -segment.ensureStyle().rotationDegrees);
        return Math.abs(local.x) <= box.width() / 2f
                && Math.abs(local.y) <= box.height() / 2f;
    }

    private List<PointF> mappedCorners(Segment segment) {
        Rect box = segment.getRenderBox();
        float[][] offsets = {
                {-box.width() / 2f, -box.height() / 2f},
                {box.width() / 2f, -box.height() / 2f},
                {box.width() / 2f, box.height() / 2f},
                {-box.width() / 2f, box.height() / 2f}
        };
        List<PointF> result = new ArrayList<>();
        for (float[] offset : offsets) {
            PointF rotated = rotatePoint(offset[0], offset[1],
                    segment.ensureStyle().rotationDegrees);
            result.add(imageToView(
                    box.exactCenterX() + rotated.x,
                    box.exactCenterY() + rotated.y));
        }
        return result;
    }

    private PointF resizeHandle(Segment segment) {
        List<PointF> corners = mappedCorners(segment);
        return corners.size() > 2 ? corners.get(2) : new PointF();
    }

    private PointF topCenter(Segment segment) {
        Rect box = segment.getRenderBox();
        PointF rotated = rotatePoint(0f, -box.height() / 2f,
                segment.ensureStyle().rotationDegrees);
        return imageToView(box.exactCenterX() + rotated.x,
                box.exactCenterY() + rotated.y);
    }

    private PointF rotateHandle(Segment segment) {
        Rect box = segment.getRenderBox();
        float offset = dp(34f) / Math.max(0.01f, currentScale());
        PointF rotated = rotatePoint(0f, -box.height() / 2f - offset,
                segment.ensureStyle().rotationDegrees);
        return imageToView(box.exactCenterX() + rotated.x,
                box.exactCenterY() + rotated.y);
    }

    private PointF imageToView(float x, float y) {
        float[] points = {x, y};
        imageMatrix.mapPoints(points);
        return new PointF(points[0], points[1]);
    }

    private PointF viewToImage(float x, float y) {
        float[] points = {x, y};
        if (imageMatrix.invert(inverseMatrix)) {
            inverseMatrix.mapPoints(points);
        }
        return new PointF(points[0], points[1]);
    }

    private float currentScale() {
        float[] values = new float[9];
        imageMatrix.getValues(values);
        return Math.abs(values[Matrix.MSCALE_X]);
    }

    private int clampSelection(int index) {
        if (segments == null || segments.isEmpty()) {
            return -1;
        }
        return Math.max(0, Math.min(index, segments.size() - 1));
    }

    private float distance(float x, float y, PointF point) {
        return (float) Math.hypot(x - point.x, y - point.y);
    }

    private float angleDegrees(float x, float y) {
        return (float) Math.toDegrees(Math.atan2(y, x));
    }

    private PointF rotatePoint(float x, float y, float degrees) {
        double radians = Math.toRadians(degrees);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        return new PointF(x * cos - y * sin, x * sin + y * cos);
    }

    private float normalizeDegrees(float value) {
        while (value > 180f) {
            value -= 360f;
        }
        while (value < -180f) {
            value += 360f;
        }
        return value;
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
