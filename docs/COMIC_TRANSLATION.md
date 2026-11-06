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

## Distribution status / signing recovery

The first 1.3.0 build (`42138208339`) passed the production skill tests (558
assertions) and Android compilation. It was **not distributed**: artifact
storage quota was exhausted, and, more importantly, the old keystore cache
had expired. The autogenerated replacement certificate did not match the
installed app. No new-signer APK was uploaded/sent/released from that run.

The workflow now publishes directly from the verified build job (no Actions
artifact storage dependency) and **fails closed before every distribution
step unless the APK signer is exactly**:

```
48c50cf5a99fcde5bbff27f586d520095e10276b4c705236e708ca261c1cca35
```

The legacy cache is only a recovery fallback, not permanent key storage.
The owner must securely restore the **original private keystore** in the
repository Actions secret `KURDMAN_DEBUG_KEYSTORE_B64` (base64-encoded original
PKCS12 keystore, alias `androiddebugkey`, existing debug password `android`).
Never paste signing material into chat, commit it, put it in a public release,
or generate a new key and present it as an in-place update. The workflow can
still compile for verification when the key is missing, but will publish
neither APK blobs, Releases nor Telegram APKs.

A certificate/APK does not contain the recoverable private signing key. If
no original backup exists, an in-place update cannot be made; a change of
signing identity requires the owner's explicit migration decision and data
backup. Do **not** uninstall the current app to try this feature. The website
continues to link to the existing, correctly signed 1.2.4 until a distributable
1.3.0 is actually available.

Latest verification: commit `9843e23`, run `42139024790` passed **563**
production rule/safety assertions and the full Android build. The overall
workflow deliberately failed at `Require original APK signer before
distribution` (`SIGNING_KEY_UNAVAILABLE`). All blob/Release/Telegram steps
were skipped. This is an unresolved signing/distribution blocker, not a
successful public 1.3.0 release.

## Separate test preview

A non-destructive preview can be built with:

```sh
gradle -p android -PkoorixPreview=true assembleKoorixDebug
```

It is explicitly named **Koorix Preview**, version `1.3.0-preview.1`, package
`com.koorix.app.preview`. It installs beside, never over, the installed
production Koorix. Both launch and account screens show the separate-preview
notice in all three UI languages. Accounts/daily server quotas still apply;
there is no sign-in or Premium bypass. Files, downloaded packs, preferences
and sessions are separate. Auth callback schemes follow the application ID
so the preview cannot intercept production magic links. Email codes remain
usable without registering the preview's optional callback redirect.

`.github/workflows/koorix-preview.yml` builds only on an explicit request in
`koorix/preview-release.json`. It verifies the actual APK package, version,
label and signature before publishing a clearly labelled **prerelease**, chat
blob and Telegram preview. It never changes the official download website
or the original-signer production gate. The preview has its own debug key;
a secure `KOORIX_PREVIEW_KEYSTORE_B64` backup can restore that key. After the
first bootstrap its fingerprint is pinned and missing/mismatched keys must
fail closed, not silently break later preview updates. Export important
preview projects; this test application is not a substitute for recovery
of the original production signing key.

### Verified preview delivery

- Source commit `c20a37f`, CI `42141658408`: **success**. Required 563 rule/safety
  assertions passed; Android build, actual APK package/version/label and
  signature verification passed. Telegram reported `ok:true`.
- APK: `Koorix-Preview-v1.3.0-1.apk`, 36,485,978 bytes, ZIP verified locally
  (606 entries), SHA-256
  `8a7685a2b21ec7166d47053537e85f02863c1ff0d43ee0382bfca569f75dc145`.
- Preview signer:
  `652abef9a4742cf8a9fa8f04c92691bdfa2e26bd7a544ba154e4c26d96c62181`.
  The workflow is now pinned to this test certificate. It remains unrelated
  to the production certificate; the original signing blocker is unresolved.
- Clearly marked prerelease (not the site's official download):
  https://github.com/s1po0/Kurd-man/releases/download/koorix-preview-v1.3.0-1/Koorix-Preview-v1.3.0-1.apk
- The preview needs its own login and, for offline use, its own language-pack
  download. It does not read, overwrite or migrate the main app's private data.
  Device visual/semantic quality acceptance remains pending.

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
