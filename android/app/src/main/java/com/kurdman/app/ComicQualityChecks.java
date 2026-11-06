package com.kurdman.app;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** On-device, deterministic review cues. No Android, model, network or accuracy score. */
public final class ComicQualityChecks {
    private static final Pattern RESERVED=Pattern.compile("ZXQK|\\[\\[KX",Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER=Pattern.compile("[-+]?\\d+(?:[.,]\\d+)*(?:\\s*%)?");
    private static final Pattern REPEATED=Pattern.compile("(\\p{L}{2,})(?:\\s+\\1){3,}",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private final List<ComicTranslationSkill.Entry> terms;

    public ComicQualityChecks(ComicTranslationSkill.Config config) throws ComicTranslationSkill.GlossaryException {
        terms=new ArrayList<>(ComicTranslationSkill.parseGlossary(config.glossary));
        terms.sort(Comparator.comparingInt((ComicTranslationSkill.Entry e)->e.source.length()).reversed());
    }

    public String review(String original, String translated, String sourceLanguage, String targetLanguage) {
        String source=ComicTranslationSkill.clean(original), target=ComicTranslationSkill.clean(translated);
        Set<String> issues=new LinkedHashSet<>();
        if(source.isEmpty())return target.isEmpty()?"":"source_empty";
        if(target.isEmpty())return "empty_target";
        if(RESERVED.matcher(target).find())issues.add("markers");
        List<String> before=numbers(source), after=numbers(target);
        if(!counts(before).equals(counts(after)))issues.add("numbers");
        else if(!before.equals(after))issues.add("number_order");
        boolean[] taken=new boolean[source.length()];
        Map<String,Integer> expected=new LinkedHashMap<>();
        boolean literal=false;
        for(ComicTranslationSkill.Entry term:terms){
            if(lineKey(source).equals(lineKey(term.source)) && lineKey(target).equals(lineKey(term.target)))literal=true;
            Matcher matches=term.pattern.matcher(source);
            while(matches.find()){
                boolean overlaps=false;
                for(int i=matches.start();i<matches.end();i++)if(taken[i]){overlaps=true;break;}
                if(overlaps)continue;
                for(int i=matches.start();i<matches.end();i++)taken[i]=true;
                expected.merge(term.target,1,Integer::sum);
            }
        }
        for(Map.Entry<String,Integer> entry:expected.entrySet()){
            Matcher matches=Pattern.compile(Pattern.quote(entry.getKey()),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(target);
            int found=0;while(matches.find())found++;
            if(found<entry.getValue()){issues.add("glossary_missing");break;}
        }
        boolean same=base(sourceLanguage).equals(base(targetLanguage));
        if(!same&&!literal){
            if(source.equalsIgnoreCase(target)&&source.codePoints().anyMatch(Character::isWhitespace))issues.add("untranslated");
            if(Arrays.asList("ckb","ar","fa","ur","ps","sd","ug").contains(base(targetLanguage))){
                int letters=0;boolean arabic=false;
                for(int c:target.codePoints().toArray())if(Character.isLetter(c)){
                    letters++;
                    if(Character.UnicodeScript.of(c)==Character.UnicodeScript.ARABIC)arabic=true;
                }
                if(letters>=4&&!arabic)issues.add("target_script");
            }
        }
        if(!REPEATED.matcher(source).find()&&REPEATED.matcher(target).find())issues.add("repetition");
        return String.join(",",issues);
    }

    public static boolean blocksReview(String codes){
        for(String code:codes.split(","))if(code.equals("empty_target")||code.equals("markers"))return true;
        return false;
    }

    public static String merge(String... lists){
        Set<String> result=new LinkedHashSet<>();
        for(String list:lists)if(list!=null)for(String code:list.split(","))if(!code.isEmpty())result.add(code);
        return String.join(",",result);
    }

    /** User-requested correction memory; same limits/parser as the existing screen. */
    public static String upsertGlossary(String glossary,String original,String preferred) throws ComicTranslationSkill.GlossaryException {
        ComicTranslationSkill.validateGlossary(glossary);
        String source=ComicTranslationSkill.clean(original), target=ComicTranslationSkill.clean(preferred);
        if(source.isEmpty()||source.contains("=")||original.contains("\n")||original.contains("\r")
                ||preferred.contains("\n")||preferred.contains("\r"))throw new ComicTranslationSkill.GlossaryException(1);
        String line=source+" = "+target;
        ComicTranslationSkill.validateGlossary(line);
        String[] lines=glossary.split("\\r?\\n",-1);
        boolean replaced=false;
        for(int i=0;i<lines.length;i++){
            String old=lines[i].trim();int equals=old.indexOf('=');
            if(old.startsWith("#")||equals<=0)continue;
            if(ComicTranslationSkill.clean(old.substring(0,equals)).equalsIgnoreCase(source)){
                lines[i]=line;replaced=true;break;
            }
        }
        String result=replaced?String.join("\n",lines):glossary+(glossary.isEmpty()||glossary.endsWith("\n")?"":"\n")+line;
        ComicTranslationSkill.validateGlossary(result);
        return result;
    }

    /** Exact candidate/context binding, not reviewer authentication. Never logged. */
    public static String binding(String... values){
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String value:values){
                String units=value==null?"":value;
                digest.update(ByteBuffer.allocate(4).putInt(units.length()).array());
                for(int i=0;i<units.length();i++){
                    char unit=units.charAt(i);digest.update((byte)(unit>>>8));digest.update((byte)unit);
                }
            }
            StringBuilder hex=new StringBuilder();
            for(byte b:digest.digest()){hex.append(Character.forDigit((b>>>4)&15,16));hex.append(Character.forDigit(b&15,16));}
            return hex.toString();
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA256 unavailable",impossible);}
    }

    private static String lineKey(String value){
        return ComicTranslationSkill.clean(value).toLowerCase(Locale.ROOT).replaceAll("[.!?؟…。！…]+$","");
    }
    private static String base(String language){return language==null?"":language.toLowerCase(Locale.ROOT).split("-")[0];}
    private static Map<String,Integer> counts(List<String> tokens){
        Map<String,Integer> counts=new LinkedHashMap<>();for(String token:tokens)counts.merge(token,1,Integer::sum);return counts;
    }
    private static List<String> numbers(String text){
        StringBuilder latin=new StringBuilder();
        for(int c:text.codePoints().toArray()){
            int digit=Character.digit(c,10);
            if(digit>=0)latin.append(digit);
            else if(c==0x066C){/* thousands separator */}
            else if(c==0x066B)latin.append('.');
            else if(c==0x2212)latin.append('-');
            else if(c==0x066A)latin.append('%');
            else latin.appendCodePoint(c);
        }
        String value=latin.toString().replaceAll("(?<=\\d),(?=\\d{3}(?:\\D|$))","");
        List<String> result=new ArrayList<>();Matcher matcher=NUMBER.matcher(value);
        while(matcher.find())result.add(matcher.group().replaceAll("\\s+%","%"));
        return result;
    }
}
