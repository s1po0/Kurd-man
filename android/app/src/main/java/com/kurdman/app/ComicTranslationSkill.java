package com.kurdman.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, rule-based manga/manhwa translation layer, NOT a new translation
 * model or an LLM prompt. Uses the existing backend, offline or online.
 *
 * <p>Only full-line idioms and numeric system-message templates are translated
 * locally. Terminology is constrained with reversible placeholders; if a
 * backend damages one, its entire response is discarded and the original
 * bubble is translated normally. Online context windows have a similarly
 * strict one-to-one boundary contract. A failed contract never becomes text
 * on the page. Context windows never cross page boundaries.</p>
 *
 * <p>Genre inference selects vocabulary, not speakers, relationships or
 * pronouns. Structural checks are review hints, NOT semantic quality scores.
 * This class is Android-independent so its safety contracts can be tested
 * with adversarial backends, without sending a reader's text to a service.</p>
 */
public final class ComicTranslationSkill {
    public enum Genre { AUTO, FANTASY, MARTIAL, EVERYDAY }

    public static final class Config {
        public final boolean enabled;
        public final Genre genre;
        public final String glossary;

        public Config(boolean enabled, Genre genre, String glossary) {
            this.enabled = enabled;
            this.genre = genre == null ? Genre.AUTO : genre;
            this.glossary = glossary == null ? "" : glossary;
        }
    }

    public interface Backend {
        String translate(String text, String source, String target) throws Exception;
    }

    public interface Progress {
        /** Called before requests as well as after completed bubbles (STOP). */
        void onProgress(int completed, int total) throws Exception;
    }

    public static final class Result {
        public String text;
        public final String method;
        /** Comma-separated, language-independent review codes. */
        public String warnings;

        Result(String text, String method, String warnings) {
            this.text = text;
            this.method = method;
            this.warnings = warnings;
        }
    }

    public static final class PageResult {
        public final List<Result> results = new ArrayList<>();
        public Genre genre;
        public int localRules;
        public int contextRequests;
        public int contextFallbacks;
        public int glossaryFallbacks;
        /** Bounded contextual recovery, not semantic/reader approval. */
        public int qualityRetries;
        public int qualityRecoveries;
    }

    public static final class GlossaryException extends Exception {
        public final int line;
        GlossaryException(int line) {
            super("Invalid or duplicate glossary entry at line " + line);
            this.line = line;
        }
    }

    static final class Entry {
        final String kind;
        final String domain;
        final String language;
        final String source;
        final String target;
        final Pattern pattern;

        Entry(String kind, String domain, String language, String source, String target) {
            this.kind = kind;
            this.domain = domain;
            this.language = language;
            this.source = source;
            this.target = target;
            // Latin words must not match inside other words (raid/braid).
            // East Asian names/terms may be followed by grammatical particles.
            boolean latin = source.codePoints().anyMatch(c -> c < 128 && Character.isLetter(c));
            String boundary = "[\\p{L}\\p{N}_]";
            pattern = Pattern.compile((latin ? "(?<!" + boundary + ")" : "")
                    + Pattern.quote(source)
                    + (latin ? "(?!" + boundary + ")" : ""),
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }

        boolean supports(String lang, Genre genre) {
            return language.equals(baseLanguage(lang))
                    && (domain.equals("all")
                    || domain.contains(genre.name().toLowerCase(Locale.ROOT)));
        }
    }

