# Koorix 1.6.1 — one app-capable source chooser

Regular `com.koorix.app`, **1.6.1 / code 14**, unchanged permanent v2 production
signer. The owner corrected the 1.6 picker: they want the app-capable source list
like their second screenshot, not two Gallery/Files sections. Screenshots are
private task evidence and are not copied into the public repository.

## Root cause and correction

`ACTION_OPEN_DOCUMENT` selects through document providers; it does not generally
offer the external GET_CONTENT activities shown in the requested source list.
A separate `PICK_IMAGES` route further narrowed selection to photos. An all-types
MIME setting alone did not make the first route equivalent to the second.

1. One existing image/CBZ button, with no split route dialog or secondary buttons.
2. One `ACTION_CHOOSER` wrapping `ACTION_GET_CONTENT`, `*/*`, `CATEGORY_OPENABLE`,
   multi-select and selected-URI read access. No MIME whitelist, local-only flag,
   package/component, extra initial intents or initial storage root.
3. Compatible installed image-only, archive and general file-source intent
   handlers are eligible, including apps such as Samsung Gallery, MT Manager,
   ZArchiver and My Files when they expose compatible content selection.
4. Single data URI and ClipData results share the existing immediate-copy importer;
   cancellation preserves the previous selection. GET_CONTENT's transient grants
   do not require all-storage/media-read permissions or account registration.
5. Existing font/editor callback registration keys remain ahead of the new source
   contract. Personal KurdMan's existing input route/version is not changed.

Android/OEMs control the actual resolver/provider UI. An app must be installed
and declare matching content-selection support; Koorix cannot force an arbitrary
app to be a picker, open Android-protected folders, or manufacture a provider.
The contract tests simulate declared handler types, not real Samsung/MT/ZArchiver
installations. Phone acceptance remains pending; don't claim the screenshots
were reproduced on a physical Galaxy S23 Ultra in the sandbox.

## Unchanged

Completely free for now; no Premium, payments, activation codes, daily app quota,
login, device registration or entitlement service. Historical private data is
untouched. No new model, paid API or NLLB. Projects, backups, STOP, recovery,
Reports, studio, optional PDF, automatic Skill, all 74 target choices and the
three-language UI remain. Existing technical 300-page/300-MB import/1-GB cache
safeguards and external-provider limits remain. Source apps can impose their
own selection caps. Supported inputs remain images or one CBZ/ZIP, not arbitrary
visible files. Translation meaning/visual-quality limitations are unchanged.

## Verification and delivery

136 expected Android/JVM executions cover picker/filter contracts, the actual
single main button, removed split UI, cancellation, free operation and preserved
project/translation contracts on SDK 26/34. Existing native/crypto/translation and
independent public APK byte/signature checks remain mandatory before shipping.
`koorix/releases/v1.6.1.json` records actual, not planned results and dual delivery.

Install over the existing v2-signed Koorix without uninstalling/clearing data.
[Permanent APK URL](https://github.com/s1po0/Kurd-man/releases/download/koorix-v1.6.1/Koorix-v1.6.1.apk).

Current site/docs reflect the correction. Historical 1.6 release records are
retained as history. No new Vercel/Pages deployment attempt or success is claimed.
