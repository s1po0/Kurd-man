package com.kurdman.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Synthetic actual-code contracts, NOT a semantic accuracy benchmark. */
public final class ComicQualityChecksTest {
    private static int assertions;
    private static void check(boolean good, String label) {
        assertions++;
        if (!good) throw new AssertionError(label);
    }
    private static ComicQualityChecks checker(String glossary) throws Exception {
        return new ComicQualityChecks(new ComicTranslationSkill.Config(true, ComicTranslationSkill.Genre.AUTO, glossary));
    }
    private static void has(String codes, String code) { check(Arrays.asList(codes.split(",")).contains(code), code); }
    private static void not(String codes, String code) { check(!Arrays.asList(codes.split(",")).contains(code), "unexpected " + code); }
    public static void main(String[] args) throws Exception {
        ComicQualityChecks c = checker("");
        has(c.review("HP: 25/100", "خاڵ: 100/25", "en", "ckb"), "number_order");
        has(c.review("30 points", "20 خاڵ", "en", "ckb"), "numbers");
        has(c.review("+5", "-5", "en", "ckb"), "numbers");
        has(c.review("5 5", "5", "en", "ckb"), "numbers");
        has(c.review("10%", "10", "en", "ckb"), "numbers");
        check(c.review("1,250 and +5 and 0.5 and 10 %", "١٬٢٥٠ و +٥ و ٠٫٥ و ١٠٪", "en", "ckb").isEmpty(), "Unicode digits and percent spacing");
        check(c.review("HP: 25/100", "خاڵ: ٢٥/١٠٠", "en", "ckb").isEmpty(), "Arabic ratio digits");
        has(c.review("Hello", "", "en", "ckb"), "empty_target");
        has(c.review("", "invented", "en", "ckb"), "source_empty");
        check(c.review("", "", "en", "ckb").isEmpty(), "empty source alignment");
        for (String marker : List.of("ZXQK001", "zxqk", "[[KX0000]]", "ＺＸＱＫ001", "ZXQK001", "［［ＫＸ0000］］")) {
            String codes=c.review("Text",marker,"en","ckb");has(codes,"markers");check(ComicQualityChecks.blocksReview(codes),"marker cannot be checked as complete");
        }
        check(ComicQualityChecks.blocksReview("empty_target"),"empty target cannot be checked");
        check(!ComicQualityChecks.blocksReview("number_order"),"numeric hint may be reader-adjudicated, not semantic verdict");
        has(c.review("We should leave.", "We should leave.", "en", "ckb"),"untranslated");
        has(c.review("Hello", "Hello", "en", "ckb"),"target_script");
        check(c.review("Hello", "Hello", "en", "en").isEmpty(),"same-language is not mistranslation");
        not(c.review("Hello", "Bonjour", "en", "fr"),"target_script");
        has(c.review("Wait", "نا نا نا نا", "en", "ckb"),"repetition");
        not(c.review("ha ha ha ha", "ها ها ها ها", "en", "ckb"),"repetition");
        c=checker("monarch = شا\nShadow Monarch = پاشای سێبەرەکان\nraid = هێرش");
        not(c.review("The Shadow Monarch is here.", "پاشای سێبەرەکان لێرەیە.","en","ckb"),"glossary_missing");
        not(c.review("Her braid is long.","قژی درێژە.","en","ckb"),"glossary_missing");
        has(c.review("A raid.","شەڕێک.","en","ckb"),"glossary_missing");
        c=checker("Jinwoo = جین‌وو");
        has(c.review("Jinwoo, Jinwoo!","جین‌وو!","en","ckb"),"glossary_missing");
        not(c.review("Jinwoo, Jinwoo!","جین‌وو، جین‌وو!","en","ckb"),"glossary_missing");
        c=checker("진우 = جین‌وو");
        not(c.review("진우는", "جین‌وو", "ko", "ckb"),"glossary_missing");
        c=checker("Jinwoo = Jin-Woo");
        check(c.review("Jinwoo!", "Jin-Woo!", "en", "ckb").isEmpty(),"reader-approved Latin name with punctuation");
        String merged=ComicQualityChecks.merge("numbers,repetition", "numbers,target_script");
        check(merged.equals("numbers,repetition,target_script"),"deduplicate fixed codes in insertion order");
        String original="# story\nJinwoo = جینوو\nraid = هێرش\n";
        String revised=ComicQualityChecks.upsertGlossary(original,"Jinwoo","جین‌وو");
        check(revised.startsWith("# story\n"),"comments retained");
        check(ComicTranslationSkill.validateGlossary(revised)==2,"upsert not duplicate");
        check(revised.contains("Jinwoo = جین‌وو"),"explicit replacement");
        revised=ComicQualityChecks.upsertGlossary(revised,"Gate","دەروازە");
        check(ComicTranslationSkill.validateGlossary(revised)==3,"append entry");
        for (String name : List.of("a=b", "a\nb", "", "ZXQK")) {
            boolean refused=false;
            try { ComicQualityChecks.upsertGlossary("",name,"ناو"); } catch (ComicTranslationSkill.GlossaryException good) { refused=true; }
            check(refused,"invalid original refused");
        }
        StringBuilder full=new StringBuilder();
        for(int i=0;i<100;i++)full.append("n").append(i).append("=ناو\n");
        check(ComicTranslationSkill.validateGlossary(ComicQualityChecks.upsertGlossary(full.toString(),"n5","نوێ"))==100,"replace at full capacity");
        boolean refused=false;
        try { ComicQualityChecks.upsertGlossary(full.toString(),"extra","نوێ"); } catch(ComicTranslationSkill.GlossaryException good){refused=true;}
        check(refused,"101st entry refused");
        String a=ComicQualityChecks.binding("ab","c"), b=ComicQualityChecks.binding("a","bc");
        check(!a.equals(b),"length-prefixed hash prevents concatenation ambiguity");
        check(a.equals(ComicQualityChecks.binding("ab","c")),"stable exact candidate binding");
        check(a.matches("[0-9a-f]{64}"),"private SHA256 binding");
        System.out.println("In-app skill: "+assertions+" offline quality/glossary/binding assertions passed; NOT semantic accuracy.");
    }
}