    private static final Pattern BOUNDARY = Pattern.compile("\\[\\[KX(\\d{4})]]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RESERVED = Pattern.compile("ZXQK|\\[\\[KX",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(?:[.,]\\d+)*");
    private static final Pattern REPEATED = Pattern.compile(
            "(\\p{L}{2,})(?:\\s+\\1){3,}", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final int MAX_WINDOW = 4;
    private static final int MAX_WINDOW_CHARS = 1050;
    private static final int MAX_GLOSSARY = 100;
    private static final int MAX_QUALITY_RETRIES_PER_PAGE = 2;

    private final Config config;
    private final List<Entry> lexicon;
    private final List<Entry> custom;
    private final ComicQualityChecks quality;
    // Disable a failing optimisation for the rest of this job, not forever.
    private boolean contextSupported = true;
    private boolean offlineMaskingSupported = true;

    public ComicTranslationSkill(Config config, List<Entry> lexicon)
            throws GlossaryException {
        this.config = config;
        this.lexicon = lexicon;
        this.custom = parseGlossary(config.glossary);
        this.quality = new ComicQualityChecks(config);
    }

    static List<Entry> readLexicon(Reader reader) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        try (BufferedReader lines = new BufferedReader(reader)) {
            String line;
            int number = 0;
            while ((line = lines.readLine()) != null) {
                number++;
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] fields = line.split("\t", -1);
                if (fields.length != 5 || !Arrays.asList("line", "term").contains(fields[0])
                        || fields[3].isEmpty() || fields[4].isEmpty()
                        || RESERVED.matcher(line).find()) {
                    throw new IOException("Invalid comic lexicon line " + number);
                }
                String key = String.join("\t", Arrays.copyOf(fields, 4)).toLowerCase(Locale.ROOT);
                if (!keys.add(key)) throw new IOException("Duplicate comic lexicon line " + number);
                entries.add(new Entry(fields[0], fields[1], fields[2], fields[3], fields[4]));
            }
        }
        return Collections.unmodifiableList(entries);
    }

    public static int validateGlossary(String text) throws GlossaryException {
        return parseGlossary(text).size();
    }

    static List<Entry> parseGlossary(String text) throws GlossaryException {
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String safe = text == null ? "" : text;
        if (safe.length() > 12000) throw new GlossaryException(1);
        String[] lines = safe.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int equals = line.indexOf('=');
            if (equals <= 0) throw new GlossaryException(i + 1);
            String source = clean(line.substring(0, equals));
            String target = clean(line.substring(equals + 1));
            if (source.isEmpty() || source.length() > 80 || target.isEmpty()
                    || target.length() > 160 || RESERVED.matcher(line).find()
                    || entries.size() >= MAX_GLOSSARY
                    || !seen.add(source.toLowerCase(Locale.ROOT))) {
                throw new GlossaryException(i + 1);
            }
            entries.add(new Entry("term", "all", "custom", source, target));
        }
        return entries;
    }

    public PageResult translatePage(List<String> sources, String sourceLanguage,
                                   String targetLanguage, boolean offline,
                                   Backend backend, Progress progress) throws Exception {
        check(progress,0,sources.size());
        PageResult fields=translateSystemPanel(sources,sourceLanguage,targetLanguage,offline,backend,progress);
        return fields!=null?fields:translatePlainPage(sources,sourceLanguage,targetLanguage,offline,backend,progress,null);
    }

