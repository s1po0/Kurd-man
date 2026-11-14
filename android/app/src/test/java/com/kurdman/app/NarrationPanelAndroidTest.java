package com.kurdman.app;

import static org.junit.Assert.*;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Synthetic equivalent of a dark narration panel; no reader page/dialogue is published. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,34},application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NarrationPanelAndroidTest {
    private static final String[] LINES={"A traveler carries a small lantern.","The road beyond is quiet tonight.","Three distant towers mark the path.","The final gate remains closed."};
    private static final String TARGET="گەشتیارەکە چرایەکی بچووک هەڵدەگرێت. ڕێگاکە ئەمشەو ئارامە. سێ تاوەری دوور ڕێگاکە دیاری دەکەن. دەروازەکە هێشتا داخراوە.";
    private Class<?> type(String name)throws Exception{return Class.forName("com.kurdman.app.ManhwaRenderer$"+name);}
    private Object candidate(String[] text,int firstRow,int pass,float confidence)throws Exception {
        Class<?> c=type("OcrCandidate"),l=type("OcrLineData");
        Rect box=new Rect(75,firstRow,525,firstRow+text.length*46-12);
        Segment s=new Segment(String.join("\n",text),box,text.length);
        Constructor<?> make=c.getDeclaredConstructor(Segment.class,int.class);make.setAccessible(true);Object result=make.newInstance(s,pass);
        Field lines=c.getDeclaredField("lines");lines.setAccessible(true);
        @SuppressWarnings("unchecked")List<Object> rows=(List<Object>)lines.get(result);
        Constructor<?> line=l.getDeclaredConstructor(String.class,Rect.class,float.class);line.setAccessible(true);
        for(int i=0;i<text.length;i++)rows.add(line.newInstance(text[i],new Rect(75,firstRow+i*46,525,firstRow+i*46+34),confidence));
        Method refresh=ManhwaRenderer.class.getDeclaredMethod("refreshCandidate",c,boolean.class);refresh.setAccessible(true);refresh.invoke(null,result,false);return result;
    }
    @SuppressWarnings("unchecked")private List<Object> merge(Object... input)throws Exception {
        Method m=ManhwaRenderer.class.getDeclaredMethod("mergeCandidates",List.class);m.setAccessible(true);return (List<Object>)m.invoke(null,new ArrayList<>(Arrays.asList(input)));
    }
    private Segment segment(Object c)throws Exception{Field f=c.getClass().getDeclaredField("segment");f.setAccessible(true);return (Segment)f.get(c);}
    @Test public void aFirstLineFromAnInvertedPassMustNotBecomeASecondTranslatedBlock()throws Exception {
        List<Object> selected=merge(candidate(LINES,140,0,.94f),candidate(new String[]{LINES[0]},140,4,.92f));
        assertEquals("A fully covered source line must not survive as duplicate lettering on its paragraph",1,selected.size());
        Segment s=segment(selected.get(0));for(String line:LINES)assertTrue(s.text.contains(line));assertEquals(4,s.lines);
        Field flag=selected.get(0).getClass().getDeclaredField("disagreement");flag.setAccessible(true);
        assertFalse("A covered equal reading is not an OCR disagreement",flag.getBoolean(selected.get(0)));
    }
    @Test public void subsetOnTheLastRowMustAlsoBeRemoved()throws Exception {
        List<Object> selected=merge(candidate(LINES,140,0,.93f),candidate(new String[]{LINES[3]},278,4,.9f));
        assertEquals("Do not limit contained-line deduplication to the paragraph centre",1,selected.size());assertEquals(4,segment(selected.get(0)).lines);
    }
    @Test public void scoreOrderCannotDiscardTheRestOfTheParagraph()throws Exception {
        List<Object> selected=merge(candidate(new String[]{LINES[0]},140,3,.99f),candidate(LINES,140,0,.60f));
        assertEquals(1,selected.size());Segment s=segment(selected.get(0));assertEquals(4,s.lines);for(String line:LINES)assertTrue(s.text.contains(line));
    }
    @Test public void aHigherConfidenceFragmentCannotLoseTheRestOfItsSourceRow()throws Exception {
        List<Object> selected=merge(candidate(new String[]{"carries a small lantern."},140,3,.99f),candidate(LINES,140,0,.60f));
        assertEquals(1,selected.size());Segment source=segment(selected.get(0));
        for(String line:LINES)assertTrue("A covered fragment is not permission to drop words: "+line,source.text.contains(line));
        assertEquals(4,source.lines);
    }
    @Test public void aFragmentRereadCannotOverwriteTheFullRowOfASelectedParagraph()throws Exception {
        Object full=candidate(LINES,140,0,.60f),fragment=candidate(new String[]{"carries a small lantern."},140,3,.99f);
        // Candidate rank can reflect other passes/rows; line confidence still
        // differs. Exercise the opposite merge order explicitly.
        Field score=full.getClass().getDeclaredField("score");score.setAccessible(true);score.setDouble(full,200d);
        List<Object> selected=merge(full,fragment);assertEquals(1,selected.size());
        for(String line:LINES)assertTrue("A high-confidence partial row cannot replace its complete reading",segment(selected.get(0)).text.contains(line));
    }
    @Test public void genuinelyRepeatedTextOnAnotherRowRemainsIndependent()throws Exception {
        List<Object> selected=merge(candidate(LINES,140,0,.94f),candidate(new String[]{LINES[0]},390,4,.92f));assertEquals(2,selected.size());
    }
    @Test public void anUnmatchedContainedReadingIsNotSilentlyDeleted()throws Exception {
        List<Object> selected=merge(candidate(LINES,140,0,.94f),candidate(new String[]{"A second sign gives different directions."},140,4,.92f));assertEquals(2,selected.size());
    }
    @Test public void sameWordsInAnotherColumnAreNotDuplicateReadings()throws Exception {
        Object a=candidate(LINES,140,0,.94f),b=candidate(new String[]{LINES[0]},140,4,.92f);
        Segment shifted=segment(b);shifted.box.offset(500,0);
        Field f=b.getClass().getDeclaredField("lines");f.setAccessible(true);
        for(Object line:(List<?>)f.get(b)){Field box=line.getClass().getDeclaredField("box");box.setAccessible(true);((Rect)box.get(line)).offset(500,0);}
        assertEquals(2,merge(a,b).size());
    }
    @Test public void standaloneNumberMustNeverBeRepaintedAsAChangedNumber() {
        for(String text:new String[]{"18","48","٠٥/٢٠١٤","-12.5%"})assertTrue(ManhwaRenderer.isStandaloneNumericText(text));
        for(String text:new String[]{"", "...", "Level 48", "1st", "2014 story"})assertFalse(ManhwaRenderer.isStandaloneNumericText(text));
        Bitmap original=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);original.eraseColor(Color.rgb(35,75,52));
        Canvas canvas=new Canvas(original);Paint paint=new Paint();paint.setColor(Color.WHITE);paint.setTextSize(22);canvas.drawText("18",30,50,paint);
        Segment number=new Segment("18",new Rect(30,28,60,52),1);number.ckb="48";
        ManhwaRenderer.RenderResult result=ManhwaRenderer.render(original,Arrays.asList(number));
        assertFalse(number.rendered);assertFalse(number.ensureStyle().visible);
        assertEquals("Keep the OCR number in the editable project", "18",result.segments.get(0).text);
        for(int y=0;y<100;y++)for(int x=0;x<100;x++)assertEquals("Original artwork must stay untouched",original.getPixel(x,y),result.bitmap.getPixel(x,y));
        original.recycle();result.bitmap.recycle();
    }
    @Test public void explicitCreditsAndAddressesRemainOriginalInsteadOfBeingTranslated() {
        assertTrue(ManhwaRenderer.isAddressOrCredit("WWW.EXAMPLE.COM 05/2014"));
        assertTrue(ManhwaRenderer.isAddressOrCredit("https://example.org"));
        assertTrue(ManhwaRenderer.isAddressOrCredit("artist@example.org"));
        assertFalse(ManhwaRenderer.isAddressOrCredit("The year is 2014."));
        assertFalse(ManhwaRenderer.isAddressOrCredit("Come here!"));
    }
    @Test public void automaticDarkPanelRendersOneCompleteJoinedRunAfterNormalization()throws Exception {
        List<Object> selected=merge(candidate(LINES,140,0,.94f),candidate(new String[]{LINES[0]},140,4,.92f));assertEquals(1,selected.size());
        Bitmap page=Bitmap.createBitmap(600,440,Bitmap.Config.ARGB_8888);page.eraseColor(Color.rgb(65,75,120));Canvas c=new Canvas(page);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.BLACK);c.drawRect(32,40,568,400,p);p.setColor(Color.WHITE);p.setTextSize(25);
        for(int i=0;i<LINES.length;i++)c.drawText(LINES[i],75,169+i*46,p);
        Segment s=segment(selected.get(0));s.ckb=TARGET;
        ManhwaRenderer.RenderResult rendered=ManhwaRenderer.render(page,new ArrayList<>(Arrays.asList(s)),new TextStyleSettings(),style->Typeface.create(Typeface.SANS_SERIF,Typeface.BOLD));
        assertEquals(1,rendered.segments.size());assertEquals(TARGET,s.ckb);assertEquals(1,rendered.rendered);assertEquals(Color.WHITE,s.detectedInkColor);
        for(int y=0;y<page.getHeight();y++)for(int x=0;x<page.getWidth();x++)if(x<32||x>=568||y<40||y>=400)assertEquals("Surrounding artwork retained",page.getPixel(x,y),rendered.bitmap.getPixel(x,y));
        java.io.File dir=new java.io.File(System.getProperty("koorix.bubbleFixtures","build/outputs/bubble-tests"));assertTrue(dir.isDirectory()||dir.mkdirs());
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,"dark-panel-ckb-sdk"+android.os.Build.VERSION.SDK_INT+".png"))){assertTrue(rendered.bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}
        page.recycle();rendered.bitmap.recycle();
    }
}
