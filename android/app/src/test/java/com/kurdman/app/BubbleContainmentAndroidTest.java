package com.kurdman.app;

import static org.junit.Assert.*;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.Collections;

/** Native Android/Skia glyph pixels on synthetic pages, NOT real-page/device acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 34}, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BubbleContainmentAndroidTest {
    private static final StyledTextRenderer.TypefaceResolver FONT =
            style -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    private Segment segment(Rect rect, String translation) {
        Segment s = new Segment("Synthetic source", new Rect(rect), 2);
        s.renderBox = new Rect(rect);
        s.eraseBox = new Rect(rect);
        s.ckb = translation;
        s.sourceLineHeight = 48;
        s.autoBackgroundReliable = true;
        s.ensureStyle().backgroundMode = TextStyleSettings.BG_CLEAN;
        return s;
    }

    private Bitmap ink(Segment s, StyledTextRenderer.FitResult fit) {
        Bitmap b = Bitmap.createBitmap(480, 340, Bitmap.Config.ARGB_8888);
        // Assert on UNCLIPPED production painting, then check the defensive
        // final clip discards no pixels. A clip-only "fix" must fail this suite.
        StyledTextRenderer.drawUnclipped(new Canvas(b), s, fit);
        Bitmap finalInk = Bitmap.createBitmap(480, 340, Bitmap.Config.ARGB_8888);
        assertTrue(StyledTextRenderer.drawText(new Canvas(finalInk), s, fit));
        assertTrue("Fitting must work without cutting any glyph pixels", b.sameAs(finalInk));
        finalInk.recycle();
        return b;
    }

    private int outside(Bitmap ink, Bitmap allowed) {
        int count = 0;
        for (int y = 0; y < ink.getHeight(); y++) {
            for (int x = 0; x < ink.getWidth(); x++) {
                if (Color.alpha(ink.getPixel(x, y)) != 0
                        && allowed.getPixel(x, y) != Color.WHITE) count++;
            }
        }
        return count;
    }

    private Bitmap rectangularMask(Rect rect) {
        Bitmap b = Bitmap.createBitmap(480, 340, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.GRAY);
        Paint p = new Paint(); p.setColor(Color.WHITE);
        new Canvas(b).drawRect(rect, p);
        return b;
    }

    @Test public void studioMustRejectOverflowInsteadOfReturningACroppedDialogue() {
        Segment s = segment(new Rect(220, 80, 234, 92),
                "Complete dialogue must never be silently shortened. ".repeat(100));
        assertNull("Editor must fit the WHOLE text or preserve the source",
                StyledTextRenderer.createFit(s, FONT, true));
    }

    @Test public void rejectedEditedExportMustLeaveOriginalPixelsUntouched() {
        Bitmap source = rectangularMask(new Rect(24, 24, 440, 300));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK); p.setTextSize(32);
        new Canvas(source).drawText("ORIGINAL SOURCE", 55, 105, p);
        Segment s = segment(new Rect(45, 65, 365, 115),
                "Complete translated dialogue. ".repeat(100));
        s.renderBox = new Rect(400, 220, 414, 232);
        StyledTextRenderer.EditedResult result = StyledTextRenderer.renderEdited(
                source, Collections.singletonList(s), FONT);
        assertTrue("Do not erase first and discover afterwards that the text cannot fit",
                source.sameAs(result.bitmap));
        assertEquals(0, result.rendered);
        assertFalse(s.rendered);
        source.recycle(); result.bitmap.recycle();
    }

    @Test public void draggingOutsideTheDetectedContourMustNotDisableItsSafety() {
        Segment s = segment(new Rect(30, 30, 220, 210), "لێرە بوەستە.");
        s.interiorTop = 30;
        s.interiorLeft = new int[180]; s.interiorRight = new int[180];
        java.util.Arrays.fill(s.interiorLeft, 30);
        java.util.Arrays.fill(s.interiorRight, 219);
        s.renderBox = new Rect(260, 40, 450, 220);
        assertNull("Moving the box must not detach it from the bubble boundary",
                StyledTextRenderer.createFit(s, FONT, true));
    }

    @Test public void actualRotatedOutlinedShadowedInkMustStayInsideRectangle() {
        Rect rect = new Rect(38, 42, 438, 235);
        Segment s = segment(rect, "We will return together. We will not leave you behind.");
        s.sourceLineHeight = 65;
        TextStyleSettings style = s.ensureStyle();
        style.rotationDegrees = 32;
        style.outline = true; style.outlineWidthRatio = 0.12f;
        style.shadow = true; style.shadowStrength = 1;
        style.italic = true; style.underline = true;
        StyledTextRenderer.FitResult fit = StyledTextRenderer.createFit(s, FONT, false);
        assertNotNull("A smaller complete layout should fit this roomy rectangle", fit);
        assertEquals(s.ckb, fit.layout.getText().toString());
        Bitmap raster = ink(s, fit), mask = rectangularMask(rect);
        assertEquals("Bounds must include rotation, italic overhang, underline, outline and shadow",
                0, outside(raster, mask));
        raster.recycle(); mask.recycle();
    }

    @Test public void anInwardSpikeBetweenThreeSampledRowsMustNotCutThroughLetters() {
        Rect rect = new Rect(24, 24, 440, 300);
        Segment s = segment(rect, "We will return together. Stay here and wait for us.");
        s.sourceLineHeight = 58;
        Bitmap mask = rectangularMask(rect);
        assertNotNull(ManhwaRenderer.computeInteriorSpans(mask, s, Collections.singletonList(s)));
        StyledTextRenderer.FitResult first = StyledTextRenderer.createFit(s, FONT, false);
        assertNotNull(first);
        Bitmap initialInk = ink(s, first);
        boolean[] sampled = new boolean[340];
        int originY = rect.top + first.verticalPadding + Math.max(0,
                (rect.height() - first.verticalPadding * 2 - first.layout.getHeight()) / 2);
        for (int i = 0; i < first.layout.getLineCount(); i++) {
            float h = first.layout.getLineBottom(i) - first.layout.getLineTop(i);
            float top = originY + first.layout.getLineTop(i) + h * .1f;
            float bottom = originY + first.layout.getLineBottom(i) - h * .1f;
            for (float sample : new float[]{top, (top + bottom) / 2, bottom}) {
                for (int d = -2; d <= 2; d++) {
                    int y = Math.round(sample) + d;
                    if (y >= 0 && y < sampled.length) sampled[y] = true;
                }
            }
        }
        int spikeY = -1, spikeEnd = -1;
        for (int y = rect.top + 12; y < rect.bottom - 12 && spikeY < 0; y++) {
            if (sampled[y] || sampled[y + 1]) continue;
            for (int x = rect.centerX() - 15; x < rect.right - 30; x++) {
                if (Color.alpha(initialInk.getPixel(x, y)) > 128) {
                    spikeY = y; spikeEnd = x + 3; break;
                }
            }
        }
        assertTrue("The synthetic fixture must actually intersect glyph ink", spikeY > 0);
        Paint cut = new Paint(); cut.setColor(Color.GRAY);
        new Canvas(mask).drawRect(rect.left, spikeY, spikeEnd, spikeY + 2, cut);
        assertNotNull(ManhwaRenderer.computeInteriorSpans(mask, s, Collections.singletonList(s)));
        StyledTextRenderer.FitResult fit = StyledTextRenderer.createFit(s, FONT, false);
        assertNotNull("Shrink/reflow; do not just clip away the intersecting letters", fit);
        assertEquals(s.ckb, fit.layout.getText().toString());
        Bitmap raster = ink(s, fit);
        assertEquals("Every painted glyph pixel, not just three sampled Y values, must fit",
                0, outside(raster, mask));
        initialInk.recycle(); raster.recycle(); mask.recycle();
    }
}
