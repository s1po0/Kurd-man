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

    private static final StyledTextRenderer.TypefaceResolver JOINED_FONT =
            style -> FontCatalog.getTypeface(org.robolectric.RuntimeEnvironment.getApplication(), style);

    private Bitmap shape(String shape) {
        Bitmap b = rectangularMask(new Rect());
        Paint p = new Paint(); p.setColor(Color.WHITE);
        Canvas c = new Canvas(b);
        android.graphics.Path path = new android.graphics.Path();
        if (shape.equals("circle")) c.drawCircle(240, 170, 138, p);
        else if (shape.equals("rectangle")) c.drawRect(40, 38, 440, 302, p);
        else {
            if (shape.equals("triangle")) {
                path.moveTo(240, 32); path.lineTo(442, 302); path.lineTo(38, 302);
            } else {
                path.moveTo(72, 62); path.lineTo(400, 28);
                path.lineTo(442, 274); path.lineTo(32, 242);
            }
            path.close(); c.drawPath(path, p);
        }
        return b;
    }

    private Segment trace(Bitmap source, String text) {
        Segment s = segment(new Rect(24, 24, 456, 316), text);
        s.box = new Rect(140, 120, 340, 225);
        s.sourceLineHeight = 42;
        Rect body = ManhwaRenderer.computeInteriorSpans(source, s, Collections.singletonList(s));
        assertNotNull("Synthetic shape must have an actual traced component", s.bubbleBoundary);
        assertFalse(s.bubbleBoundary.isEmpty());
        if (body != null) s.renderBox = body;
        return s;
    }

    private void fixture(String name, Bitmap paper, Bitmap raster) throws Exception {
        java.io.File dir = new java.io.File(System.getProperty("koorix.bubbleFixtures",
                "build/outputs/bubble-tests"));
        assertTrue(dir.isDirectory() || dir.mkdirs());
        Bitmap visible = paper.copy(Bitmap.Config.ARGB_8888, true);
        new Canvas(visible).drawBitmap(raster, 0, 0, null);
        java.io.File file = new java.io.File(dir, name + "-sdk"
                + android.os.Build.VERSION.SDK_INT + ".png");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            assertTrue(visible.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        visible.recycle();
    }

    @Test public void completeJoinedSoraniAndLatinFitEveryRequestedShapeWithoutClipping() throws Exception {
        for (String shape : new String[]{"circle", "rectangle", "triangle", "quadrilateral"}) {
            for (String language : new String[]{"ckb", "en"}) {
                Bitmap paper = shape(shape);
                String text = language.equals("ckb")
                        ? "هێشتا کاتی ئەوە نەهاتووە؛ لێرە چاوەڕێ بکە."
                        : "Do not leave. Stay here and wait for us.";
                Segment s = trace(paper, text);
                s.ensureStyle().outline = true;
                s.ensureStyle().outlineWidthRatio = .06f;
                s.ensureStyle().outlineColor = Color.BLACK;
                StyledTextRenderer.FitResult fit = StyledTextRenderer.createFit(s, JOINED_FONT, false);
                assertNotNull(shape + ": complete " + language + " should fit", fit);
                assertEquals(text, fit.layout.getText().toString());
                assertFalse(fit.overflow);
                Bitmap raster = ink(s, fit);
                fixture(shape + "-" + language, paper, raster);
                assertEquals(shape + ": raw glyph ink must not leave the actual traced shape", 0,
                        outside(raster, paper));
                raster.recycle(); paper.recycle();
            }
        }
    }

    @Test public void concaveRunsKeepTheGapAndAdaptivePlacementFindsTheLowerChamber() throws Exception {
        Bitmap paper = rectangularMask(new Rect(24, 24, 456, 316));
        Paint cut = new Paint(); cut.setColor(Color.GRAY);
        new Canvas(paper).drawRect(184, 24, 296, 200, cut);
        Segment s = trace(paper, "لێرە چاوەڕێ بکە، من دەگەڕێمەوە.");
        // Deliberately retain the full editing box, whose centre is in the notch.
        s.renderBox = new Rect(24, 24, 456, 316);
        assertFalse(s.bubbleBoundary.contains(240, 120));
        assertTrue(s.bubbleBoundary.contains(100, 120));
        assertTrue(s.bubbleBoundary.contains(360, 120));
        assertTrue(s.interiorLeft[120 - s.interiorTop] < 240
                && s.interiorRight[120 - s.interiorTop] > 240);
        Rect anchor = s.bubbleBoundary.inset(3).placementAnchor();
        assertTrue("Do not anchor text in the concave gap", anchor.top >= 200);
        StyledTextRenderer.FitResult fit = StyledTextRenderer.createFit(s, JOINED_FONT, false);
        assertNotNull("Find the wide lower chamber rather than losing the dialogue", fit);
        assertEquals(s.ckb, fit.layout.getText().toString());
        Bitmap raster = ink(s, fit);
        fixture("concave-ckb", paper, raster);
        assertEquals(0, outside(raster, paper));
        raster.recycle(); paper.recycle();
    }

    @Test public void glyphHolesAreFilledButASeparateArtHoleRemainsProtected() {
        Bitmap paper = rectangularMask(new Rect(24, 24, 456, 316));
        Paint p = new Paint(); p.setColor(Color.BLACK);
        Canvas c = new Canvas(paper);
        c.drawRect(220, 150, 230, 175, p); // within the declared OCR block
        c.drawRect(80, 60, 105, 85, p); // artwork, outside that block
        Segment s = trace(paper, "Stay here.");
        assertTrue("Source glyphs must not become permanent holes in the text area",
                s.bubbleBoundary.contains(224, 160));
        assertFalse("Do not bridge a protected art hole using row min/max",
                s.bubbleBoundary.contains(90, 70));
        assertFalse(s.bubbleBoundary.inset(3).contains(106, 70));
        paper.recycle();
    }

    @Test public void immutableMasksSurviveDuplicateSaveAndReopen() throws Exception {
        Bitmap paper = shape("quadrilateral");
        Segment s = trace(paper, "ئاگادار بە، لێرە چاوەڕێمان بکە.");
        s.ensureStyle().rotationDegrees = 7;
        assertSame("Sharing an immutable mask does not lose contour data", s.bubbleBoundary,
                s.duplicate().bubbleBoundary);
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        TranslationProject project = TranslationProjectStore.createNew(app, false,
                "Synthetic contour", "Synthetic contour", "en");
        TranslationProject.Page page = new TranslationProject.Page("pages/0001.png", ".png");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(page.sourceFile(project.projectDir))) {
            assertTrue(paper.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        page.segments.add(s); project.pages.add(page); project.save();
        Segment reopened = TranslationProject.load(project.projectDir).pages.get(0).segments.get(0);
        assertNotNull(reopened.bubbleBoundary);
        assertEquals(s.bubbleBoundary.signature(), reopened.bubbleBoundary.signature());
        assertEquals(s.ckb, reopened.ckb);
        assertEquals(s.renderBox, reopened.renderBox);
        assertEquals(7, reopened.ensureStyle().rotationDegrees, .001f);
        StyledTextRenderer.FitResult fit = StyledTextRenderer.createFit(reopened, JOINED_FONT, true);
        assertNotNull(fit);
        Bitmap raster = ink(reopened, fit);
        assertEquals(0, outside(raster, paper));
        raster.recycle(); paper.recycle();
    }

    @Test public void malformedOrOversizedStoredMaskMustFailClosed() throws Exception {
        BubbleBoundary valid = BubbleBoundary.rectangle(new Rect(20, 20, 80, 70));
        for (String key : new String[]{"w", "h", "v"}) {
            org.json.JSONObject invalid = new org.json.JSONObject(valid.toJson().toString());
            invalid.put(key, Integer.MAX_VALUE);
            assertTrue(BubbleBoundary.fromJson(invalid).isEmpty());
        }
        org.json.JSONObject invalid = new org.json.JSONObject(valid.toJson().toString());
        invalid.getJSONArray("runs").put(0, new org.json.JSONArray("[15,10]"));
        assertTrue(BubbleBoundary.fromJson(invalid).isEmpty());
        invalid = new org.json.JSONObject(valid.toJson().toString());
        invalid.getJSONArray("runs").put(0, new org.json.JSONArray("[0,25,12,40]"));
        assertTrue(BubbleBoundary.fromJson(invalid).isEmpty());
        assertTrue(BubbleBoundary.fromJson(null).isEmpty());
        Segment s = segment(new Rect(20, 20, 400, 250), "Keep the source.");
        s.bubbleBoundary = BubbleBoundary.fromJson(invalid);
        assertNull("Never replace malformed contour data with an unprotected rectangle",
                StyledTextRenderer.createFit(s, FONT, true));
    }

    @Test public void everyPositionRotationOutlineAndGradientEditInvalidatesTheFit() {
        Segment s = segment(new Rect(38, 42, 438, 280), "Stay here and wait.");
        StyledTextRenderer.FitResult fit = StyledTextRenderer.createFit(s, FONT, true);
        assertNotNull(fit);
        String key = StyledTextRenderer.fitSignature(s);
        s.renderBox.offset(1, 0);
        assertNotEquals(key, StyledTextRenderer.fitSignature(s));
        Bitmap target = Bitmap.createBitmap(480, 340, Bitmap.Config.ARGB_8888);
        assertFalse("A stale fit must not be drawn after moving a same-size box",
                StyledTextRenderer.drawText(new Canvas(target), s, fit));
        key = StyledTextRenderer.fitSignature(s);
        s.ensureStyle().rotationDegrees = 20;
        assertNotEquals(key, StyledTextRenderer.fitSignature(s));
        key = StyledTextRenderer.fitSignature(s);
        s.ensureStyle().outline = true; s.ensureStyle().outlineWidthRatio = .2f;
        assertNotEquals(key, StyledTextRenderer.fitSignature(s));
        key = StyledTextRenderer.fitSignature(s);
        s.ensureStyle().gradient = true; s.ensureStyle().gradientDirection = 2;
        assertNotEquals(key, StyledTextRenderer.fitSignature(s));
        target.recycle();
    }

    @Test public void studioDoesNotEraseARejectedReplacementEvenWithCachedCleanPatches() {
        Bitmap source = rectangularMask(new Rect(24, 24, 440, 300));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.BLACK); p.setTextSize(32);
        new Canvas(source).drawText("ORIGINAL SOURCE", 55, 105, p);
        Segment s = segment(new Rect(45, 65, 365, 115), "Complete dialogue. ".repeat(100));
        s.renderBox = new Rect(400, 220, 414, 232);
        PageEditorView view = new PageEditorView(org.robolectric.RuntimeEnvironment.getApplication(), null);
        view.layout(0, 0, 480, 340);
        view.setResolver(FONT);
        view.setShowAllOverlays(false);
        view.setPage(source, Collections.singletonList(s), -1);
        try {
            java.lang.reflect.Field selected = PageEditorView.class.getDeclaredField("selectedIndex");
            selected.setAccessible(true); selected.setInt(view, -1); // hide UI handles for pixel comparison
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        Bitmap originalView = Bitmap.createBitmap(480, 340, Bitmap.Config.ARGB_8888);
        view.setPeekOriginal(true); view.draw(new Canvas(originalView));
        Bitmap editedView = Bitmap.createBitmap(480, 340, Bitmap.Config.ARGB_8888);
        view.setPeekOriginal(false); view.draw(new Canvas(editedView));
        assertTrue("Studio must retain source pixels until a complete replacement fits",
                originalView.sameAs(editedView));
        originalView.recycle(); editedView.recycle(); source.recycle();
    }

    @Test public void interruptionCannotTurnARejectedFitIntoErasure() {
        Bitmap source = rectangularMask(new Rect(24, 24, 440, 300));
        Segment s = trace(source, "لێرە چاوەڕێ بکە.");
        Thread.currentThread().interrupt();
        try {
            StyledTextRenderer.EditedResult result = StyledTextRenderer.renderEdited(
                    source, Collections.singletonList(s), JOINED_FONT);
            assertEquals(0, result.rendered);
            assertTrue(source.sameAs(result.bitmap));
            result.bitmap.recycle();
        } finally { Thread.interrupted(); source.recycle(); }
    }

    @Test public void customRotatedBackgroundAndBorderCannotPaintOutsideATriangle() {
        Bitmap paper = shape("triangle");
        Segment s = trace(paper, "لێرە بمێنەوە.");
        s.ensureStyle().rotationDegrees = 29;
        s.ensureStyle().backgroundMode = TextStyleSettings.BG_CUSTOM;
        s.ensureStyle().backgroundColor = Color.MAGENTA;
        s.ensureStyle().backgroundBorder = true;
        s.ensureStyle().backgroundBorderWidth = 12;
        Bitmap fill = Bitmap.createBitmap(480, 340, Bitmap.Config.ARGB_8888);
        StyledTextRenderer.drawBackground(new Canvas(fill), s);
        assertEquals("Style backgrounds/borders must obey the same physical contour", 0,
                outside(fill, paper));
        fill.recycle(); paper.recycle();
    }


    @Test public void normalAutomaticRendererUsesContourFittingAndDoesNotTouchOutsideArt() {
        for (String shape : new String[]{"circle", "rectangle", "triangle", "quadrilateral"}) {
            Bitmap source = shape(shape), allowed = shape(shape);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.BLACK); p.setTextSize(24);
            Canvas c = new Canvas(source);
            c.drawText("WAIT", 190, 170, p); c.drawText("HERE", 190, 202, p);
            Segment s = new Segment("WAIT HERE", new Rect(186, 145, 294, 208), 2);
            s.sourceLineHeight = 32; s.ckb = "مەڕۆ، لێرە چاوەڕێمان بکە.";
            ManhwaRenderer.RenderResult result = ManhwaRenderer.render(source,
                    Collections.singletonList(s), new TextStyleSettings(), JOINED_FONT);
            assertEquals(shape + ": normal automatic rendering must actually place the dialogue", 1, result.rendered);
            assertTrue(s.rendered);
            assertNotNull(s.bubbleBoundary);
            for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
                if (allowed.getPixel(x, y) != Color.WHITE) assertEquals(
                        shape + ": outside artwork/frame pixels must not change",
                        source.getPixel(x, y), result.bitmap.getPixel(x, y));
            }
            StyledTextRenderer.FitResult fit = StyledTextRenderer.createFit(s, JOINED_FONT, false);
            assertNotNull(fit);
            Bitmap raster = ink(s, fit);
            assertEquals(0, outside(raster, allowed));
            raster.recycle(); source.recycle(); allowed.recycle(); result.bitmap.recycle();
        }
    }

    @Test public void blackNarrationKeepsWhiteJoinedTextAndOutsidePixels() {
        Bitmap allowed = shape("quadrilateral");
        Bitmap source = allowed.copy(Bitmap.Config.ARGB_8888, true);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            if (source.getPixel(x, y) == Color.WHITE) source.setPixel(x, y, Color.BLACK);
        }
        Segment s = segment(new Rect(24, 24, 456, 316), "هێشتا چیرۆکەکە تەواو نەبووە.");
        s.box = new Rect(140, 120, 340, 225);
        s.sourceLineHeight = 42;
        s.detectedBackgroundColor = Color.BLACK; s.detectedInkColor = Color.WHITE;
        Rect body = ManhwaRenderer.computeInteriorSpans(source, s, Collections.singletonList(s));
        assertNotNull(s.bubbleBoundary);
        if (body != null) s.renderBox = body;
        StyledTextRenderer.EditedResult result = StyledTextRenderer.renderEdited(
                source, Collections.singletonList(s), JOINED_FONT);
        assertEquals(1, result.rendered);
        StyledTextRenderer.FitResult fit = StyledTextRenderer.createFit(s, JOINED_FONT, true);
        assertNotNull(fit);
        assertEquals(Color.WHITE, fit.paint.getColor());
        Bitmap raster = ink(s, fit);
        assertEquals(0, outside(raster, allowed));
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            if (allowed.getPixel(x, y) != Color.WHITE)
                assertEquals(source.getPixel(x, y), result.bitmap.getPixel(x, y));
        }
        raster.recycle(); allowed.recycle(); source.recycle(); result.bitmap.recycle();
    }

    @Test public void explicitlyHiddenForceEraseStillWorksWithoutAReplacement() {
        Bitmap source = rectangularMask(new Rect(24, 24, 440, 300));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.BLACK); p.setTextSize(28);
        new Canvas(source).drawText("WAIT HERE", 170, 175, p);
        Segment s = segment(new Rect(155, 140, 340, 190), "");
        s.ensureStyle().visible = false; s.ensureStyle().forceErase = true;
        StyledTextRenderer.EditedResult result = StyledTextRenderer.renderEdited(
                source, Collections.singletonList(s), FONT);
        assertEquals(0, result.rendered);
        assertFalse("Reader-requested hidden-block erasure is not a failed replacement",
                source.sameAs(result.bitmap));
        source.recycle(); result.bitmap.recycle();
    }

}
