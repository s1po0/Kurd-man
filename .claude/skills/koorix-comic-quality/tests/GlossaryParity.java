package com.kurdman.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** Compares public synthetic fixtures with the actual app's parser, no network. */
public final class GlossaryParity {
    public static void main(String[] args) throws Exception {
        int checks = 0;
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 2) throw new AssertionError("Invalid parity fixture");
            int expected = Integer.parseInt(fields[0]);
            String text = new String(Base64.getDecoder().decode(fields[1]), StandardCharsets.UTF_8);
            int actual;
            try {
                actual = ComicTranslationSkill.validateGlossary(text);
            } catch (ComicTranslationSkill.GlossaryException invalid) {
                actual = -1;
            }
            if (actual != expected) throw new AssertionError("Glossary contract mismatch at fixture index " + checks);
            checks++;
        }
        if (checks < 30) throw new AssertionError("Incomplete glossary parity run");
        System.out.println("Koorix skill: " + checks + " glossary fixtures match the actual Java app validator; no network/model.");
    }
}
