# Koorix 1.5 — no-login, durable local work

The owner's latest decision removes **user login**, superseding the earlier
Google/email requirement. Regular `com.koorix.app`, version 1.5.0 / code 12,
keeps the permanent v2 signer and installs over v2-signed versions. No Preview,
new translation model, NLLB, paid API, PR or merge is introduced.

## Reader workflow

Select images/CBZ → **Translate**. No Google/email/phone/password entry, auth
callback or mandatory account screen. Optional names/genre remain inline.
The existing 74 target choices and Kurdish/English/Arabic UI remain available.

- **Saved projects** is in Files/Downloads. A new job gets its own `p-UUID`
  directory; legacy `projects/current` stays intact and is listed too.
- Projects can be reopened, renamed, deleted, backed up and restored explicitly.
  ZIP imports are transactional and enforce entry/path/page/size restrictions.
  Backups contain private source pages, dialogue, glossary and styles, **not**
  device credentials or a reusable quota permit.
- Optional **Use this story's names and terms** reuses a reader-selected book's
  frozen glossary for future Translate calls; it does not rewrite the old book.
- OCR, translation and rendering checkpoints are saved after each page with
  `AtomicFile`. Recovery skips completed work, keeps the frozen pair/style/mode,
  and does not spend another use for an already-authorized saved job.
- A foreground service can recover a pending job after a process restart/reboot
  where Android permits it; opening the app also recovers it. **STOP persists**
  and disables automatic recovery. OS force-stop/permission/OEM restrictions
  cannot be bypassed or described as an absolute never-stop guarantee.
- OCR/translation sessions close before rendering. Main-result previews are
  bounded independently of the full-resolution OCR/export path.
- Unsafe/unfittable lettering remains editable and is disclosed as a partial
  result, not described as a fully replaced or semantically approved page.

## No-login entitlements

`koorix-device-session` creates a random device identity automatically. It does
not collect a reader mailbox or phone number, nor send a login email. A random
internal technical address is an Auth implementation detail. Only a 256-bit
random device secret can recover the associated identity; the server stores its
hash. Service-role credentials remain in the Edge environment, never in the APK
or the publisher browser. Bootstrap is rate-limited; device tables are RLS/private.

`DeviceStateStore` uses AES-256-GCM with an Android Keystore key. There is no
plaintext fallback. An existing valid subscriber session migrates to encrypted
storage so an ordinary same-key update does not discard paid access. New
installation credentials are not backed up: **uninstalling/clearing app data can
lose Premium identity**. Keep the activation/order reference and project backups.

Input, packs and language-pair checks precede processing. Source pages and OCR
are saved before entitlement spending; a no-input/missing-pack/no-text failure
cannot consume a translation use. A saved UUID permit is idempotent and bound to
its caller and page count; a completed permit cannot be reused for a new job.

The existing free budget remains **3 jobs / 60 pages per UTC day**. Offline jobs
use the same cached remaining budget, not a second independent pool. Free grants
need online initialisation/renewal (also warmed on an explicit pack download).
A valid cached Premium entitlement can span offline days until expiry. Offline
jobs themselves make no account/translation network call; usage reconciles on a
later explicit online action. This is device entitlement enforcement, **not** a
claim of tamper-proof DRM or cross-install identity recovery.

## Premium and publisher tools

Plans: 1/3/6/12 months. The authoritative plan/price is server-side; a request
records a quote and reference. **No payment is automatically charged.** Payment
verification and publisher approval are manual. Code redemption still works.

`koorix/site/admin.html` is a publisher-only, first-party PKCE console, not a
reader login page. It never requests a service-role key. Server RPCs require the
configured publisher email **and a recent signed OTP/OAuth authentication method**;
an autoconfirmed password signup with that email is not sufficient. The default
Supabase mailbox rate limit applies to this optional owner console, not readers.
Only its exact production callback was appended; the shared project's provider
settings/default site and other apps' callbacks are preserved.

Device-data deletion is explicitly confirmed. It removes that device's Koorix
records and (for a Koorix-created random device) its Auth identity. A retained
legacy identity shared with another app is not globally deleted. Local editable
projects remain unless the reader separately deletes them.

## Actual evidence / limits

- Native source `2b784d6f4315f724236ea9a1f579385bf3987440`: **112 Android/JVM
  executions on SDK 26/34**, no failures/skips; build, production package/label,
  permanent pinned signer and twice-cold restore passed in CI 42283897842.
- Same-client-header live backend test: **19** bootstrap/identity/quota/idempotency/
  RLS/Premium/deletion contracts, with the disposable device deleted afterward
  (backend CI 42283897696; also a required APK-release gate).
- Existing actual Java: **604** comic assertions / **204** entries and **51**
  quality assertions. Python developer-skill tests: **55**; glossary parity: **36**.
- GitHub repository is now public (owner changed visibility). Unauthenticated
  model-asset HEAD returns 302 instead of the former private-repository 404.
  Known credential-format scan of fetched Git history found no actual credentials;
  it is not a comprehensive secrecy guarantee.
- Offline Sorani still uses the existing OPUS v2 pack; non-English sources pivot
  via English. Skill is rules/context/glossary/checks, not a replacement model.
  Real-page Sorani meaning, glyph/erasure acceptance, actual hardware Keystore,
  OEM recovery, RAM/thermal/latency and long-chapter device benchmarks remain
  **unverified**. Synthetic/mocked tests must never be marketed as an accuracy score.

See `koorix/releases/v1.5.0.json` for the final published APK hash, source, signature,
delivery and site evidence once the regular release is actually distributed.

## Final release / hosting status

Regular APK CI **42285492904 / 128125103295** passed all gates, including 112
Android/JVM executions, 19 live device-service checks with cleanup, twice-restored
permanent signing and independent v2/manifest/no-login byte verification. Release
**436954981**, asset **718409672**, is published and its APK was delivered in
Telegram and presented in chat. Local independent verification of the downloaded
33,547,301-byte APK also passed; SHA-256:
`3235da8be6e59d905b3fc868e970e9459c9c03ac623c49e8f300301def621ce1`.

The static site and a pinned, token-free public-source build are ready, but a
permanent website deployment is **not claimed**: Vercel has no GitHub-provider
connection; inline deployment hits a provider `projectSettings` requirement not
exposed by the available tool; GitHub Pages creation is denied (403). No new or
unrelated project was created. The public release page is the working download
landing page. The publisher web console is prepared source, not a claimed live
production service. Reader translation/code redemption does not depend on it.
