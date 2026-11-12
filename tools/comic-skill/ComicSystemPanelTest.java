package com.kurdman.app;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic source text only. Contract/terminology tests, not a general meaning benchmark. */
public final class ComicSystemPanelTest {
    private static int checks;
    private static List<ComicTranslationSkill.Entry> lexicon;
    private static final String TITLE="ASPECT: Stone Keeper";
    private static final String DESCRIPTION="ASPECT DESCRIPTION:";
    private static final String BODY="This rare trait grants two abilities and one skill.";
    private static final String VALUE="پارێزەری بەرد";
    private static final String TARGET="ئەم تایبەتمەندییە دەگمەنە دوو توانا و یەک لێهاتوویی دەبەخشێت.";
    private static ComicTranslationSkill skill(ComicTranslationSkill.Genre genre,String glossary)throws Exception {
        return new ComicTranslationSkill(new ComicTranslationSkill.Config(true,genre,glossary),lexicon);
    }
    private static ComicTranslationSkill.Backend normal(List<String> requests) {
        return (text,source,target)->{requests.add(text);return text.replace("Stone Keeper",VALUE).replace(BODY,TARGET);};
    }
    private static void check(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    private static void equal(Object want,Object got,String message){check(Objects.equals(want,got),message+" expected="+want+" actual="+got);}
    public static void main(String[] args)throws Exception {
        lexicon=ComicTranslationSkill.readLexicon(Files.newBufferedReader(Path.of(args[0]),StandardCharsets.UTF_8));
        for(boolean offline:new boolean[]{true,false}) {
            List<String> sent=new ArrayList<>();
            ComicTranslationSkill.PageResult p=skill(ComicTranslationSkill.Genre.AUTO,"").translatePage(Arrays.asList(TITLE,DESCRIPTION,BODY),"en","ckb",offline,normal(sent),null);
            equal(3,p.results.size(),"One result per original source block");
            equal("تایبەتمەندی: "+VALUE,p.results.get(0).text,"Contextual trait label, not ordinary side");
            equal("وەسفی تایبەتمەندی:",p.results.get(1).text,"Natural local description label");
            equal(TARGET,p.results.get(2).text,"Body still uses selected translator");
            check(sent.stream().noneMatch(s->s.toLowerCase(Locale.ROOT).contains("aspect")),"Field labels are local; no fragile marker/synonym guessing");
            String joined=TITLE+"\n"+DESCRIPTION+"\n"+BODY;
            sent.clear();p=skill(ComicTranslationSkill.Genre.AUTO,"").translatePage(Collections.singletonList(joined),"en","ckb",offline,normal(sent),null);
            equal(1,p.results.size(),"A combined OCR block keeps one result identity");
            equal("تایبەتمەندی: "+VALUE+" وەسفی تایبەتمەندی: "+TARGET,p.results.get(0).text,"Only labels replaced, values fully translated once");
            check(sent.stream().noneMatch(s->s.contains("ASPECT")),"Combined field labels never reach the engine");
        }
        List<String> sent=new ArrayList<>();
        ComicTranslationSkill.PageResult custom=skill(ComicTranslationSkill.Genre.AUTO,"Aspect = سیفەت\nAspect Description = وەسفی سیفەت\nStone Keeper = پارێزگار")
                .translatePage(Arrays.asList(TITLE,DESCRIPTION,BODY),"en","ckb",true,normal(sent),null);
        equal("سیفەت: پارێزگار",custom.results.get(0).text,"Reader term/value spellings override local labels");
        equal("وەسفی سیفەت:",custom.results.get(1).text,"Reader description label preserved");
        for(ComicTranslationSkill.Genre optout:new ComicTranslationSkill.Genre[]{ComicTranslationSkill.Genre.EVERYDAY,ComicTranslationSkill.Genre.MARTIAL}) {
            sent.clear();skill(optout,"").translatePage(Arrays.asList(TITLE,DESCRIPTION,BODY),"en","ckb",true,normal(sent),null);
            check(sent.stream().anyMatch(s->s.contains("ASPECT")),"Explicit non-fantasy genre opts out");
        }
        for(List<String> ordinary:Arrays.asList(
                Collections.singletonList("Consider every aspect of the decision."),
                Collections.singletonList(TITLE),
                Arrays.asList("ASPECT: lighting",DESCRIPTION,"The room faces north."),
                Arrays.asList("The sign reads ASPECT: Stone Keeper",DESCRIPTION,BODY))) {
            sent.clear();ComicTranslationSkill.PageResult p=skill(ComicTranslationSkill.Genre.AUTO,"").translatePage(ordinary,"en","ckb",true,normal(sent),null);
            check(sent.stream().anyMatch(s->s.toLowerCase(Locale.ROOT).contains("aspect")),"No global ambiguous-word replacement");
            check(p.results.stream().noneMatch(r->r.method.equals("comic_system_fields")),"No unjustified system provenance");
        }
        sent.clear();skill(ComicTranslationSkill.Genre.AUTO,"").translatePage(Arrays.asList(TITLE,DESCRIPTION,BODY),"en","ar",true,(s,a,b)->{sent.add(s);return "نص اختباري";},null);
        check(sent.stream().anyMatch(s->s.contains("ASPECT")),"Other target languages are not forced into Sorani");
        sent.clear();new ComicTranslationSkill(new ComicTranslationSkill.Config(false,ComicTranslationSkill.Genre.AUTO,""),lexicon)
                .translatePage(Arrays.asList(TITLE,DESCRIPTION,BODY),"en","ckb",true,normal(sent),null);
        check(sent.stream().anyMatch(s->s.contains("ASPECT")),"Disabled skill leaves ordinary route alone");
        String all=TITLE+"\n"+DESCRIPTION+"\nRare skill.";
        ComicTranslationSkill.PageResult whole=skill(ComicTranslationSkill.Genre.AUTO,all.replace('\n',' ')+" = هەڵبژاردەی خوێنەر")
                .translatePage(Collections.singletonList(all),"en","ckb",true,normal(new ArrayList<>()),null);
        equal("هەڵبژاردەی خوێنەر",whole.results.get(0).text,"Whole-block custom override still wins");
        AtomicInteger calls=new AtomicInteger();
        try {
            skill(ComicTranslationSkill.Genre.AUTO,"").translatePage(Arrays.asList(TITLE,DESCRIPTION,BODY),"en","ckb",true,(s,a,b)->{calls.incrementAndGet();return s;},(d,t)->{throw new InterruptedException("stop");});
            throw new AssertionError("STOP must interrupt local field planning too");
        }catch(InterruptedException expected){check(calls.get()==0,"No backend after STOP");}
        ComicTranslationSkill reused=skill(ComicTranslationSkill.Genre.AUTO,"");
        reused.translatePage(Arrays.asList(TITLE,DESCRIPTION,BODY),"en","ckb",true,normal(new ArrayList<>()),null);
        sent.clear();reused.translatePage(Collections.singletonList("A new aspect of everyday life."),"en","ckb",true,normal(sent),null);
        equal(Collections.singletonList("A new aspect of everyday life."),sent,"Panel context never leaks to a later page");
        System.out.println("Comic system fields: "+checks+" synthetic terminology/boundary assertions passed; NOT a general meaning benchmark.");
    }
}
