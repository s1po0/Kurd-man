# Story, genre and glossary rules

## Meaning before word substitution

- **AUTO**: select vocabulary from evidence; don't infer plot or speakers.
- **FANTASY**: game/system/rank terminology when supported by context. Distinguish
  player class from school class, raid party from birthday party, mana from money.
- **MARTIAL**: cultivation/sect/qi senses need martial context; rice cultivation
  and religious sects must not be forced into combat vocabulary.
- **EVERYDAY**: natural school/romance/everyday dialogue, not fantasy defaults.

Idioms need whole-line/context evidence. "Give me a break!" and "Give me a break
from work" are different cases. A phrase containing a term does not automatically
license rewriting the entire sentence. Names need spelling, not literal meaning.
Do not guess gender from English "you", Korean omitted subjects, Japanese honorifics
or a Chinese name. Ask/mark uncertain only when the missing fact is essential.

## Reader glossary = source of authority

Normal use is **Translate**. Optional names/genre expand inline; no mandatory
setup/save screen. One `original = preferred target` per line. The optional saved
project library can explicitly reuse a story's glossary for future jobs. The text exporter does NOT add a new
in-app file-import feature; validated text is pasted into that existing field.

- At most 100 entries; total text <=12,000 Java UTF-16 units.
- Source <=80 and target <=160 Java UTF-16 units after the runtime's cleanup.
- Blank lines and lines starting with `#` after trim are allowed.
- First `=` is the delimiter; later `=` characters belong to the target.
- Duplicate cleaned source keys are rejected case-insensitively.
- Reserved runtime markers `ZXQK` and `[[KX` are forbidden in entries.
- Same source/target language pair and same story only; clear before a new story.
- Keep the user's chosen target spelling, including Kurdish joining marks.
- Longest overlapping name/term wins. Latin matches need word boundaries;
  CJK names may have adjacent particles. Do not replace `raid` inside `braid`.
- Glossary failures must not silently become a confident translation. Preserve
  ordinary editable fallback and review hints; don't hide failure provenance.

The bundled validator mirrors these limits and is parity-tested against the
actual Java validator. The Android implementation remains the runtime authority.
The `check` command's preferred-term hint is only a review cue: morphology,
legitimate rephrasing and longest-match choices still need a language reviewer.