    private PageResult translatePlainPage(List<String> sources,String sourceLanguage,
            String targetLanguage,boolean offline,Backend backend,Progress progress,Genre forcedGenre) throws Exception {
        PageResult page = new PageResult();
        page.genre = forcedGenre!=null?forcedGenre:config.genre == Genre.AUTO ? inferGenre(sources) : config.genre;
        List<Plan> plans = new ArrayList<>();
        for (String source : sources) {
            String text = clean(source);
            Plan plan = new Plan(text);
            if (!config.enabled || sameLanguage(sourceLanguage, targetLanguage) || text.isEmpty()) {
                plan.direct = sameLanguage(sourceLanguage, targetLanguage) || text.isEmpty() ? text : null;
            } else {
                plan.mask = protect(text, sourceLanguage, targetLanguage, page.genre);
                // Explicit reader choices take precedence over all built-in rules.
                for (Entry term : custom) {
                    if (lineKey(text).equals(lineKey(term.source))) {
                        plan.direct = withPunctuation(term.target, text);
                        plan.method = "comic_glossary";
                        break;
                    }
                }
                boolean customInSentence = custom.stream().anyMatch(e -> e.pattern.matcher(text).find());
                if (plan.direct == null && !customInSentence && "ckb".equals(targetLanguage)) {
                    for (Entry entry : lexicon) {
                        if (entry.supports(sourceLanguage, page.genre)
                                && (entry.kind.equals("line") || entry.kind.equals("term"))
                                && lineKey(text).equals(lineKey(entry.source))) {
                            plan.direct = withPunctuation(entry.target, text);
                            plan.method = "comic_phrase";
                            break;
                        }
                    }
                    if (plan.direct == null && baseLanguage(sourceLanguage).equals("en")
                            && (page.genre == Genre.FANTASY || config.genre == Genre.AUTO)) {
                        plan.direct = systemMessage(text);
                        if (plan.direct != null) plan.method = "comic_system";
                    }
                }
            }
            plans.add(plan);
        }

        for (int start = 0; start < plans.size();) {
            check(progress, page.results.size(), plans.size());
            int end = start + 1;
            int chars = plans.get(start).input().length() + 16;
            while (end < plans.size() && end - start < MAX_WINDOW
                    && chars + plans.get(end).input().length() + 16 <= MAX_WINDOW_CHARS) {
                chars += plans.get(end).input().length() + 16;
                end++;
            }
            List<Plan> window = plans.subList(start, end);
            long pending = window.stream().filter(p -> p.direct == null).count();
            boolean batch = config.enabled && !offline && contextSupported && pending >= 2
                    && window.stream().noneMatch(p -> RESERVED.matcher(p.source).find());
            List<String> outputs = null;
            if (batch) {
                page.contextRequests++;
                try {
                    String response = backend.translate(joinWindow(window), sourceLanguage, targetLanguage);
                    outputs = splitWindow(response, window.size());
                    if (outputs != null) {
                        List<String> restored = new ArrayList<>();
                        for (int i = 0; i < window.size(); i++) {
                            Plan plan = window.get(i);
                            String value = plan.mask.restore(outputs.get(i));
                            if (value == null || value.isEmpty() || !sameNumbers(plan.source, value)) {
                                outputs = null;
                                break;
                            }
                            restored.add(value);
                        }
                        if (outputs != null) outputs = restored;
                    }
                } catch (Exception failure) {
                    rethrowCancellation(failure);
                    check(progress, page.results.size(), plans.size());
                    // Fallback below also handles temporary route failures.
                }
                if (outputs == null) {
                    page.contextFallbacks++;
                    contextSupported = false;
                }
            }
            for (int i = 0; i < window.size(); i++) {
                check(progress, page.results.size(), plans.size());
                Plan plan = window.get(i);
                String value;
                String method;
                String warnings = "";
                if (plan.direct != null) {
                    value = plan.direct;
                    method = plan.method;
                    if (method.startsWith("comic_")) page.localRules++;
                } else if (outputs != null) {
                    value = outputs.get(i);
                    method = "comic_context";
                } else {
                    value = null;
                    method = config.enabled ? "comic_individual" : "standard";
                    if (!plan.mask.terms.isEmpty() && (!offline || offlineMaskingSupported)) {
                        try {
                            String response = backend.translate(plan.input(), sourceLanguage, targetLanguage);
                            value = plan.mask.restore(response);
                            if (value != null && (!sameNumbers(plan.source, value) || value.isEmpty())) value = null;
                        } catch (Exception failure) {
                            rethrowCancellation(failure);
                            check(progress, page.results.size(), plans.size());
                        }
                        if (value == null && offline) offlineMaskingSupported = false;
                        if (value != null) method = "comic_glossary";
                    }
                    if (value == null) {
                        check(progress, page.results.size(), plans.size());
                        value = clean(backend.translate(plan.source, sourceLanguage, targetLanguage));
                        // Only replace a term still literally present in the output.
                        // Never global-replace guessed Kurdish/Arabic synonyms.
                        value = plan.mask.replaceEchoedTerms(value);
                        if (!plan.mask.terms.isEmpty()) {
                            page.glossaryFallbacks++;
                            warnings = "glossary_unconfirmed";
                        }
                    }
                }
                if (value == null || value.trim().isEmpty() && !plan.source.isEmpty()) {
                    throw new IOException("Empty comic translation response");
                }
                if (config.enabled && !RESERVED.matcher(plan.source).find() && RESERVED.matcher(value).find()) {
                    throw new IOException("Unrestorable comic translation marker");
                }
                if (config.enabled && !sameLanguage(sourceLanguage, targetLanguage)) {
                    String cues = reviewHints(plan.source, value, sourceLanguage, targetLanguage);
                    // A contextual response may obey marker/number counts yet echo
                    // the source, reverse a ratio or lose a percent sign. Recover
                    // just that bubble using the existing selected online route.
                    // No second model, offline network fallback or unbounded loop.
                    if (!offline && "comic_context".equals(method) && !cues.isEmpty()
                            && !RESERVED.matcher(plan.source).find()
                            && page.qualityRetries < MAX_QUALITY_RETRIES_PER_PAGE) {
                        check(progress, page.results.size(), plans.size());
                        page.qualityRetries++;
                        try {
                            String candidate = plan.mask.restore(backend.translate(
                                    plan.input(), sourceLanguage, targetLanguage));
                            check(progress, page.results.size(), plans.size());
                            if (candidate != null && !candidate.isEmpty() && sameNumbers(plan.source, candidate)) {
                                String candidateCues = reviewHints(plan.source, candidate, sourceLanguage, targetLanguage);
                                if (strictlyFewerCues(cues, candidateCues)) {
                                    value = candidate;
                                    cues = candidateCues;
                                    method = "comic_context_recovered";
                                    page.qualityRecoveries++;
                                }
                            }
                        } catch (Exception failure) {
                            rethrowCancellation(failure);
                            check(progress, page.results.size(), plans.size());
                            // Preserve the original editable result and its hints.
                        }
                    }
                    warnings = addWarning(warnings, cues);
                }
                page.results.add(new Result(clean(value), method, warnings));
                check(progress, page.results.size(), plans.size());
            }
            start = end;
        }
        return page;
    }

