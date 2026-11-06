# Meaning-first review rubric

## Review order

1. **Source reliability** — is OCR correct and the bubble complete? If not,
   mark OCR/uncertain; do not repair the story by imagining missing words.
2. **Propositions** — who acts, what happens, to whom, with what negation,
   condition, quantity, time/aspect and certainty? Preserve these before style.
3. **Context** — use only visible/explicit neighbouring dialogue and established
   story glossary. Separate what is observed from what is merely plausible.
4. **Natural target dialogue** — a line should sound like a speaker, not a
   word-by-word dictionary. Keep threat/joke/sarcasm/politeness and register.
   Do not explain an idiom at length inside a short bubble or add plot detail.
5. **Consistency** — reader-approved names and terms, ranks, relationships and
   address forms. Prefer one documented spelling per story/language pair.
6. **Structure** — same bubble IDs, per-bubble numbers and order. A valid JSON
   structure or preserved digit count is necessary evidence, not correct meaning.
7. **Visual** — inspect the rendered real page separately. RTL/shaping, font fit,
   original ink removal, black narration, bottom double bubbles, safe outside SFX.

## Required error categories

`fabrication`, `omission`, `negation`, `condition`, `roles`, `tense`, `tone`,
`name`, `term`, `number`, `ocr`, `structure`, `visual`, `other`.

Critical examples: reversing "don't", swapping who is being threatened, adding
an unseen relationship, turning "if" into a fact, moving HP from one bubble to
another. Fluent text with any such error is NOT an acceptable translation.

## Declared scores (0–4 each, never an automatic accuracy metric)

| Score | Meaning | Naturalness | Terminology |
| --- | --- | --- | --- |
| 0 | Missing/unusable | Missing/unreadable | Missing/unusable |
| 1 | Major change/invention | Mostly unnatural | Major name/term errors |
| 2 | Material uncertainty/error | Readable but literal/awkward | Inconsistent |
| 3 | Meaning preserved with minor issues | Natural enough for the scene | Consistent, minor issue |
| 4 | Careful source/context verification | Natural and faithful tone | Verified reader choices |

A `pass` needs all three >=3, no major/critical issue, and adequate source context.
A fluent line cannot compensate for a meaning error. A `revise` needs work;
`uncertain` explicitly lacks reliable evidence. `unreviewed` is not a decision.

Record reviewer kind as `same_agent`, `independent_agent` or `human`, truthfully.
The tool cannot verify identity or independence: its output labels all scores
as **declared assessments**, never authenticated/native-reviewed accuracy.
If the same agent generated and reviewed a line, disclose that limitation.
Do not fill a template with invented reviewer scores to make a gate green.

## Evaluation hygiene

- Compare baseline/candidate on the exact same source, context, glossary and mode.
- Blind A/B labels where feasible. Keep held-out cases out of rule tuning.
- Use multiple genres/languages and negative examples, not only curated idioms.
- The bundled 20 synthetic cases have no reference translations or scores.
  They exercise review scenarios, not a validated Sorani translation benchmark.
- Reports omit raw dialogue, glossary, names, evidence notes and file paths.
  Keep any detailed evidence privately with the exact reviewed packet.
- Even a complete human-labelled file is a declaration, not proof that the
  reviewer was qualified or independent. Real-device visual checks remain separate.
