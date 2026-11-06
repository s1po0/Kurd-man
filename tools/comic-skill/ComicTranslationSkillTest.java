package com.kurdman.app;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** No network, Android runtime, paid API or model download needed. */
public final class ComicTranslationSkillTest {
    private static int checks;
    private static List<ComicTranslationSkill.Entry> lexicon;
    private static final ComicTranslationSkill.Backend NO_BACKEND = (text, source, target) -> {
        throw new AssertionError("Unexpected backend request: " + text);
    };

    public static void main(String[] args) throws Exception {
        lexicon = ComicTranslationSkill.readLexicon(Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8));
        check(lexicon.size() >= 180, "substantial curated vocabulary");
        testEveryEntry();
        testIdioms();
        testGenreBoundaries();
        testNumericTemplates();
        testGlossary();
        testGlossaryFailures();
        testContext();
        testAdversarialBoundaries();
        testIsolationAndOtherLanguages();
        testReviewAndCancellation();
        System.out.println("Comic skill: " + checks + " assertions passed; " + lexicon.size()
                + " lexicon entries. Safety/rule tests only, NOT a semantic accuracy benchmark.");
    }

    private static ComicTranslationSkill skill(ComicTranslationSkill.Genre genre, String glossary) throws Exception {
        return new ComicTranslationSkill(new ComicTranslationSkill.Config(true, genre, glossary), lexicon);
    }

    private static ComicTranslationSkill.PageResult page(ComicTranslationSkill skill, boolean offline,
            String language, String target, ComicTranslationSkill.Backend backend, String... texts) throws Exception {
        return skill.translatePage(Arrays.asList(texts), language, target, offline, backend, null);
    }

    private static String one(ComicTranslationSkill skill, String text) throws Exception {
        return page(skill, true, "en", "ckb", NO_BACKEND, text).results.get(0).text;
    }

    private static void testEveryEntry() throws Exception {
        for (ComicTranslationSkill.Entry entry : lexicon) {
            ComicTranslationSkill.Genre genre = entry.domain.equals("all")
                    ? ComicTranslationSkill.Genre.EVERYDAY
                    : ComicTranslationSkill.Genre.valueOf(entry.domain.toUpperCase(java.util.Locale.ROOT));
            ComicTranslationSkill.PageResult page = page(skill(genre, ""), true, entry.language, "ckb", NO_BACKEND, entry.source);
            equal(entry.target, page.results.get(0).text, "exact curated entry " + entry.source);
            equal(1, page.localRules, "curated entry records provenance");
        }
        expect(IOException.class, () -> ComicTranslationSkill.readLexicon(new StringReader("bad\tline")));
    }

    private static void testIdioms() throws Exception {
        ComicTranslationSkill s = skill(ComicTranslationSkill.Genre.AUTO, "");
        equal("تا من زیندووم، ڕێگەی پێ نادەم!", one(s, "OVER MY DEAD BODY!"), "idiom, not literal corpse");
        equal("من پشتت دەگرم.", one(s, "I’ve got your back!"), "curly apostrophe normalization");
        equal("بیهێڵە بۆ من.", one(s, "Leave\n it to me..."), "OCR whitespace, ellipsis");
        equal("بەسمە ئیتر!", one(s, "Give me a break!"), "comic idiom, not rest time");
        equal("بەخۆتدا بێرەوە!", page(s, true, "ko", "ckb", NO_BACKEND, "정신 차려!").results.get(0).text, "native Korean idiom");
        equal("باوەڕم پێ ناکرێت!", page(s, true, "ja", "ckb", NO_BACKEND, "信じられない！").results.get(0).text, "native Japanese + fullwidth punctuation");
        AtomicInteger calls = new AtomicInteger();
        ComicTranslationSkill.Backend normal = (text, source, target) -> { calls.incrementAndGet(); return "ئەمە وەرگێڕانی ئاساییە."; };
        page(s, true, "en", "ckb", normal, "He said over my dead body yesterday.", "Give me a break from work.");
        equal(2, calls.get(), "idioms must not be substring-replaced");
    }

    private static void testGenreBoundaries() throws Exception {
        equal(ComicTranslationSkill.Genre.FANTASY,
                ComicTranslationSkill.inferGenre(Arrays.asList("The dungeon is open.", "My mana is low.")), "page cues across bubbles");
        equal(ComicTranslationSkill.Genre.MARTIAL,
                ComicTranslationSkill.inferGenre(Arrays.asList("Focus on your dantian.")), "strong martial cue");
        equal(ComicTranslationSkill.Genre.EVERYDAY,
                ComicTranslationSkill.inferGenre(Arrays.asList("Rice cultivation is difficult.", "Our class starts now.")), "no farm/school combat assumptions");
        equal(ComicTranslationSkill.Genre.EVERYDAY,
                ComicTranslationSkill.inferGenre(Arrays.asList("The religious sect is nearby.")), "sect alone is not Murim");
        equal(ComicTranslationSkill.Genre.EVERYDAY,
                ComicTranslationSkill.inferGenre(Arrays.asList("dungeon dungeon dungeon")), "repetition is not independent evidence");
        equal("ئاستەکەت بەرز بووەوە!", one(skill(ComicTranslationSkill.Genre.AUTO, ""), "You have leveled up!"), "unambiguous system line selects fantasy");
        equal("خاڵەکانی توانا", one(skill(ComicTranslationSkill.Genre.AUTO, ""), "SKILL POINTS"), "unambiguous compound");
        AtomicInteger masks = new AtomicInteger();
        ComicTranslationSkill.Backend b = (text, source, target) -> {
            if (text.contains("ZXQK")) masks.incrementAndGet();
            return "وەرگێڕانی ئاسایی";
        };
        page(skill(ComicTranslationSkill.Genre.FANTASY, ""), true, "en", "ckb", b,
                "Class starts now.", "My cooking skill improved.", "They planned a birthday party.", "Rice cultivation is hard.");
        equal(0, masks.get(), "ambiguous words are not blanket-glossaried even in a fantasy story");
        page(skill(ComicTranslationSkill.Genre.EVERYDAY, ""), true, "en", "ckb", b, "My mana crystal glows.");
        equal(0, masks.get(), "explicit everyday genre opts out of fantasy terms");
    }

    private static void testNumericTemplates() throws Exception {
        ComicTranslationSkill s = skill(ComicTranslationSkill.Genre.FANTASY, "");
        equal("1,250 خاڵی ئەزموونت وەرگرت.", one(s, "You have gained 1,250 experience points!"), "EXP numeric slot");
        equal("خاڵی تەندروستی: 25/100", one(s, "HP: 25/100"), "HP ratio");
        equal("خاڵی مانا: 2/45", one(s, "MP: 2 / 45"), "MP ratio");
        equal("ئاستی 12", one(s, "Level 12"), "level label");
        equal("پلەی S", one(s, "Rank S"), "rank letter is a code, not a personal name");
        equal(null, ComicTranslationSkill.systemMessage("You have not gained 12 experience points."), "must not reverse negation");
        equal(null, ComicTranslationSkill.systemMessage("If you gained 12 experience points, leave."), "anchored whole-line template only");
        equal(null, ComicTranslationSkill.systemMessage("Level 12 enemies are approaching."), "no partial numeric template");
        check(ComicTranslationSkill.sameNumbers("25/100 HP and +5 MP", "٢٥/١٠٠ و +٥"), "Arabic digits, same values");
        check(ComicTranslationSkill.sameNumbers("1,250 and 0.5", "١٬٢٥٠ و ٠٫٥"), "grouping/decimal separators");
        check(!ComicTranslationSkill.sameNumbers("25/100 HP", "25/10"), "missing zero detected");
        check(!ComicTranslationSkill.sameNumbers("+5", "-5"), "numeric sign detected");
        check(!ComicTranslationSkill.sameNumbers("5 5", "5"), "duplicate numbers counted");
    }

    private static void testGlossary() throws Exception {
        equal(2, ComicTranslationSkill.validateGlossary("# names\nJinwoo = جین‌وو\nMana = وزە"), "comments, entry count");
        ComicTranslationSkill s = skill(ComicTranslationSkill.Genre.FANTASY, "Jinwoo = جین‌وو\nMana = وزە");
        equal("وزە!", one(s, "MANA!"), "reader choice overrides built-in mana");
        ComicTranslationSkill.Backend preserving = (text, source, target) -> text.replace(" is here.", " لێرەیە.");
        ComicTranslationSkill.PageResult p = page(s, true, "en", "ckb", preserving, "Jinwoo is here.");
        equal("جین‌وو لێرەیە.", p.results.get(0).text, "custom name restored at exact position");
        equal("comic_glossary", p.results.get(0).method, "glossary provenance");
        equal("", p.results.get(0).warnings, "intact placeholder contract");
        p = page(s, true, "en", "ckb", (text, source, target) -> text.toLowerCase(java.util.Locale.ROOT), "Jinwoo and Jinwoo");
        equal("جین‌وو and جین‌وو", p.results.get(0).text, "same term repeated twice");
        p = page(skill(ComicTranslationSkill.Genre.AUTO, "진우 = جین‌وو"), true, "ko", "ckb",
                (text, source, target) -> text, "진우진우");
        equal("جین‌ووجین‌وو", p.results.get(0).text, "adjacent CJK occurrences are not collapsed");
        p = page(skill(ComicTranslationSkill.Genre.FANTASY, "monarch = شا\nShadow Monarch = پاشای سێبەرەکان"), true,
                "en", "ckb", (text, source, target) -> text, "Shadow Monarch appears.");
        equal("پاشای سێبەرەکان appears.", p.results.get(0).text, "longest custom phrase wins overlap");
        equal("Jin-Woo لێرەیە.", skill(ComicTranslationSkill.Genre.AUTO, "Jinwoo=Jin-Woo")
                .formatPreservingGlossary("Jin-Woo is here.", value -> value.replace("Jin-Woo", "WRONG").replace("is here", "لێرەیە")),
                "explicit reader spelling survives final name/script cleanup");
        List<String> inputs = new ArrayList<>();
        page(skill(ComicTranslationSkill.Genre.FANTASY, "raid = هێرش"), true, "en", "ckb",
                (text, source, target) -> { inputs.add(text); return "وەرگێڕان"; }, "Her braid is long.");
        equal(Collections.singletonList("Her braid is long."), inputs, "Latin word boundary: raid != braid");
        for (String invalid : Arrays.asList("no separator", " = target", "a =", "a=x\nA=y", "a=ZXQK0000QXZ", "[[KX0000]]=x")) {
            expect(ComicTranslationSkill.GlossaryException.class, () -> ComicTranslationSkill.validateGlossary(invalid));
        }
        StringBuilder tooMany = new StringBuilder();
        for (int i = 0; i < 101; i++) tooMany.append("name").append(i).append("=ناو\n");
        expect(ComicTranslationSkill.GlossaryException.class, () -> ComicTranslationSkill.validateGlossary(tooMany.toString()));
    }

    private static void testGlossaryFailures() throws Exception {
        for (String bad : Arrays.asList("جین‌وو لێرەیە", "ZXQK0000QXZ ZXQK0000QXZ", "ZXQK0000QXZ ZXQK9999QXZ", "ZXQK0000QX")) {
            AtomicInteger calls = new AtomicInteger();
            ComicTranslationSkill.PageResult p = page(skill(ComicTranslationSkill.Genre.AUTO, "Jinwoo = جین‌وو"), true,
                    "en", "ckb", (text, source, target) -> {
                        calls.incrementAndGet();
                        return text.contains("ZXQK") ? bad : "ئەو لێرەیە.";
                    }, "Jinwoo is here.");
            equal("ئەو لێرەیە.", p.results.get(0).text, "damaged/duplicate/unknown/truncated marker fallback");
            equal(2, calls.get(), "one constrained attempt + original-source fallback");
            equal("glossary_unconfirmed", p.results.get(0).warnings, "fallback cannot claim glossary applied");
        }
        ComicTranslationSkill.PageResult selfName = page(skill(ComicTranslationSkill.Genre.AUTO, "John=John Smith"), true,
                "en", "ar", (text, source, target) -> text.contains("ZXQK") ? "broken" : text, "John and John");
        equal("John Smith and John Smith", selfName.results.get(0).text, "echo fallback cannot repeatedly expand a name");
        ComicTranslationSkill.PageResult crossNames = page(skill(ComicTranslationSkill.Genre.AUTO, "Alpha=Beta\nBeta=Gamma"), true,
                "en", "ar", (text, source, target) -> text.contains("ZXQK") ? "broken" : text, "Alpha and Beta");
        equal("Beta and Gamma", crossNames.results.get(0).text, "echo fallback is simultaneous, not cascading");
        AtomicInteger masked = new AtomicInteger();
        ComicTranslationSkill s = skill(ComicTranslationSkill.Genre.AUTO, "Jinwoo = جین‌وو");
        ComicTranslationSkill.Backend b = (text, source, target) -> {
            if (text.contains("ZXQK")) { masked.incrementAndGet(); return "ناونیشانی هەڵە"; }
            return "ئەو لێرەیە.";
        };
        page(s, true, "en", "ckb", b, "Jinwoo is here.", "Jinwoo is there.");
        equal(1, masked.get(), "do not repeatedly waste offline inference on an unsupported marker format");
        ComicTranslationSkill.PageResult p = page(skill(ComicTranslationSkill.Genre.AUTO, "Jinwoo = جین‌وو"), true,
                "en", "ckb", (text, source, target) -> text.contains("ZXQK") ? "ZXQK0000QXZ: 2" : "جین‌وو: 20", "Jinwoo: 20");
        equal("جین‌وو: 20", p.results.get(0).text, "dropped number invalidates constrained result");
        expect(IOException.class, () -> page(skill(ComicTranslationSkill.Genre.AUTO, ""), true, "en", "ckb",
                (text, source, target) -> "ZXQK9999QXZ", "Hello there."));
    }

    private static void testContext() throws Exception {
        List<String> requests = new ArrayList<>();
        ComicTranslationSkill.PageResult p = page(skill(ComicTranslationSkill.Genre.AUTO, ""), false, "en", "ckb",
                (text, source, target) -> {
                    requests.add(text);
                    return "[[KX0000]] جین‌وو لە کوێیە؟ [[KX0001]] ئەو لە ناوەوەیە.";
                }, "Where is Jinwoo?", "He is inside.");
        equal(1, requests.size(), "neighbouring dialogue shared in one request");
        check(requests.get(0).contains("Where is Jinwoo?") && requests.get(0).contains("He is inside."), "both original bubbles in context");
        equal(2, p.results.size(), "one result per bubble");
        equal("جین‌وو لە کوێیە؟", p.results.get(0).text, "bubble one identity");
        equal("ئەو لە ناوەوەیە.", p.results.get(1).text, "bubble two identity");
        equal("comic_context", p.results.get(1).method, "context provenance");
        p = page(skill(ComicTranslationSkill.Genre.AUTO, "Jinwoo = جین‌وو"), false, "en", "ckb",
                (text, source, target) -> "[[kx0000]] ZXQK0000QXZ هات. [[kx0001]] ئەو لێرەیە.",
                "Jinwoo arrived.", "He is here.");
        equal("جین‌وو هات.", p.results.get(0).text, "glossary restored inside context window");
        AtomicInteger windows = new AtomicInteger();
        page(skill(ComicTranslationSkill.Genre.AUTO, ""), false, "en", "ckb", (text, source, target) -> {
            windows.incrementAndGet();
            java.util.regex.Matcher markers = java.util.regex.Pattern.compile("\\[\\[KX[0-9]{4}]]").matcher(text);
            StringBuilder response = new StringBuilder();
            int count = 0;
            while (markers.find()) { response.append(markers.group()).append(" وەرگێڕان "); count++; }
            check(count <= 4, "context window is bounded to four bubbles");
            return response.toString();
        }, "one", "two", "three", "four", "five", "six", "seven", "eight");
        equal(2, windows.get(), "eight bubbles form two bounded windows");
        AtomicInteger requestsCount = new AtomicInteger();
        p = page(skill(ComicTranslationSkill.Genre.AUTO, ""), true, "en", "ckb",
                (text, source, target) -> { check(!text.contains("[[KX"), "offline never uses a context batch"); requestsCount.incrementAndGet(); return "وەرگێڕان"; },
                "Where is Jinwoo?", "He is inside.");
        equal(2, requestsCount.get(), "offline stays per bubble");
        equal(0, p.contextRequests, "offline context requests = 0");
    }

    private static void testAdversarialBoundaries() throws Exception {
        for (String bad : Arrays.asList("no markers", "[[KX0000]] first", "[[KX0001]] second [[KX0000]] first",
                "[[KX0000]] first [[KX0000]] second", "[[KX0000]] first [[KX0001]] second [[KX0002]] third",
                "prefix [[KX0000]] first [[KX0001]] second", "[[KX0000]] [[KX0001]] second",
                "[[KX0000]] first [[KX0001]] second [[KX999]] broken")) {
            equal(null, ComicTranslationSkill.splitWindow(bad, 2), "strict parser rejects bad alignment");
            AtomicInteger calls = new AtomicInteger();
            ComicTranslationSkill.PageResult p = page(skill(ComicTranslationSkill.Genre.AUTO, ""), false, "en", "ckb",
                    (text, source, target) -> { calls.incrementAndGet(); return text.contains("[[KX") ? bad : "وەرگێڕانی " + text; },
                    "first", "second");
            equal(3, calls.get(), "failed context request falls back to two separate requests");
            equal("وەرگێڕانی first", p.results.get(0).text, "first bubble not replaced by second");
            equal("وەرگێڕانی second", p.results.get(1).text, "second bubble not replaced by first");
            equal(1, p.contextFallbacks, "alignment fallback recorded");
        }
        // Marker count can be correct but the number in one bubble moved to another.
        ComicTranslationSkill.PageResult p = page(skill(ComicTranslationSkill.Genre.AUTO, ""), false, "en", "ckb",
                (text, source, target) -> text.contains("[[KX")
                        ? "[[KX0000]] 10 هێرش [[KX0001]] 20 بەرگری" : text,
                "Attack 20", "Defense 10");
        equal("Attack 20", p.results.get(0).text, "per-bubble number contract rejects plausible wrong alignment");
        equal(1, p.contextFallbacks, "number movement causes fallback");
        equal(null, ComicTranslationSkill.splitWindow("", 0), "empty parser request is safe");
    }

    private static void testIsolationAndOtherLanguages() throws Exception {
        ComicTranslationSkill s = skill(ComicTranslationSkill.Genre.AUTO, "");
        page(s, true, "en", "ckb", (text, source, target) -> text, "dungeon", "mana");
        ComicTranslationSkill.PageResult p = page(s, true, "en", "ckb", (text, source, target) -> "وەرگێڕان", "Our class starts now.");
        equal(ComicTranslationSkill.Genre.EVERYDAY, p.genre, "no previous-page genre leakage");
        p = page(s, true, "en", "ar", (text, source, target) -> "هذا حوار", "Over my dead body!");
        equal("هذا حوار", p.results.get(0).text, "built-in Sorani rules never override another target");
        p = page(skill(ComicTranslationSkill.Genre.FANTASY, "Jinwoo = جين وو"), true, "en", "ar", NO_BACKEND, "Jinwoo");
        equal("جين وو", p.results.get(0).text, "custom glossary supports Arabic targets");
        p = page(skill(ComicTranslationSkill.Genre.AUTO, "Back = سۆرانی"), true, "en", "en", NO_BACKEND, "Back");
        equal("Back", p.results.get(0).text, "same-language re-lettering is not changed");
        ComicTranslationSkill off = new ComicTranslationSkill(new ComicTranslationSkill.Config(false,
                ComicTranslationSkill.Genre.FANTASY, "Jinwoo=ناو"), lexicon);
        AtomicInteger calls = new AtomicInteger();
        p = page(off, false, "en", "ckb", (text, source, target) -> {
            calls.incrementAndGet(); check(!text.contains("ZXQK") && !text.contains("[[KX"), "disabled skill sends plain sources"); return "وەرگێڕانی ئاسایی";
        }, "Over my dead body!", "Jinwoo is here.");
        equal(2, calls.get(), "disabled skill uses individual standard requests");
        equal("standard", p.results.get(0).method, "standard provenance");
        p = page(s, false, "en", "ckb", NO_BACKEND, "", "   ");
        equal(2, p.results.size(), "empty inputs preserve alignment without requests");
    }

    private static void testReviewAndCancellation() throws Exception {
        ComicTranslationSkill.PageResult p = page(skill(ComicTranslationSkill.Genre.AUTO, ""), true, "en", "ckb",
                (text, source, target) -> "10 خاڵ", "20 points remain.");
        check(p.results.get(0).warnings.contains("numbers"), "ordinary fallback number mismatch remains visible");
        p = page(skill(ComicTranslationSkill.Genre.AUTO, ""), true, "en", "ckb",
                (text, source, target) -> "نا نا نا نا", "We should leave.");
        check(p.results.get(0).warnings.contains("repetition"), "obvious repetition review hint");
        p = page(skill(ComicTranslationSkill.Genre.AUTO, ""), true, "en", "ckb",
                (text, source, target) -> text, "We should leave.");
        check(p.results.get(0).warnings.contains("untranslated"), "echo review hint");
        expect(InterruptedException.class, () -> skill(ComicTranslationSkill.Genre.AUTO, "").translatePage(
                Arrays.asList("first", "second"), "en", "ckb", false, NO_BACKEND,
                (done, total) -> { throw new InterruptedException("STOP"); }));
        AtomicInteger calls = new AtomicInteger();
        expect(IOException.class, () -> page(skill(ComicTranslationSkill.Genre.AUTO, ""), false, "en", "ckb",
                (text, source, target) -> { calls.incrementAndGet(); throw new IOException("wrapped STOP", new InterruptedException("STOP")); },
                "first", "second"));
        equal(1, calls.get(), "wrapped cancellation must not cause fallback requests");
        AtomicInteger completed = new AtomicInteger();
        skill(ComicTranslationSkill.Genre.AUTO, "").translatePage(Arrays.asList("first", "second", "third", "fourth", "fifth"),
                "en", "ckb", true, (text, source, target) -> "وەرگێڕان", (done, total) -> {
                    check(done >= completed.get() && done <= total, "monotonic progress"); completed.set(done);
                });
        equal(5, completed.get(), "final progress");
    }

    private interface Task { void run() throws Exception; }
    private static void expect(Class<? extends Exception> type, Task task) throws Exception {
        try { task.run(); }
        catch (Exception e) { check(type.isInstance(e), "expected " + type.getName() + " got " + e); return; }
        throw new AssertionError("Expected " + type.getName());
    }
    private static void equal(Object expected, Object actual, String label) {
        check(java.util.Objects.equals(expected, actual), label + ": expected <" + expected + "> got <" + actual + ">");
    }
    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