    private static final Pattern SYSTEM_FIELD = Pattern.compile(
            "(?im)^[\t ]*(ASPECT DESCRIPTION|ASPECT)[\t ]*[:：][\t ]*");

    private static final Pattern SYSTEM_KEEPER = Pattern.compile("([a-z]{3,24})[\\t ]+keeper",Pattern.CASE_INSENSITIVE);
    private static final Pattern SPORTS_CONTEXT = Pattern.compile(
            "\\b(?:football|soccer|hockey|handball|lacrosse|goals?|goalkeepers?|goalkeeping|balls?|teams?|matches|pitch)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final class SystemPart {
        final String label,value;
        final boolean title;
        String valuePrefix="";
        int translated=-1;
        SystemPart(String label,String value){this(label,value,false);}
        SystemPart(String label,String value,boolean title){this.label=label;this.value=value;this.title=title;}
    }

    /**
     * Two explicit field labels plus independent game-system vocabulary are
     * evidence for a trait panel, not permission to replace ordinary 'aspect'.
     * Labels and a conservative, description-supported Keeper role head are
     * local. Every noun modifier and the complete body still use the SAME
     * chosen translator, glossary contracts, bounded context and STOP.
     */
    private PageResult translateSystemPanel(List<String> sources,String source,String target,
            boolean offline,Backend backend,Progress progress) throws Exception {
        if(!config.enabled || !"en".equals(baseLanguage(source)) || !"ckb".equals(target)
                || (config.genre!=Genre.AUTO && config.genre!=Genre.FANTASY))return null;
        String pageText=String.join("\n",sources);
        if(RESERVED.matcher(pageText).find())return null;
        int cues=0;
        for(String cue:new String[]{"skills?","abilit(?:y|ies)","ranks?","traits?","powers?",
                "rarity|rare|rarer|uncommon|dormant|awakened|ascended"}) {
            if(Pattern.compile("\\b(?:"+cue+")\\b",Pattern.CASE_INSENSITIVE).matcher(pageText).find())cues++;
        }
        if(cues<2)return null;
        boolean named=false,description=false;
        int fieldCount=0;
        List<List<SystemPart>> blocks=new ArrayList<>();
        for(String raw:sources) {
            check(progress,0,sources.size());
            String normalized=Normalizer.normalize(raw==null?"":raw,Normalizer.Form.NFKC).replace('\r','\n');
            Matcher matcher=SYSTEM_FIELD.matcher(normalized);
            List<Integer> starts=new ArrayList<>(),ends=new ArrayList<>();
            List<String> labels=new ArrayList<>();
            while(matcher.find()){
                if(++fieldCount>64)return null;
                starts.add(matcher.start());ends.add(matcher.end());labels.add(matcher.group(1));
            }
            List<SystemPart> parts=new ArrayList<>();
            if(starts.isEmpty())parts.add(new SystemPart("",clean(normalized)));
            else {
                String prefix=clean(normalized.substring(0,starts.get(0)));
                if(!prefix.isEmpty())parts.add(new SystemPart("",prefix));
                for(int i=0;i<starts.size();i++) {
                    String value=clean(normalized.substring(ends.get(i),i+1<starts.size()?starts.get(i+1):normalized.length()));
                    boolean desc=labels.get(i).equalsIgnoreCase("ASPECT DESCRIPTION");
                    description|=desc;named|=!desc&&!value.isEmpty()&&value.length()<=100;
                    parts.add(new SystemPart(systemFieldLabel(desc),value,!desc));
                }
                // An exact reader-authored whole-block override always wins.
                for(Entry entry:custom)if(lineKey(entry.source).equals(lineKey(normalized))){
                    parts.clear();parts.add(new SystemPart("",clean(normalized)));break;
                }
            }
            blocks.add(parts);
        }
        if(!named||!description)return null;
        String roleContext=systemRoleContext(blocks,pageText);
        List<String> values=new ArrayList<>();List<Integer> owners=new ArrayList<>();
        for(int i=0;i<blocks.size();i++)for(SystemPart part:blocks.get(i))if(!part.value.isEmpty()){
            check(progress,0,sources.size());
            String modifier=part.title?systemKeeperModifier(part.value,roleContext):null;
            if(modifier!=null)part.valuePrefix="پارێزەری ";
            part.translated=values.size();values.add(modifier==null?part.value:modifier);owners.add(i);
        }
        final int[] reported={0};
        Progress bounded=(done,total)->{
            reported[0]=Math.max(reported[0],done>=owners.size()?sources.size():owners.get(done));
            check(progress,reported[0],sources.size());
        };
        PageResult translated=translatePlainPage(values,source,target,offline,backend,bounded,Genre.FANTASY);
        PageResult result=new PageResult();result.genre=Genre.FANTASY;
        result.localRules=translated.localRules;result.contextRequests=translated.contextRequests;
        result.contextFallbacks=translated.contextFallbacks;result.glossaryFallbacks=translated.glossaryFallbacks;
        result.qualityRetries=translated.qualityRetries;result.qualityRecoveries=translated.qualityRecoveries;
        for(int i=0;i<blocks.size();i++) {
            check(progress,Math.max(reported[0],i),sources.size());
            StringBuilder out=new StringBuilder();String warnings="",method="comic_system_fields";boolean local=false;
            for(SystemPart part:blocks.get(i)) {
                if(!part.label.isEmpty()){
                    if(out.length()>0)out.append(' ');out.append(part.label);local=true;
                }
                if(part.translated>=0){
                    Result value=translated.results.get(part.translated);
                    if(out.length()>0)out.append(' ');out.append(part.valuePrefix).append(value.text);
                    warnings=ComicQualityChecks.merge(warnings,value.warnings);method=value.method;
                }
            }
            if(local){method="comic_system_fields";result.localRules++;}
            String finalText=clean(out.toString());
            warnings=ComicQualityChecks.merge(warnings,reviewHints(sources.get(i),finalText,source,target));
            result.results.add(new Result(finalText,method,warnings));
            reported[0]=Math.max(reported[0],i+1);
            check(progress,reported[0],sources.size());
        }
        return result;
    }

