package com.kurdman.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Advisory real-route smoke probe, never an automatic semantic quality score. */
public final class LiveProbe {
    public static void main(String[] args) throws Exception {
        ComicTranslationSkill.Backend backend = (text, source, target) -> {
            Process process = new ProcessBuilder("python3", "tools/comic-probe/backend.py", source, target).start();
            process.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new java.io.IOException("public route timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new java.io.IOException(error);
            }
            return output;
        };
        try {
            List<String> sources = Arrays.asList("Jinwoo, bring the mana crystal.", "The guild master is waiting for us.");
            String first = backend.translate(sources.get(0), "en", "ckb");
            String second = backend.translate(sources.get(1), "en", "ckb");
            ComicTranslationSkill skill = new ComicTranslationSkill(new ComicTranslationSkill.Config(true,
                    ComicTranslationSkill.Genre.FANTASY, "Jinwoo = جین‌وو"),
                    ComicTranslationSkill.readLexicon(Files.newBufferedReader(
                            Path.of("android/app/src/main/assets/comic-lexicon-v1.tsv"), StandardCharsets.UTF_8)));
            ComicTranslationSkill.PageResult page = skill.translatePage(sources, "en", "ckb", false, backend, null);
            notice("Live public-route probe: synthetic samples only; NOT a semantic accuracy score");
            notice("BASELINE_1=" + first);
            notice("SKILL_1=" + page.results.get(0).text + " method=" + page.results.get(0).method + " review=" + page.results.get(0).warnings);
            notice("BASELINE_2=" + second);
            notice("SKILL_2=" + page.results.get(1).text + " method=" + page.results.get(1).method + " review=" + page.results.get(1).warnings);
            notice("CONTEXT_REQUESTS=" + page.contextRequests + " CONTEXT_FALLBACKS=" + page.contextFallbacks + " GLOSSARY_FALLBACKS=" + page.glossaryFallbacks);
        } catch (Exception unavailable) {
            System.out.println("::warning::Advisory live route probe unavailable: " + safe(unavailable.toString()));
        }
    }
    private static String safe(String value) {
        return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
    }
    private static void notice(String text) { System.out.println("::notice::" + safe(text)); }
}
