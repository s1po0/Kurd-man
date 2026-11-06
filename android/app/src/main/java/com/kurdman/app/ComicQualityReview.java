package com.kurdman.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Project adapter: local review declarations, NEVER authenticated accuracy. */
final class ComicQualityReview {
    static final String UNREVIEWED="unreviewed", CHECKED="checked", REVISE="revise", UNCERTAIN="uncertain";
    static final Set<String> DECISIONS=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(UNREVIEWED,CHECKED,REVISE,UNCERTAIN)));
    private ComicQualityReview(){ }

    static final class Item {
        final int page,segment;
        final String codes,decision;
        Item(int p,int s,String codes,String decision){this.page=p;this.segment=s;this.codes=codes;this.decision=decision;}
    }
    static final class Scan {
        final List<List<Item>> pages=new ArrayList<>();
        int segments,hints,checked,unreviewed;
    }

    static Scan scan(TranslationProject project) throws ComicTranslationSkill.GlossaryException {
        ComicQualityChecks checker=new ComicQualityChecks(project.comicConfig);
        Scan scan=new Scan();
        for(int pi=0;pi<project.pages.size();pi++){
            List<Item> pageItems=new ArrayList<>();scan.pages.add(pageItems);
            for(int si=0;si<project.pages.get(pi).segments.size();si++){
                if(Thread.currentThread().isInterrupted())throw new CancellationException();
                Segment segment=project.pages.get(pi).segments.get(si);
                if(blank(segment.text)&&blank(segment.ckb))continue;
                String codes=codes(checker,project,segment);
                String decision=decision(project,pi,si);
                pageItems.add(new Item(pi,si,codes,decision));scan.segments++;
                if(!codes.isEmpty())scan.hints++;
                if(CHECKED.equals(decision))scan.checked++;
                if(UNREVIEWED.equals(decision))scan.unreviewed++;
            }
        }
        return scan;
    }

    static String codes(TranslationProject project,int page,int segment){
        try{return codes(new ComicQualityChecks(project.comicConfig),project,project.pages.get(page).segments.get(segment));}
        catch(ComicTranslationSkill.GlossaryException invalid){return "glossary_invalid";}
    }
    private static String codes(ComicQualityChecks checker,TranslationProject project,Segment segment){
        String current=checker.review(segment.text,segment.ckb,project.sourceLanguage,project.targetLanguage);
        // A fresh manual edit should not retain old backend-fallback warnings.
        if(!"manual".equals(segment.translationMethod)&&segment.translationWarnings!=null){
            for(String previous:segment.translationWarnings.split(",")){
                if(previous.equals("untranslated"))current=ComicQualityChecks.merge(current,previous);
                if(previous.equals("glossary_unconfirmed")&&current.contains("glossary_missing"))current=ComicQualityChecks.merge(current,previous);
            }
        }
        if(segment.ocrConfidence>=0f&&segment.ocrConfidence<0.78f)current=ComicQualityChecks.merge(current,"ocr_low");
        return current;
    }

    static String binding(TranslationProject p,int page,int index){
        TranslationProject.Page current=p.pages.get(page);
        Segment item=current.segments.get(index);
        Segment before=index>0?current.segments.get(index-1):null;
        Segment after=index+1<current.segments.size()?current.segments.get(index+1):null;
        return ComicQualityChecks.binding("koorix.reader-review.v1",Long.toString(p.createdAt),
                Integer.toString(page),Integer.toString(index),p.sourceLanguage,p.targetLanguage,
                Boolean.toString(p.comicConfig.enabled),p.comicConfig.genre.name(),p.comicConfig.glossary,
                item.text,item.ckb,Boolean.toString(item.outsideText),
                before==null?null:before.text,before==null?null:before.ckb,
                after==null?null:after.text,after==null?null:after.ckb);
    }
    static String decision(TranslationProject p,int page,int segment){
        Segment item=p.pages.get(page).segments.get(segment);
        if(!DECISIONS.contains(item.readerReview)||!binding(p,page,segment).equals(item.readerReviewBinding))return UNREVIEWED;
        return item.readerReview;
    }
    static void record(TranslationProject p,int page,int segment,String decision){
        if(!DECISIONS.contains(decision))throw new IllegalArgumentException("Invalid review decision");
        if(CHECKED.equals(decision)&&ComicQualityChecks.blocksReview(codes(p,page,segment)))throw new IllegalArgumentException("Incomplete target");
        Segment item=p.pages.get(page).segments.get(segment);
        item.readerReview=decision;
        item.readerReviewBinding=UNREVIEWED.equals(decision)?"":binding(p,page,segment);
    }
    static int remember(TranslationProject project,String original,String preferred,boolean applyExact)
            throws ComicTranslationSkill.GlossaryException {
        String updated=ComicQualityChecks.upsertGlossary(project.comicConfig.glossary,original,preferred);
        project.comicConfig=new ComicTranslationSkill.Config(project.comicConfig.enabled,project.comicConfig.genre,updated);
        int changed=0;
        if(applyExact)for(TranslationProject.Page page:project.pages)for(Segment item:page.segments){
            // Only an identical WHOLE source. Never rewrite an ambiguous substring
            // or guess which Kurdish spelling in a sentence belongs to a name.
            if(ComicTranslationSkill.clean(item.text).equals(ComicTranslationSkill.clean(original))
                    &&!ComicTranslationSkill.clean(item.ckb).equals(ComicTranslationSkill.clean(preferred))){
                item.ckb=ComicTranslationSkill.clean(preferred);edited(item);changed++;
            }
        }
        return changed;
    }
    static void edited(Segment segment){
        segment.translationMethod="manual";
        segment.translationWarnings="";
        segment.readerReview=UNREVIEWED;
        segment.readerReviewBinding="";
    }
    private static boolean blank(String value){return value==null||value.trim().isEmpty();}
}
