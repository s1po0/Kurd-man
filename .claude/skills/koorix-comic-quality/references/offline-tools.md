# Offline tools and formats

Python 3.10+ standard library only. All commands run locally without translating,
calling an API/model, reading environment secrets, following image paths or
executing dialogue. Start at the checkout root:

```sh
SKILL=.claude/skills/koorix-comic-quality
python3 "$SKILL/scripts/quality.py" self-check
python3 -m unittest discover -s "$SKILL/tests" -v
```

## Private inputs and outputs

Use `.private/koorix-quality/` for packets, notes and glossary exports. The tool
refuses sensitive output elsewhere inside a Git checkout, protected paths and
existing output files. Outside a checkout, explicit output files get private
permissions on POSIX. On Windows, use an owner-only folder: Python chmod does
not configure Windows ACLs. It does not overwrite inputs or add an app export/import UI.

## Prepare source

Synthetic unscored examples (four pages for each source, targets initially empty):

```sh
python3 "$SKILL/scripts/quality.py" fixtures --source-language en --target-language ckb --out .private/koorix-quality/source.json
```

Sources with bundled examples: `en`, `ko`, `ja`, `zh-CN`, `zh-TW`. The tool accepts
other well-formed target tags; it does not hide the app's other target languages.
These examples are neither translations nor a native-validated gold benchmark.

An explicitly supplied developer copy of the existing app's JSON:

```sh
python3 "$SKILL/scripts/quality.py" packet --project .private/koorix-quality/project.json --out .private/koorix-quality/source.json
```

It maps `sourceLanguage`, `targetLanguage`, `comicSkill`, page `segments`, and
segment `text`/`ckb`/`outsideText`. `ckb` is the saved target field for ALL targets.
It ignores titles, paths, geometry, styles and unknown/private metadata, never
opens source images, and never writes back to the project. It generates stable
`p0001` / per-page `b0001` IDs. No phone access or project-export UI is implied.

Packet shape (all listed fields required, no extra fields):

```json
{
  "schema": "koorix.packet.v1",
  "source_language": "en",
  "target_language": "ckb",
  "genre": "AUTO",
  "glossary": "",
  "pages": [{
    "id": "p0001",
    "genre": "FANTASY",
    "segments": [{"id": "b0001", "source": "HP: 25/100", "target": "", "kind": "dialogue"}]
  }]
}
```

Make a separate candidate, keeping every field except `target` unchanged.
Stable canonical IDs prevent duplicated/reordered IDs; source comparisons catch
swapping content while retaining IDs. Semantic reversals still require review.
Kinds: `unknown`, `dialogue`, `narration`, `sfx`. The adapter does not guess narration.

## Check contracts

```sh
python3 "$SKILL/scripts/quality.py" check --source .private/koorix-quality/source.json --candidate .private/koorix-quality/candidate.json --report .private/koorix-quality/contracts.json
```

Blocking: changed source/settings/kind/counts, invalid IDs, empty target, added
text for an empty source, leaked runtime markers. Review hints: per-bubble number,
ratio order or percent changes, missing preferred terms, source echo, unconfirmed
Arabic script for ckb/ar/fa, repeated words. Number-format/word-order differences
can be legitimate; no hint automatically rewrites or condemns a translation.
`contracts_clear` does **not** mean correct meaning, rendering or model accuracy.

The aggregate report includes only fixed codes, indices and counters, not text,
notes, glossary, file names or reader identifiers. Detailed files remain private. Page/segment indices are zero-based; `-1`
indicates a whole-packet finding.

## Validate an existing-screen glossary

```sh
python3 "$SKILL/scripts/quality.py" glossary --input .private/koorix-quality/glossary.txt --source-language en --target-language ckb --out .private/koorix-quality/validated-glossary.txt
```

The text is not changed; the output is not printed. Paste it into the existing
Manga/Manhwa settings for the selected story/language pair. This does not create
a new file-import control. Limits count Java UTF-16 units, not Python characters.
`assets/glossary-contract-cases.json` is compared against the actual Java app
validator by `tests/GlossaryParity.java` in CI, including emoji/NFKC/limits.

## Review meaning (not automatic)

```sh
python3 "$SKILL/scripts/quality.py" review-template --candidate .private/koorix-quality/candidate.json --out .private/koorix-quality/review.json
# A real reviewer must fill the file using meaning-review.md; no auto-pass.
python3 "$SKILL/scripts/quality.py" score --candidate .private/koorix-quality/candidate.json --review .private/koorix-quality/review.json --report .private/koorix-quality/review-summary.json
```

The template is bound to the exact canonical candidate hash. Edits invalidate
old reviews. Record reviewer kind truthfully: `same_agent`, `independent_agent`,
`human`. The tool cannot authenticate identity, competence or independence.
Every nonempty source segment needs one ordered review. Default is `unreviewed`.
A declared pass requires sufficient context, all three 0–4 scores >=3, a nonempty
target, and no major/critical issue. Missing coverage, stale/duplicate rows or
unsupported passes are rejected. Notes/issue evidence never enter the summary.
`contract_status` remains separate; pending number/term hints are not erased by
reviewer scores, and they keep the CLI exit at 2. Even `all_declared_pass` is only
a summary of supplied labels, not proof of semantic accuracy, an independent review or release/device acceptance.

## Portable bundle

```sh
python3 "$SKILL/scripts/quality.py" bundle --out skill-download/koorix-comic-quality-v1.0.0.zip
```

The ZIP contains only an exact public-resource allowlist plus file hashes. It
never recursively walks the repo or includes `.private`, signing, credentials,
source pages, local reviews or APKs. Common private-key/token markers are
rejected before writing a ZIP; this is not a general content/privacy classifier,
so keep the allowlisted source resources public and synthetic. No API key is
requested. The included license covers this skill, not other project/upstream code. Keep generated ZIPs out of Git.

Exit codes: `0` operation/declared checks completed; `1` invalid input or I/O;
`2` blocked, pending or needs review. Inspect report meaning carefully: a zero
exit is never an automatic semantic/device-quality certificate.