    /** Only one unambiguous field pair; body evidence never comes from its title. */
    private static String systemRoleContext(List<List<SystemPart>> blocks,String pageText) {
        if(SPORTS_CONTEXT.matcher(pageText).find())return "";
        int titles=0,descriptions=0;
        boolean inDescription=false;
        StringBuilder body=new StringBuilder();
        for(List<SystemPart> block:blocks)for(SystemPart part:block) {
            if(part.title){titles++;inDescription=false;}
            else if(!part.label.isEmpty()){descriptions++;inDescription=true;}
            if(inDescription&&!part.value.isEmpty())body.append(part.value).append(' ');
        }
        return titles==1&&descriptions==1?body.toString():"";
    }

    /**
     * A description-referenced object + Keeper is a custodian role here, not
     * an invented goalkeeper. Do not rewrite an arbitrary title or dialogue,
     * infer a personal name, or override any reader term in that value.
     * Only a single short English noun and its regular plural are supported;
     * unsupported/ambiguous forms stay with the selected translator.
     */
    private String systemKeeperModifier(String value,String description) {
        if(description.isEmpty())return null;
        Matcher keeper=SYSTEM_KEEPER.matcher(value);
        if(!keeper.matches())return null;
        for(Entry entry:custom)if(entry.pattern.matcher(value).find())return null;
        String noun=keeper.group(1);
        Pattern reference=Pattern.compile("\\b"+Pattern.quote(noun)+"s?\\b",Pattern.CASE_INSENSITIVE);
        return reference.matcher(description).find()?noun:null;
    }

