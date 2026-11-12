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
    }
    @Test public void subsetOnTheLastRowMustAlsoBeRemoved()throws Exception {
        List<Object> selected=merge(candidate(LINES,140,0,.93f),candidate(new String[]{LINES[3]},278,4,.9f));
        assertEquals("Do not limit contained-line deduplication to the paragraph centre",1,selected.size());assertEquals(4,segment(selected.get(0)).lines);
    }
    @Test public void scoreOrderCannotDiscardTheRestOfTheParagraph()throws Exception {
        List<Object> selected=merge(candidate(new String[]{LINES[0]},140,3,.99f),candidate(LINES,140,0,.60f));
        assertEquals(1,selected.size());Segment s=segment(selected.get(0));assertEquals(4,s.lines);for(String line:LINES)assertTrue(s.text.contains(line));
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
    @Test public void automaticDarkPanelRendersOneCompleteJoinedRunAfterNormalization()throws Exception {
        List<Object> selected=merge(candidate(LINES,140,0,.94f),candidate(new String[]{LINES[0]},140,4,.92f));assertEquals(1,selected.size());
        Bitmap page=Bitmap.createBitmap(600,440,Bitmap.Config.ARGB_8888);page.eraseColor(Color.rgb(65,75,120));Canvas c=new Canvas(page);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.BLACK);c.drawRect(32,40,568,400,p);p.setColor(Color.WHITE);p.setTextSize(25);
        for(int i=0;i<LINES.length;i++)c.drawText(LINES[i],75,169+i*46,p);
        Segment s=segment(selected.get(0));s.ckb=TARGET;
        ManhwaRenderer.RenderResult rendered=ManhwaRenderer.render(page,new ArrayList<>(Arrays.asList(s)),new TextStyleSettings(),style->Typeface.create(Typeface.SANS_SERIF,Typeface.BOLD));
        assertEquals(1,rendered.segments.size());assertEquals(TARGET,s.ckb);assertEquals(1,rendered.rendered);assertEquals(Color.WHITE,s.detectedInkColor);
        for(int y=0;y<page.getHeight();y++)for(int x=0;x<page.getWidth();x++)if(x<32||x>=568||y<40||y>=400)assertEquals("Surrounding artwork retained",page.getPixel(x,y),rendered.bitmap.getPixel(x,y));
        java.io.File dir=new java.io.File(System.getProperty("koorix.panelFixtures","build/outputs/panel-tests"));assertTrue(dir.isDirectory()||dir.mkdirs());
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,"dark-panel-ckb-sdk"+android.os.Build.VERSION.SDK_INT+".png"))){assertTrue(rendered.bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}
        page.recycle();rendered.bitmap.recycle();
    }
}
