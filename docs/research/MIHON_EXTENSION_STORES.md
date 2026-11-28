# Extension stores: groundwork, not a delivered feature

## Owner request

Add a Mihon-style extension-store mode with Keiyoushi compatibility. Installed
extensions/sites must appear in a separate first section; extensions not yet
installed appear below. Existing locally present extensions remain visible even
if removed from the current store. Search should find the names of sites inside
an extension without moving an installed extension into the available section.
Installed means **present**, not automatically enabled, compatible or trusted.
A native catalog, local shared-APK inventory, explicit signer confirmation and
Android installer handoff are implemented as an unfinished candidate. Source
homepages can be opened in the limited static reader, but extension execution
and Mihon-style source browsing do not exist yet. No release or new APK may
claim the requested extension mode works.

## Upstream findings

- [Keiyoushi repository](https://github.com/keiyoushi/extensions) advertises
  `https://github.com/keiyoushi/extensions/raw/repo/index.pb`. Repository
  snapshot `0859bd8b9bed149e40b1a2f9c92f1a4fced2c9de` has gzipped protobuf
  metadata, blob `8cf02b6b70f10dd9733659780f7ca858377538c3`, and a current
  `repo.json` fingerprint. Its legacy `index.min.json` is now an outdated-app
  compatibility listing, not the full catalog. Remote metadata, including a
  fingerprint supplied by the same server, is untrusted until the user approves
  a store and its identity is verified independently. Fingerprints can change;
  do not hard-code the fixture as a universal trust decision.
- [Upstream index.proto](https://github.com/keiyoushi/extensions-source/blob/main/.github/scripts/index.proto)
  describes store metadata, resources, content warnings and multiple sources
  per extension. Source metadata URLs, labels and identifiers are untrusted.
- [Mihon](https://github.com/mihonapp/mihon), examined at
  `f8e30a86086020aaee1e89a9d015373b1a4ff026` (Apache-2.0), handles installed
  shared and private extensions; its loader supports extension library versions
  1.4 and 1.6, obtains signing certificate SHA-256 fingerprints, checks trust and
  package metadata, and loads extension classes through a class loader. A
  class loader is **not** a security sandbox: extension code shares the host
  process and permissions. Do not copy this unsafe assumption into Koorix or
  claim a certificate proves a source harmless. Android package visibility,
  split/source API dependencies, supported versions and store updates all need
  real-device testing. No upstream code was copied into this groundwork.

## Current implementation and evidence

`ExtensionStoreIndex` is a bounded offline parser for an embedded-list v2
protobuf, including gzip expansion limits, duplicate-package rejection,
content warnings and per-extension source metadata. External index-list URLs
are deliberately rejected. URLs are merely data: they are not downloaded,
validated or executed. `ExtensionLibrarySections` is an unconnected pure data
model for installed-first grouping and source-name search; the caller must
supply the actual local extension inventory, and a package-name match must
never be interpreted as signature verification. `ExtensionCatalogActivity`
now connects those pieces to a native screen with localized search,
installed-first sections, content-warning labels and error states. The reader
can explicitly approve a displayed store signer, prepare a matching APK and
handoff to Android's installer; Koorix never installs silently. The reader may
also explicitly open a catalog source's HTTPS homepage in the separate static
reader and return its captured images to the translation flow. That visits a
website; it does not execute the extension and cannot replace a genuine source
runtime. Run `45991332423` (`1827052`) passed 378 JVM tests and signed regular
APK checks without emulator, including synthetic catalog-to-static-reader
intent and result propagation; this does not demonstrate live Android source
execution, website compatibility, or translation quality.
`ExtensionInstalledInventory`
uses the `tachiyomi.extension` feature flag to filter system-installed packages
locally, without storing, logging or transmitting the installed-app list. Koorix
requests broad `QUERY_ALL_PACKAGES` visibility solely for this user-requested
inventory; users should understand this permission permits the app to query all
installed apps. It cannot read another app's private extensions or cross-profile
apps, and an unreadable inventory is shown as unknown, not "not installed".
No detected package is automatically trusted. Android's per-app
`REQUEST_INSTALL_PACKAGES` permission is requested for the explicit system
installer handoff; granting it does not install anything automatically.
`ExtensionApkStager` bounds download/cache use, validates HTTPS destinations
and each redirect, stages in private cache and compares package/version/signer
via Android metadata before the system installer independently verifies it.
Only app-created installer-cache APKs older than seven days are eligible for
bounded cleanup; reader files and projects are not touched. The displayed store fingerprint still needs the reader's independent approval;
a valid signer does not imply safe code. API26 synthetic metadata tests are not
proof of API28+ PackageManager behavior, APK installer behavior, or malware
containment. Run `45993236516` (`09c8107`) also passed synthetic SDK28/34
SigningInfo tests for single, multiple and rotated signers, not device signature
verification. No APK is executed by these components.

Hosted run `45734684900` (`7598802`) parsed the pinned live store blob before
failing later because Robolectric could not fetch its Android artifact; it is
not a successful Android run. Run `45735126030` (`f8ce707`) also parsed the
pinned blob with source metadata, but Android/JVM tests could not run because
Maven Central returned HTTP 429. Run `45735586223` (`35b647a`) compiled and
ran the fixed-public-blob parser and installed-first grouping probe successfully,
but failed later when Robolectric could not download Android artifacts; this is
not a passed Android build or installed-app test. The release gate remains; no
test or prototype proves the requested feature works.

The owner initially requested **no Android emulator**, then explicitly allowed
it again for continued work. Prior no-emulator results remain JVM-only evidence;
future emulator results must be reported separately and are not physical-device
or extension-execution proof. Emulator run `46084388902` (`af2f7df`) verified the
regular signed APK and exercised static MDN image import into OCR and loopback
literal rejection; it failed later because the driver pressed Back only once,
which dismissed the keyboard instead of closing the reader. It did not complete
its synthetic translation checks and did not run an extension. The observed native
frame also showed misaligned static-reader button labels; the subsequent candidate
centers them. Follow-up run `46085388190` (`8405b4f`) passed 412 JVM tests,
permanent-v2 signed-build checks and the full installed regular-APK API34
emulator driver. On fixed fixtures it exercised the native static reader,
image-to-OCR/translation/private reader, original toggle, unified chooser,
public export/Studio/PDF and STOP; its report blob is
`e06a463841c391100b94bac5a26cb921e35fb418`, APK SHA-256
`3b3d31144fbfa52c3080aedf1cf0c4f1e0c69dfa34b6672acfae8b817e705ac3`.
The observed MDN reader frame shows centered action labels. This is not
extension execution, actual installation of an extension, broad web-security,
physical-device, translation-meaning or real-page visual acceptance, and the
candidate was not published. Commit `7f7dd61` adds an explicitly offline `ExtensionIdentityPolicy`: it compares approved
SHA-256 signer identity, package and exact catalog version, compatible library
versions, and existing signer/version to reject substitution and downgrade. It
is not connected to Android, cannot verify APK signatures on its own, and does
not confer safety or trust. Hosted run `45976534501` passed the pure Java
synthetic identity/grouping checks and parsed the pinned Keiyoushi fixture;
no emulator, extension installation or installed-app verification was used.
Regular build run `45976534125` (`7f7dd61`) failed at the Robolectric artifact
fetch step and did not produce or verify an APK. It did not launch an emulator.
The no-emulator opt-out on the regular verification workflow must never be
mistaken for device acceptance; the existing release security gate stays in
place while the feature is incomplete.

`ExtensionCatalogClient` now has a fixed Keiyoushi-only, pinned-public-DNS GET
prototype, parses the bounded v2 catalog and limits fetch concurrency. A caller
wait deadline cancels and closes an attached socket; OS DNS/connect can still
outlive the deadline, so a single shared worker and zero waiting queue prevent
unbounded work. It is not connected to any UI and isn't yet a complete network
isolation solution. Hosted OpenJDK runs `45977591845` and `45977843317` passed
synthetic bounded-worker checks, a pinned public fixture and a real current
Keiyoushi public index fetch without an emulator; they do not prove Android
TLS/network behavior, APK installation or extension runtime security. The
regular build `45977338442` failed before the build on a Maven artifact fetch.
After an opt-in CI switch to Google's Maven Central mirror, regular build
`45978350116` (`96b5192`) passed 335 JVM tests, built and verified the same-v2
signed regular APK, and skipped the Android emulator at the owner's request.
Run `45979804908` (`354178b`) passed 340 JVM tests, including synthetic native
catalog/inventory grouping, built and verified the same-v2 APK, and skipped the
emulator. It did not publish an APK or exercise the app on a device; installing
and using extensions remains unimplemented and this is not a release candidate.
Run `45981517955` (`289ddea`) passed 348 JVM tests, including synthetic archive
and staging failure checks, without an emulator. Run `45982363408` (`ee4f1ee`)
used Android SDK `apksigner` and `aapt` to independently check one fixed public
extension APK's signature and metadata without installing or executing it;
this checks that fixture, not the in-app PackageManager implementation on a
phone. The follow-up consent UI test at `264b43c` failed on test looper timing;
run `45983904594` (`0071e58`) corrected this and passed 349 JVM tests and
signed-build checks with no emulator. These remain synthetic UI tests, not
actual Android installer or extension execution validation.

`ExtensionDexExtractor` is an unconnected, bounded ZIP/DEX byte-extraction
primitive. It deliberately neither verifies nor loads code, nor is it a sandbox.
Its header check is not DEX validation; any future loader needs an independently
verified immutable input, isolated execution and adversarial/device validation.
The separate public fixture check exercises extraction only after SDK apksigner
verification and never runs code. Run `45984657996` (`c3985ca`) passed 353 JVM
tests, verified the fixture signer and metadata, extracted its DEX bytes, and
built/verified the signed regular APK; Android emulator execution was skipped.
This is not a functioning source runtime or device acceptance. The inspected
`mihon-kit` snapshot has a conflict between its Apache-2.0 LICENSE file and GPL-3.0 README: no library was
copied or integrated; clarify upstream terms before any reuse.

The pinned Keiyoushi extension source at `bac637ac333a03da0f8c5e7894713d782670b2d7`
confirms why simply loading the downloaded fixture is insufficient: its build
plugin bundles the Keiyoushi core in each extension but uses `compileOnly` for
shared libraries and `extensions-lib` 1.6. The AchyManga fixture subclasses
`KeiSource`, which in turn subclasses Mihon `HttpSource`, depends on OkHttp,
coroutines, serialization and injection, and insists that particular host
network interceptors exist. The pinned `keiyoushi/extensions-lib` revision
`42255ee5fa96d9425697b7c143587483207308d1` is explicitly a compile-only stub
library: for example, its `HttpSource` constructor throws `Stub!`; packaging it
as the host implementation would not run sources. Its README lists newer host
Kotlin, coroutines, serialization, OkHttp and jsoup compatibility requirements.
Each source may customize its client. `ExtensionNetworkBroker` is an unconnected
candidate mediator for anonymous, bounded public HTTPS GET, an allowlist of
request headers and cancellation; it does not implement POST, cookies, login,
JavaScript, a Binder boundary or source execution. It rejects attempts to set
Host, authorization, cookies, framing or arbitrary headers; cross-host redirects
strip Referer. Its worker limit is a safety bound, not a daily allowance, and
blocking DNS may outlive cancellation. A safe runtime
must supply compatible ABI/dependencies and mediate all privileged requests
without trusting extension-created clients; an isolated process with no network
permission is a candidate boundary, not a working compatibility layer. No source
class has been loaded or executed, and the store screen must not claim otherwise.

An Android `isolatedProcess=true` service is a potential execution boundary
([Android service documentation](https://developer.android.com/guide/topics/manifest/service-element)),
not a completed solution: it has no permissions of its own, requires narrow
IPC and has compatibility limits for extension code needing network, files,
preferences, JavaScript or WebView. It must not be replaced by merely loading
untrusted extension code in the main process or an ordinary same-UID process.
Mihon's own private extensions cannot automatically be enumerated/read by a
separate app; system-installed shared APKs, package visibility and local
Koorix-private installs need separate reconciliation paths. Never claim an
empty inventory means the user has no previously installed extensions.

The unreleased candidate can import one explicitly selected extension APK at a
time through the existing unified file chooser after a confirmation. It copies
up to 32 MB privately, recognises the declared extension feature, and displays
it alongside installed packages with an explicit imported/not-installed label.
It does not scan Downloads or other apps' private data. Mixed image/CBZ/APK
selections are rejected without replacing the selected comic. Stop can interrupt
copying; a provider that blocks opening a stream may delay cancellation.
A SHA-256 filename detects later modification, not authenticity; package
metadata and a matching catalog package name are untrusted. It does not verify
signatures, install, approve, or execute the imported APK. Future execution must
check identity/signatures independently. Damaged copies are not automatically
removed. No APK containing this change has been released.

## Hosted import probe observations

On API 34, run `46141224950` selected the SDK-verified AchyManga APK with the
ordinary unified Android chooser and confirmed the import dialog. The success
message appeared, the selected public file's digest remained unchanged, and
the extension package was not system-installed. The driver then scrolled in
the wrong direction and did not reach the catalog, so this run does not prove
the imported item appears there. The driver direction was corrected in
`d4400d4`; run `46142131263` could not retest it because its preliminary
Android TLS probe failed DNS resolution before the app driver started. Neither
run executed extension code or establishes source compatibility or safety.

## Remaining acceptance gates

- Review a compatible Mihon source API and host dependency/license strategy;
  support real search, title/chapter/page operations, and source-specific image
  headers, not just a catalog or a generic website button.
- Avoid executing untrusted extension code with access to Koorix data, signing
  material or arbitrary local-network endpoints. Design and test an isolation
  and narrowly scoped IPC/network architecture with explicit permissions and
  failure behavior. A separate class loader/process sharing the app UID does
  not satisfy this requirement. Existing WebView private-network and hostile
  page-world issues remain open independently.
- Handle user-approved store identity, per-APK signer verification, version
  checks, safe upgrades, package-name conflicts, revocation, install consent,
  removal, and content warnings without assuming store metadata is safe.
  No installer or extension should be bundled/installed automatically.
- Complete native Kurdish/English/Arabic UI with installation, source execution,
  language/content filtering, and explicit trust. The preliminary read-only
  sections/search/refresh and system-installed inventory exist; Koorix-private
  installs, Android package-visibility behavior and explicit trust still need
  implementation and verification. Preserve user's existing installs and data.
- Respect 300-page/300-MB import and 1-GB cache limits, STOP, project recovery,
  and normal automatic Translate. No hidden paid service or per-image chat.
- Test malformed and hostile stores/APKs, wrong/changed signers, unavailable
  networks, redirects, private networks, parallel lifecycle/restarts, and actual
  extension/source-to-translation flow on disposable Android. Public-site tests
  must not claim complete coverage. Final same-v2-signed regular APK, release
  link and Telegram acknowledgement remain gated on actual completion.
