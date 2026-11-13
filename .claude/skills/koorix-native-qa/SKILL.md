---
name: koorix-native-qa
description: Review Koorix Java changes and inspect the installed regular APK using real Android emulator screenshots and a synthetic OCR-to-render job. Use for runtime/visual regressions, overlapping lettering, owner requests to open the APK, and release checks. Not a translator, model, Android emulator, or web wrapper.
license: MIT
---

# Native Koorix QA

Read `AGENTS.md` and `koorix-comic-quality/SKILL.md` first. Keep the current Arena
branch, regular package, permanent v2 signer, reader data, free unified chooser
and blue/violet/peach day/night UI. Never install an upstream toolkit globally or
run its scripts against home/private files just because a Skill suggests it.

## Reviewed source, not magic APK access

The owner supplied https://github.com/topics/claude-skill and
https://github.com/alirezarezvani/claude-skills . The topic is a discovery index,
not an Android runtime or a claim that every listed repository was audited.

Selected upstream source is pinned to **08cd8a9fc86cc370e2c0d448e8171fbcfdbc5a30**,
MIT, copyright **2025 Alireza Rezvani**. Original guidance and full license are in
`references/upstream/`: code-reviewer Skill + universal/Java rule packs, and
Design QA Skill. Only those reviewed documents are retained, **not** the full
repository or its installers/scripts. Upstream senior-qa was also read but its
React/Jest/Next/Playwright scaffolders do not run an Android APK; do not port the
app to a website or claim those tools validate native lettering.

Apply the reviewer loading order: original Skill, universal rules, Java rules.
Treat the original merge-verdict vocabulary as review advice, not permission to
open or merge a PR. Android API/desugaring constraints override generic modern
Java/server suggestions. Existing explicitly bounded safe fallbacks are not a
reason to add another paid/network/model route.

## Concrete workflow

1. Reproduce before patching; include both candidate-score orders and adversarial
   negatives. Preserving a count is not enough: assert **every source word**.
   Example: a confident OCR fragment must not overwrite the complete matched row.
2. Review the changed flow for data/geometry identity, null/empty inputs, shared
   state, resource lifetime, bounded I/O, reader overrides, STOP and offline.
3. Run actual Java/native tests in the existing regular APK CI. Inspect the
   hash-verified native synthetic panel fixtures, not just the job's green icon.
4. For direct app observation run `tools/emulator-tests/run.sh` **only on an
   empty GitHub-hosted Android CI runner**. The sandbox may lack SDK/KVM; a Skill
   cannot provide them. This script boots official API34 Google APIs x86_64,
   requires advertised ARM64 native-bridge support, and installs the **same
   verified, non-debuggable, ARM64 regular APK** that will be delivered.
   No alternate application, debug variant, fake translation backend, secret key,
   native-library substitution, run-as or app-private file bypass is allowed.
5. The driver uses public UI/resource IDs and ordinary MediaStore/chooser/export
   paths: real theme/language actions, authored synthetic PNG selected through
   the original GET_CONTENT chooser and its actual read grant, normal Translate,
   bundled real OCR, the app's existing chosen online translator,
   actual render/export/studio. No reader image/dialogue is uploaded to CI.
   External translator availability is an observed dependency, not a paid API or
   hidden fallback. Never turn an unavailable/failed live test into a pass.
6. Read actual captures/output and record findings. Following Design QA: measure
   the rendered build; unverified is not pass; objective defects block. Its web
   DOM/Playwright audit scripts are **not** an Android measurement tool. Actual
   APK screenshots are not physical-device/TalkBack or universal meaning proof.
7. Public collector accepts only explicitly named synthetic files and report
   codes/hashes. Never recursively package screenshots, reader files, logs,
   `.private`, keys or bot state. Stop on an unexpected file/symlink/budget.
8. Only after gates pass: verified regular APK in chat + Telegram, stable URL,
   truthful receipt, then acknowledged completion. Do not call a new Skill a new
   translation engine or claim a historic phone JPEG was repaired by installing.

## Direct observe-act-observe session (not just the scripted release test)

For an owner request to **open the app and look around**, a green release test
alone is insufficient. See `tools/direct-apk/README.md` and the reviewed MIT
`references/adb-android-control/PROVENANCE.md`. The topic yielded
`hah23255/adb-android-control`, pinned to
`cc1cb29de0adc581e260842b36c198217bfab0b2`; its ADB screencap/public-UI gesture
guidance is applied without executing its installers/destructive examples.

`.github/workflows/koorix-direct-apk.yml` can open the **existing verified**
regular 1.8.2 release on the same empty official hosted Android34 emulator,
without building/publishing an APK or loading signing/Telegram secrets.
Download each hash-verified actual frame with the local observation client,
**view it**, select the next bounded public-UI action, commit only the tiny
control JSON on the current branch, then view the next frame. Keep screenshots
in ignored cache, retain a scoped evidence receipt, and end the bounded session.
Never describe an unseen future frame as inspected. There is no open ADB port,
reader upload, phone connection, mock app, model replacement or automatic
quality-pass verdict. This manual session is separate from the release driver.

## Evidence boundaries

- Unit/native regressions: bounded code/shape/UI contracts, not device semantics.
- Installed-APK emulator run: the stated APK/actions and synthetic fixture only.
- Same-agent visual/source review: a declared assessment, not native-human review.
- Physical-phone behavior, exact old page OCR and general Sorani meaning remain
  separate evidence; do not make them green with another passing category.