    private String systemFieldLabel(boolean description) {
        String source=description?"Aspect Description":"Aspect";
        for(Entry entry:custom){
            if(lineKey(entry.source).equals(lineKey(source+":")))return entry.target;
            if(lineKey(entry.source).equals(lineKey(source)))return entry.target.endsWith(":")?entry.target:entry.target+":";
        }
        return description?"وەسفی تایبەتمەندی:":"تایبەتمەندی:";
    }

    /** Keep explicit reader spellings intact during final script/name cleanup.
     * The guards are local-only private-use characters, never sent to a backend. */
    String formatPreservingGlossary(String text, java.util.function.UnaryOperator<String> formatter) {
        List<Entry> ordered = new ArrayList<>(custom);
        ordered.sort(Comparator.comparingInt((Entry e) -> e.target.length()).reversed());
        Map<String, String> guards = new LinkedHashMap<>();
        String protectedText = text;
        int guard = 0xE000;
        for (Entry entry : ordered) {
            while (guard <= 0xF8FF && protectedText.indexOf((char) guard) >= 0) guard++;
            if (guard > 0xF8FF) return text;
            String token = String.valueOf((char) guard++);
            Entry target = new Entry("term", "all", "custom", entry.target, entry.target);
            String next = target.pattern.matcher(protectedText).replaceAll(Matcher.quoteReplacement(token));
            if (!next.equals(protectedText)) {
                guards.put(token, entry.target);
                protectedText = next;
            }
        }
        String result = formatter.apply(protectedText);
        for (Map.Entry<String, String> entry : guards.entrySet()) result = result.replace(entry.getKey(), entry.getValue());
        return result;
    }

    private static final class Plan {
        final String source;
        Mask mask;
        String direct;
        String method = "standard";
        Plan(String source) { this.source = source; this.mask = new Mask(source); }
        String input() { return mask.text; }
    }

    private static final class ProtectedTerm {
        final String marker;
        final Entry entry;
        ProtectedTerm(String marker, Entry entry) { this.marker = marker; this.entry = entry; }
    }

