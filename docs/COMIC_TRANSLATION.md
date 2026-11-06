# Manga / Manhwa skill (Koorix 1.3.0)

This is a **rule-and-glossary layer over the existing translators**, not a new
trained translation model, LLM prompt, semantic validator, or paid service.
No NLLB dependency, new model download, API key, or hidden online fallback was
added. The Sorani pack v2 and the other languages' ML Kit packs are unchanged.

## Reader controls

Translate → **Configure Manga / Manhwa** opens a separate screen:

- Enabled by default; disabling it restores individual, normal translation.
- Genre: Auto / Fantasy–RPG–Hunters / Martial arts–Murim / Everyday–School–Romance.
- Editable glossary: `original = preferred translation`, one entry per line,
  at most 100 entries. Source ≤80, target ≤160 characters; total ≤12,000.
  Blank lines and `#` comments are allowed. Duplicates/malformed entries are
  rejected, not silently ignored. Names should use the desired target-script
  spelling, for example `Jinwoo = جین‌وو` for Sorani.
- Glossary presets are scoped to the **source + target language pair**, can be
  cleared for a new story, and are snapshotted into `project.json` with the
  selected mode and genre. Changing settings never rewrites saved projects.
- Kurdish Sorani, English and Arabic UI strings are supplied for every new
  control, validation error and review hint.

## What actually changes translation

1. **204 curated entries** in `comic-lexicon-v1.tsv`: complete dialogue idioms
   and selected fantasy/RPG, Murim and honorific terms in English, Korean,
   Japanese and simplified/traditional Chinese, with Sorani renderings.
   This number is vocabulary coverage, **not a translation accuracy score**.
   Whole-line idioms are never substituted inside a different sentence.
2. Anchored numeric system-message templates preserve EXP, level, HP/MP and
   rank slots. Negated/conditional/longer sentences do not match a template.
3. Auto genre uses distinct, page-local lexical cues. It does not infer a
   speaker, gender, relationship or plot. Weak words such as `cultivation`
   and `sect` alone do not select Murim. Plain `class`, `party`, `core` and
   `tank` are deliberately not globally substituted. An explicit genre is
   preferable for a known story.
4. Matching terminology/names are longest-first, word-boundary aware for
   Latin words, and protected with reversible markers. Reader glossary
   entries take precedence over built-ins. Restoration requires every
   marker exactly once, no unknown/damaged marker, and unchanged numeric
   values. Invalid constrained responses are **discarded**, and the original
   bubble is translated normally. That fallback receives a terminology
   review hint; guessed translations are never relabelled as a glossary hit.
5. **Online only:** bounded windows of at most four neighbouring OCR bubbles
   (≤1,050 input characters including boundaries) share context in one
   translation request. The exact number, order, non-empty boundaries and
   each bubble's numbers must survive. Otherwise the entire window falls
   back to separate translations. A failed optimisation is disabled for the
   remainder of that job. Windows never cross a page boundary; no new bubble
   grouping, merging, geometry, font sizing, erasure or SFX behaviour was added.
6. **Offline:** always one bubble at a time. Local rules/glossary work without
   network access. If a pack cannot preserve terminology markers, only one
   such failure is attempted per job before using ordinary offline fallback.
   This avoids repeatedly paying for unusable native inference. The optional
   online context code cannot change an offline job into a network job.
7. Numbers, blatant repeated words and exact source echoes generate review
   hints. These hints persist in project segments, exported text and the
   editor. Ordinary fallback remains editable. No warning is **not** proof
   of correct meaning. Reports contain only counts/methods, not dialogue,
   character names, glossary contents or authentication secrets.

Built-in Sorani entries are never forced into another target language.
Other targets can still use online context and their own glossary.
Explicit reader spellings are protected during final script/name cleanup.
Old projects load with the skill disabled in their snapshot, without altering
any stored translation or layout.

## Tests

Run with Java 17+:

```sh
bash tools/comic-skill/test.sh
```

The same test is a required step **before APK build/publication** in
`koorix-apk.yml`. It compiles the actual Android-independent production skill
and tests the real lexicon, exact idioms, anti-substring guards, native scripts,
genre isolation, numeric slots, explicit overrides, repeated/overlapping names,
malformed glossaries, dropped/duplicated/truncated markers, missing/reordered
bubble IDs, per-bubble number movement, offline/no-batching, cross-page
isolation, other targets, disabled mode, review hints and STOP propagation.
Backends in these tests are deterministic mocks: this tests contracts/rules,
**not Google/OPUS semantic quality or Android UI behaviour**.

### Device acceptance still required

- Install over 1.2.4 without uninstalling; account, results and downloaded
  Sorani pack v2 should remain. No pack re-download is required for 1.3.0.
- Open the skill screen in all three UI languages. Save/clear a glossary,
  change target/source and confirm presets stay with the right pair.
- Translate a real fantasy/Murim page online and with airplane mode on.
  Judge meaning, pronouns and tone against the original; neither the lexicon
  nor a strict boundary parser can guarantee the model understood the story.
- Confirm adjacent bubbles, joined ovals, black narration and outside SFX
  retain their existing clean-erase/typesetting behaviour.
- Open the saved project after restarting, inspect any review hints in the
  editor/text output, and test STOP during a long batch.

Offline Sorani's limited model quality remains a known limitation. This layer
improves covered expressions and consistency; it does not make OPUS equivalent
to a high-quality contextual translation model.
