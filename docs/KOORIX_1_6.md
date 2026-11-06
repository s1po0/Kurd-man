# Koorix 1.6 — fully free public runtime and explicit image/file sources

The owner's November 7, 2026 decision supersedes the optional-Premium and daily
allowance policy in 1.5. Regular `com.koorix.app`, version **1.6.0 / code 13**,
uses the unchanged permanent RSA-3072 v2 signer. No separate Preview or KurdMan
build is delivered. Install over the existing v2 app; do not uninstall or clear
its data. The historical pre-v2 signature migration limitation is unchanged.

## No hidden commercial gate

The public flavour removes `AccountGate`, `SupabaseAuth`, `DeviceAllowance`,
`DeviceStateStore` and `PremiumActivity`, the manifest entry, sales/login strings,
server configuration and all calls from normal translation/download entry points.
`SavedJobCoordinator` runs the existing translation backend directly, without
registration, authorization latches, quota IDs or usage synchronisation. The
3-job/60-page daily limits, payment requests and activation are gone, not just
invisible. Build flags record `FREE_EDITION=true`, no required account and zero
unused quota constants.

No migration deletes old encrypted preferences, shared identities, customer data,
backend functions, orders or history. Legacy project metadata fields remain
read-compatible but no longer control execution; backups still strip credentials
and old permission fields. The historical backend workflow is manual-only and is
not invoked by free builds. There are no new backend mutations in this task.

## Gallery / Photos and Files / CBZ

- Dedicated, visible buttons plus the original source-choice button.
- Gallery uses AndroidX's multi-photo picker, image-only, where supported, and a
  multi-select `ACTION_GET_CONTENT image/*` gallery-app chooser otherwise.
- Files uses `ACTION_OPEN_DOCUMENT */*` **without `EXTRA_MIME_TYPES`**, allows
  multi-select and shared-storage/SD/cloud providers, and supplies a shared-primary
  storage initial-location hint. A missing document activity has an all-files
  `GET_CONTENT` fallback. Unknown CBZ MIME metadata no longer hides the archive.
- Read/persistable grants concern only chosen URIs; temporary gallery grants are
  accepted because the existing importer copies sources into local working files.
  Cancel does not clear an existing choice. ClipData/data URIs are deduplicated.
- Listing all MIME types does not add support for arbitrary file contents: images
  and a single CBZ/ZIP remain the supported inputs. Existing validation and
  300-page/300-MB import/1-GB cache safeguards remain. The platform photo picker
  can impose a smaller per-selection cap; Files retains the app's normal limits.
- No broad all-storage/media permission is added. Android-protected private folders
  remain inaccessible. An OEM's actual picker/provider visibility needs phone
  acceptance; Robolectric contract tests are not physical-device proof.

## Preserved and not claimed

Projects/library, backups/restores, frozen settings, page checkpoints, STOP,
recovery, diagnostics, studio, optional PDF, all 74 target choices and whole-UI
Kurdish/English/Arabic switching stay in place. Skill remains automatic with
normal **Translate**. No new model, paid API, NLLB or hidden online fallback.
Supported offline jobs need only their existing downloaded language packs, not
an initial online device entitlement. External online translation providers
retain their own rate limits/policies. OPUS Sorani meaning remains limited;
passing synthetic/JVM tests is not a semantic or visual accuracy guarantee.

## Verification and delivery

The release pipeline checks free-edition source contracts, actual Android picker
contracts and full main-activity routing on SDK 26/34, core translation rules,
cryptographic signing tests, twice cold-restored pinned production signer, and
independent APK v2 signature/content/manifest/absence-of-billing checks.
Publication and Telegram document delivery occur only after all gates pass.
`koorix/releases/v1.6.0.json` records the actual results and acknowledged delivery;
this document does not substitute planned counts for completed runs.

[Permanent APK URL](https://github.com/s1po0/Kurd-man/releases/download/koorix-v1.6.0/Koorix-v1.6.0.apk)

Current static site/privacy/readme copy reflects free 1.6. Its build copies the
reviewed checkout instead of fetching pinned, obsolete 1.5 paid-edition files.
The publisher purchase UI is retired without touching server records. No
successful standalone hosting deployment is claimed; the previously reported
Vercel/Pages integration block remains. Historical release records are preserved.
