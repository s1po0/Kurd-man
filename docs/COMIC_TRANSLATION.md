# Manga / Manhwa skill (regular Koorix 1.3.1)

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

- **One-time old-key migration:** export needed results from the old app first.
  The owner authorized a new key for 1.3.1, which cannot install over 1.2.4.
  Uninstall deletes private projects, settings and packs; no automatic migration
  is claimed or performed. After safe export/reinstall, test later v2-signed
  updates in place and confirm their settings/projects/downloads survive.
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

## Production distribution and permanent signing (v2)

The owner rejected the separately delivered Preview and explicitly authorized
**a new durable key for regular Koorix**. The Preview workflow/request and its
Gradle application-ID variant are removed. It is not an accepted production
release or the delivery path. The normal app keeps `com.koorix.app`, label Koorix,
version **1.3.1 / code 9**, and uses a non-debuggable release build.

New production certificate (RSA-3072, valid through 2126-11-06):

```
SHA-256 fb304de3cd357f35cf679e2a46befac08f3e97feef28b1b9a8af7b0891d40340
SHA-1   21209f6f75195cfeb1dcaa7b7d74ad09e4954b6b
```

This is NOT recovery of the old certificate
`48c50cf5a99fcde5bbff27f586d520095e10276b4c705236e708ca261c1cca35`.
Android cannot update an old-certificate installation with this new key.
No uninstall or private-data deletion is automated. Export needed results
before choosing the one-time reinstall; private editable projects, settings
and downloaded packs do not automatically migrate. Future regular releases
must restore the same v2 key and increment versionCode.

Bootstrap CI `42144904867` passed 18 crypto recovery/fail-closed tests and Java 17
PKCS12 import. Only authenticated ciphertext/public metadata left that job.
An independent owner-envelope recovery without the CI secret was then actually
performed locally and imported through Java keytool. The complete recovery
archive was uploaded to an **unshared private Dropbox folder**, with no public
link; Dropbox server content-hash metadata matches the verified local archive.
The sandbox did not download the private ZIP back from Dropbox; do not confuse
server hash verification with a separate cloud-download test.

Normal production CI has **no signing cache and no key-generation fallback**.
It restores the committed AES-GCM encrypted key twice from cold storage,
validates the backup record, pins and Java alias/certificate, builds only the
regular release, then verifies actual APK signer/package/version/label and
non-debuggable status before blob, GitHub Release and Telegram delivery.
Private signing material is never put into Git, public releases, chat or
Actions artifacts. Full storage, wrapper-secret dependency/rotation and
owner recovery: [`tools/signing/README.md`](../tools/signing/README.md).

### Verified regular APK delivery

CI **42146247653** (`a83ca13`) succeeded: 18 crypto tests, 563 rule/safety
assertions, two cold signer restores, full release compilation, actual
package/version/label/non-debuggable and `apksigner` checks, regular GitHub Release,
and acknowledged Telegram APK upload. The actual APK was also presented in chat.

* `Koorix-v1.3.1.apk`: **33,583,399 bytes**, 608 ZIP entries, ZIP integrity verified.
* SHA-256: `4d05d6a6ef9e21bc0a124143e7ced2b3d85352fb400a238685a91d0e5987b51b`.
* Downloaded file independently passed **v2 RSA signature and chunked APK-content
  digest verification** locally against the pinned public certificate. Entries
  were checked for the actual private PKCS12/password/recovery PEM: none present.
* Stable URL: https://github.com/s1po0/Kurd-man/releases/download/koorix-v1.3.1/Koorix-v1.3.1.apk
  (private repository access required; chat/Telegram files are separate delivery).
* The tag was first created by successful run `42146159673` at `8bba478`.
  The final asset above is from `a83ca13`; **Android and signing trees are exactly
  identical** between those commits, only the CI diagnostic script differs.
* `koorix/releases/v1.3.1.json` records precise artifact/delivery evidence.
  Real-page semantic and device visual acceptance are not implied by these checks.

Historical 1.3.0 runs passed compilation/rule tests but were blocked by loss of
the original cache-only key. The earlier Preview was an unauthorized substitute
and is superseded, not a claimed solution to the original update requirement.
Actual real-page semantic and device visual acceptance is still required.

### Actual free-route smoke probe

Advisory run `42141822128` compiled the production comic skill and used the live
public Google route with two synthetic English dialogue bubbles. It preserved
both bubble boundaries and all protected terms: 1 context request, 0 context
fallbacks, 0 glossary fallbacks. Observed examples:

