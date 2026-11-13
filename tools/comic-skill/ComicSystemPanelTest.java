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
        AtomicInteger completed=new AtomicInteger();
        skill(ComicTranslationSkill.Genre.AUTO,"").translatePage(Arrays.asList(TITLE,DESCRIPTION,BODY),"en","ckb",true,normal(new ArrayList<>()),(done,total)->{
            equal(3,total,"Progress total remains original block count, not field count");
            check(done>=completed.get()&&done<=total,"Structured field progress is monotonic");completed.set(done);
        });
        equal(3,completed.get(),"Structured fields complete original block progress");
        ComicTranslationSkill reused=skill(ComicTranslationSkill.Genre.AUTO,"");
        reused.translatePage(Arrays.asList(TITLE,DESCRIPTION,BODY),"en","ckb",true,normal(new ArrayList<>()),null);
        sent.clear();reused.translatePage(Collections.singletonList("A new aspect of everyday life."),"en","ckb",true,normal(sent),null);
        equal(Collections.singletonList("A new aspect of everyday life."),sent,"Panel context never leaks to a later page");
        keeperRoleContracts();
        System.out.println("Comic system fields: "+checks+" synthetic terminology/boundary assertions passed; NOT a general meaning benchmark.");
    }

    private static void keeperRoleContracts() throws Exception {
        // Real installed-APK run 43344151462 exposed the sports sense of Keeper.
        // Exercise a general guarded role pattern, not a hardcoded full title.
        for(boolean offline:new boolean[]{true,false}) {
            for(String noun:new String[]{"Stone","Gate"}) {
                String body=BODY+" Nearby "+noun.toLowerCase(Locale.ROOT)+"s remain still.";
                List<String> requests=new ArrayList<>();
                ComicTranslationSkill.Backend backend=(text,source,target)->{
                    requests.add(text);
                    return text.replace(body,TARGET).replace(noun+" Keeper","گۆڵپارێزی هەڵە")
                            .replace(noun,noun.equals("Stone")?"بەرد":"دەروازە");
                };
                List<String> input=Arrays.asList("ASPECT: "+noun+" Keeper",DESCRIPTION,body);
                ComicTranslationSkill.PageResult result=skill(ComicTranslationSkill.Genre.AUTO,"")
                        .translatePage(input,"en","ckb",offline,backend,null);
                equal("تایبەتمەندی: پارێزەری "+(noun.equals("Stone")?"بەرد":"دەروازە"),
                        result.results.get(0).text,"Referenced fantasy role is not a football goalkeeper");
                equal(TARGET,result.results.get(2).text,"Entire description still uses the same engine");
                equal(3,result.results.size(),"Role planning does not change source/result identity");
                check(requests.stream().anyMatch(s->s.contains(body)),"Complete original body must reach the selected backend");
                check(requests.stream().noneMatch(s->s.contains(noun+" Keeper")),"Only a justified role head is local; noun still translated");
            }
            String body=BODY+" Nearby stones remain still.";
            String combined=TITLE.toUpperCase(Locale.ROOT)+"\n"+DESCRIPTION+"\n"+body;
            ComicTranslationSkill.PageResult result=skill(ComicTranslationSkill.Genre.AUTO,"")
                    .translatePage(Collections.singletonList(combined),"en","ckb",offline,
                            (text,a,b)->text.replace(body,TARGET).replace("STONE KEEPER","گۆڵپارێزی هەڵە").replace("STONE","بەرد"),null);
            equal(1,result.results.size(),"Uppercase combined OCR block retains one result");
            equal("تایبەتمەندی: پارێزەری بەرد وەسفی تایبەتمەندی: "+TARGET,result.results.get(0).text,
                    "Combined field/body structure supports the same justified role grammar");
        }
        for(String body:new String[]{BODY, BODY+" Nearby stones are used in soccer.",BODY+" Nearby stones cover the goal."}) {
            List<String> requests=new ArrayList<>();
            skill(ComicTranslationSkill.Genre.AUTO,"").translatePage(Arrays.asList(TITLE,DESCRIPTION,body),"en","ckb",true,
                    (text,a,b)->{requests.add(text);return text;},null);
            check(requests.stream().anyMatch(s->s.contains("Stone Keeper")),"Unreferenced/sports role stays with the engine");
        }
        String body=BODY+" Nearby stones remain still.";
        ComicTranslationSkill.PageResult custom=skill(ComicTranslationSkill.Genre.AUTO,"Stone Keeper = ناوی خوێنەر")
                .translatePage(Arrays.asList(TITLE,DESCRIPTION,body),"en","ckb",true,(text,a,b)->text,null);
        equal("تایبەتمەندی: ناوی خوێنەر",custom.results.get(0).text,"Reader whole-title spelling wins over role grammar");
        custom=skill(ComicTranslationSkill.Genre.AUTO,"Keeper = سەرپەرشتیار")
                .translatePage(Arrays.asList(TITLE,DESCRIPTION,body),"en","ckb",true,(text,a,b)->text,null);
        check(custom.results.get(0).text.contains("سەرپەرشتیار")&&!custom.results.get(0).text.contains("پارێزەری"),
                "Reader role choice wins over built-in grammar");
        List<String> requests=new ArrayList<>();
        skill(ComicTranslationSkill.Genre.AUTO,"").translatePage(Arrays.asList(TITLE,DESCRIPTION,body),"en","ar",true,
                (text,a,b)->{requests.add(text);return text;},null);
        check(requests.stream().anyMatch(s->s.contains("Stone Keeper")),"Role grammar never forces Sorani into another target");
        requests.clear();
        skill(ComicTranslationSkill.Genre.AUTO,"").translatePage(Collections.singletonList("Stone Keeper walks near the stones."),"en","ckb",true,
                (text,a,b)->{requests.add(text);return text;},null);
        check(requests.stream().anyMatch(s->s.contains("Stone Keeper")),"Ordinary dialogue is never a role field");
        for(ComicTranslationSkill.Genre genre:new ComicTranslationSkill.Genre[]{ComicTranslationSkill.Genre.EVERYDAY,ComicTranslationSkill.Genre.MARTIAL}) {
            requests.clear();skill(genre,"").translatePage(Arrays.asList(TITLE,DESCRIPTION,body),"en","ckb",true,
                    (text,a,b)->{requests.add(text);return text;},null);
            check(requests.stream().anyMatch(s->s.contains("Stone Keeper")),"Explicit non-fantasy genre disables role grammar");
        }
        requests.clear();
        skill(ComicTranslationSkill.Genre.AUTO,"").translatePage(Arrays.asList(TITLE,DESCRIPTION,body,TITLE,DESCRIPTION,body),"en","ckb",true,
                (text,a,b)->{requests.add(text);return text;},null);
        check(requests.stream().anyMatch(s->s.contains("Stone Keeper")),"Ambiguous multiple field pairs must not share inferred role context");
        requests.clear();
        skill(ComicTranslationSkill.Genre.AUTO,"").translatePage(Arrays.asList("ASPECT: Shopkeeper",DESCRIPTION,body),"en","ckb",true,
                (text,a,b)->{requests.add(text);return text;},null);
        check(requests.stream().anyMatch(s->s.contains("Shopkeeper")),"A word suffix is not the separate Keeper role");
        requests.clear();
        new ComicTranslationSkill(new ComicTranslationSkill.Config(false,ComicTranslationSkill.Genre.AUTO,""),lexicon)
                .translatePage(Arrays.asList(TITLE,DESCRIPTION,body),"en","ckb",true,(text,a,b)->{requests.add(text);return text;},null);
        check(requests.stream().anyMatch(s->s.contains("Stone Keeper")),"Disabled Skill never applies role grammar");
    }
}