    private static final class Mask {
        String text;
        final List<ProtectedTerm> terms = new ArrayList<>();
        final boolean allowReserved;
        Mask(String text) { this.text = text; this.allowReserved = RESERVED.matcher(text).find(); }
        String restore(String response) {
            if (response == null) return null;
            String out = clean(response);
            for (ProtectedTerm term : terms) {
                Pattern pattern = Pattern.compile(Pattern.quote(term.marker), Pattern.CASE_INSENSITIVE);
                Matcher matcher = pattern.matcher(out);
                if (!matcher.find() || matcher.find()) return null;
                out = pattern.matcher(out).replaceFirst(Matcher.quoteReplacement(term.entry.target));
            }
            // Unknown, truncated or reordered boundary text must never leak.
            return RESERVED.matcher(out).find() && !allowReserved ? null : out;
        }
        String replaceEchoedTerms(String response) {
            // One pass over the original output: A=B and B=C must not cascade
            // into A=C, nor repeatedly expand a name whose target includes it.
            List<Entry> unique = new ArrayList<>();
            for (ProtectedTerm term : terms) if (!unique.contains(term.entry)) unique.add(term.entry);
            unique.sort(Comparator.comparingInt((Entry e) -> e.source.length()).reversed());
            if (unique.isEmpty()) return response;
            List<String> alternatives = new ArrayList<>();
            for (Entry entry : unique) alternatives.add("(?:" + entry.pattern.pattern() + ")");
            Matcher matches = Pattern.compile(String.join("|", alternatives),
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(response);
            StringBuffer out = new StringBuffer();
            while (matches.find()) {
                String replacement = matches.group();
                for (Entry entry : unique) {
                    if (entry.source.equalsIgnoreCase(matches.group())) { replacement = entry.target; break; }
                }
                matches.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            matches.appendTail(out);
            return out.toString();
        }
    }

    private Mask protect(String text, String language, String target, Genre genre) {
        Mask mask = new Mask(text);
        if (RESERVED.matcher(text).find()) return mask;
        List<Entry> available = new ArrayList<>(custom);
        List<Entry> builtIn = new ArrayList<>();
        if ("ckb".equals(target)) {
            for (Entry entry : lexicon) {
                if (entry.kind.equals("term") && entry.supports(language, genre)) builtIn.add(entry);
            }
        }
        // Custom entries always win an overlap, then longest built-in phrase.
        Comparator<Entry> longest = Comparator.comparingInt((Entry e) -> e.source.length()).reversed();
        available.sort(longest);
        builtIn.sort(longest);
        available.addAll(builtIn);
        int[] owner = new int[text.length()];
        Arrays.fill(owner, -1);
        List<Entry> occurrences = new ArrayList<>();
        for (Entry entry : available) {
            Matcher matcher = entry.pattern.matcher(text);
            while (matcher.find()) {
                boolean free = true;
                for (int i = matcher.start(); i < matcher.end(); i++) if (owner[i] >= 0) { free = false; break; }
                if (free) {
                    Arrays.fill(owner, matcher.start(), matcher.end(), occurrences.size());
                    occurrences.add(entry);
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length();) {
            int occurrence = owner[i];
            if (occurrence < 0 || mask.terms.size() >= 64) { out.append(text.charAt(i++)); continue; }
            int end = i + 1;
            while (end < text.length() && owner[end] == occurrence) end++;
            Entry entry = occurrences.get(occurrence);
            String marker = String.format(Locale.ROOT, "ZXQK%04dQXZ", mask.terms.size());
            mask.terms.add(new ProtectedTerm(marker, entry));
            out.append(marker);
            i = end;
        }
        mask.text = out.toString();
        return mask;
    }

    private static String joinWindow(List<Plan> plans) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < plans.size(); i++) {
            out.append(String.format(Locale.ROOT, "[[KX%04d]]\n", i));
            out.append(plans.get(i).input()).append('\n');
        }
        return out.toString().trim();
    }

    /** Strict ordering/count; do not guess a split after a marker is lost. */
    static List<String> splitWindow(String response, int count) {
        if (response == null || count <= 0) return null;
        Matcher matcher = BOUNDARY.matcher(response);
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        while (matcher.find()) {
            if (starts.size() >= count || Integer.parseInt(matcher.group(1)) != starts.size()) return null;
            starts.add(matcher.start());
            ends.add(matcher.end());
        }
        if (starts.size() != count || !response.substring(0, starts.get(0)).trim().isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String part = response.substring(ends.get(i), i + 1 < count ? starts.get(i + 1) : response.length()).trim();
            if (part.isEmpty() || BOUNDARY.matcher(part).find() || part.toLowerCase(Locale.ROOT).contains("[[kx")) return null;
            out.add(part);
        }
        return out;
    }

    static Genre inferGenre(List<String> sources) {
        String text = clean(String.join(" ", sources)).toLowerCase(Locale.ROOT);
        int martial = countCues(text, "martial arts", "inner energy", "sword intent", "sect leader",
                "cultivation technique", "meridian", "qi", "무공", "내공", "武功", "真气", "真氣");
        int fantasy = countCues(text, "dungeon", "mana", "guild", "quest", "inventory", "level up",
                "experience points", "stat points", "던전", "마나", "길드", "퀘스트", "ダンジョン", "クエスト", "魔力", "副本");
        if (countCues(text, "murim", "dantian", "무림", "단전", "丹田", "武林") > 0) martial += 3;
        if (countCues(text, "leveled up", "levelled up", "level up", "quest completed", "quest failed", "skill acquired",
                "dungeon boss", "dungeon break", "mana crystal", "status window", "skill points", "stat points",
                "레벨 업", "퀘스트 완료", "퀘스트 실패", "レベルアップ") > 0) fantasy += 2;
        if (martial >= 2 && martial >= fantasy) return Genre.MARTIAL;
        return fantasy >= 2 ? Genre.FANTASY : Genre.EVERYDAY;
    }

    private static int countCues(String text, String... cues) {
        int score = 0;
        for (String cue : cues) {
            Entry entry = new Entry("term", "all", "en", cue, "");
            if (entry.pattern.matcher(text).find()) score++;
        }
        return score;
    }

    /** Anchored templates: preserve numeric slots, never translate a fragment. */
    static String systemMessage(String text) {
        String key = lineKey(text);
        String number = "([0-9]+(?:[,.][0-9]+)*)";
        Matcher match = Pattern.compile("(?:you have |you )?(?:gained|received) " + number
                + " (?:exp|xp|experience points)").matcher(key);
        if (match.matches()) return match.group(1) + " خاڵی ئەزموونت وەرگرت.";
        match = Pattern.compile("(?:level|lv\\.?)[: ]+" + number).matcher(key);
        if (match.matches()) return "ئاستی " + match.group(1);
        match = Pattern.compile("(?:hp|health points)[: ]+" + number + "\\s*/\\s*" + number).matcher(key);
        if (match.matches()) return "خاڵی تەندروستی: " + match.group(1) + "/" + match.group(2);
        match = Pattern.compile("(?:mp|mana points)[: ]+" + number + "\\s*/\\s*" + number).matcher(key);
        if (match.matches()) return "خاڵی مانا: " + match.group(1) + "/" + match.group(2);
        match = Pattern.compile("(?:rank|rank:) ([sabcde])(?:[- ]rank)?").matcher(key);
        if (match.matches()) return "پلەی " + match.group(1).toUpperCase(Locale.ROOT);
        return null;
    }

    static boolean sameNumbers(String source, String output) {
        return numbers(source).equals(numbers(output));
    }

    private static Map<String, Integer> numbers(String text) {
        StringBuilder latin = new StringBuilder();
        for (int c : clean(text).codePoints().toArray()) {
            int digit = Character.digit(c, 10);
            if (digit >= 0) latin.append(digit);
            else if (c == 0x066C) { /* thousands separator */ }
            else if (c == 0x066B) latin.append('.');
            else if (c == 0x2212) latin.append('-');
            else latin.appendCodePoint(c);
        }
        String normalized = latin.toString().replaceAll("(?<=\\d),(?=\\d{3}(?:\\D|$))", "");
        Map<String, Integer> result = new LinkedHashMap<>();
        Matcher matcher = NUMBER.matcher(normalized);
        while (matcher.find()) result.merge(matcher.group(), 1, Integer::sum);
        return result;
    }

    String reviewHints(String source, String output, String sourceLanguage, String targetLanguage) {
        return config.enabled ? quality.review(source, output, sourceLanguage, targetLanguage) : "";
    }

    private static boolean strictlyFewerCues(String before, String after) {
        Set<String> first = new HashSet<>(Arrays.asList(before.split(",")));
        Set<String> second = new HashSet<>(Arrays.asList(after.split(",")));
        first.remove(""); second.remove("");
        return first.containsAll(second) && first.size() > second.size();
    }

    private static String addWarning(String current, String warning) {
        return warning.isEmpty() ? current : current.isEmpty() ? warning : current + "," + warning;
    }

    private static String lineKey(String text) {
        return clean(text).toLowerCase(Locale.ROOT).replaceAll("[.!…。！]+$", "").trim();
    }

    private static String withPunctuation(String target, String source) {
        String result = target;
        String trimmed = source.trim();
        if (trimmed.endsWith("!") && !result.matches(".*[.!?؟…]$")) result += "!";
        if ((trimmed.endsWith("?") || trimmed.endsWith("？")) && !result.matches(".*[?؟]$")) result += "؟";
        return result;
    }

    static String clean(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .replace('\u2019', '\'').replace('\u2018', '\'')
                .replace("\u00AD", "").replaceAll("\\s+", " ").trim();
    }

    private static boolean sameLanguage(String source, String target) {
        return baseLanguage(source).equals(baseLanguage(target));
    }

    private static String baseLanguage(String lang) {
        return lang == null ? "" : lang.toLowerCase(Locale.ROOT).split("-")[0];
    }

    private static void check(Progress progress, int done, int total) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Translation interrupted");
        if (progress != null) progress.onProgress(done, total);
    }

    private static void rethrowCancellation(Exception error) throws Exception {
        Throwable cause = error;
        for (int i = 0; cause != null && i < 8; i++, cause = cause.getCause()) {
            if (cause instanceof InterruptedException
                    || cause instanceof java.util.concurrent.CancellationException) throw error;
        }
        if (Thread.currentThread().isInterrupted()) throw error;
    }
}