| Source | Standard route | Comic skill |
| --- | --- | --- |
| Jinwoo, bring the mana crystal. | جینوو، کریستاڵەکەی مانا بهێنە. | جین‌وو، کریستاڵی مانا بهێنە. |
| The guild master is waiting for us. | وەستای گیڵد چاوەڕێمان دەکات. | سەرۆکی گیلد چاوەڕێمان دەکات. |

This establishes that the real route accepted the boundary/terminology
contract on those examples, not that all comic dialogue is semantically
correct. `tools/comic-probe` is advisory research tooling, uses no reader data
or API key, and is not part of the app or an offline/network fallback.


## 1.4.0 — Koorix Skill inside the normal app

The standalone AgentSkill remains a developer resource, but readers no longer
need it, Python or Claude Code to use the practical review workflow:
**Translate / Results → Open Koorix Skill**. This is a native screen in
`com.koorix.app`, not a Preview or another application.

- Deterministic checks run on the phone: per-block number/ratio order/percent,
  preferred-name occurrence counts (longest match, word boundaries), internal
  markers, empty targets, source echoes, script, repetition and low OCR confidence.
- Read originals, targets and neighbouring dialogue on the same page; edit a
  target, choose a reader-declared assessment, or open that exact page/block in
  the existing visual editor. Export there to regenerate images/CBZ/PDF.
- Remember a chosen name or short phrase. Optional bulk correction touches only
  **identical whole source blocks**, never substrings/guessed target synonyms.
  Geometry, erasure, styles, hidden blocks and the original reset wording stay.
- Story setup can target the saved project’s frozen language pair. Future presets
  are separate and require an explicit checkbox. No cross-language glossary leak.
- Reader decisions start unreviewed, survive project save/restart, and become
  stale after relevant wording, neighbouring context, language or story-setting
  edits. A reader label is not reviewer authentication or a semantic score.
- Snapshot writes reject an older screen if a new translation replaced the latest
  project. No additional raw dialogue/glossary is sent to diagnostics or a service.

Implementation: `ComicQualityChecks` (pure Java), `ComicQualityReview` (project
adapter) and `ComicQualityActivity` (reader UI). The existing Android-independent
contract suite is extended. `ComicQualityAndroidTest` tests native Android views,
editing, filters, story-memory isolation and restart persistence under Robolectric
at API 26 and 34, before production signing/distribution. These are Android/JVM
contracts, **not real-device visual acceptance or native-language accuracy**.

No paid API, agent-service call, extra translation model or NLLB is added. The
existing translator, selected online/offline mode, account quotas, cancellation
and v2 signer remain authoritative. General offline Sorani meaning limitations
remain; no-warning/checked-by-reader never means every meaning is correct.

### Verified 1.4.0 delivery (2026-11-06)

Regular release run [`42155104001`](https://github.com/s1po0/Kurd-man/actions/runs/42155104001)
passed at `2d37a41e589eada0ceebb70bab004ff93bb2dfa4`, the exact release-tag commit:
18 signing-recovery tests, 567 existing rule/safety assertions, 51 new local
quality/glossary/binding assertions and **28 Android/JVM tests, zero failures,
errors or skips** (14 methods at API 26 and 34). The independent skill run
`42155103736` passed 55 Python tests on each of 3.11/3.14 and 36 Java glossary
parity fixtures. These use synthetic data and deterministic backend mocks;
real-page meaning and actual-device visual behaviour remain **pending**.

The production signer was cold-restored twice only after Android tests passed.
The actual downloaded 33,641,911-byte APK independently passed v2 RSA signature,
chunked content-digest, ZIP CRC and binary-manifest verification. It is ordinary
`com.koorix.app`, **Koorix 1.4.0 / code 10**, non-debuggable, with the original
permanent v2 certificate. Native review classes and resource symbols are present;
Robolectric/test classes are absent from its DEX. AAPT optimizes `res/` filenames,
so resource symbols were checked in the compiled table, not guessed ZIP paths.

- APK SHA-256: `293a1837f39872b6a03806a48e8c0fb855b6f1a5a5703bb60f47baac1025be24`.
- [Download Koorix 1.4.0](https://github.com/s1po0/Kurd-man/releases/download/koorix-v1.4.0/Koorix-v1.4.0.apk)
  (repository access required). The same APK was presented in chat and Telegram
  delivery was acknowledged by the existing release workflow.
- See `koorix/releases/v1.4.0.json` for exact build, test and delivery provenance.
  This is an in-place update over **v2-signed 1.3.1**; no uninstall/key rotation.
  The pre-v2 one-time migration warning remains separate and unchanged.
