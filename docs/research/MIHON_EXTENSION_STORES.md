# Extension stores: groundwork, not a delivered feature

## Owner request

Add a Mihon-style extension-store mode with Keiyoushi compatibility. Installed
extensions/sites must appear in a separate first section; extensions not yet
installed appear below. Existing locally present extensions remain visible even
if removed from the current store. Search should find the names of sites inside
an extension without moving an installed extension into the available section.
Installed means **present**, not automatically enabled, compatible or trusted.
A read-only native catalog and local shared-APK inventory are now implemented;
installing, executing sources, browsing chapters and translating them do not
exist yet. No release or new APK may claim the requested mode works.

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
now connects those pieces to a native, explicitly read-only screen with localized
search, installed-first sections, content-warning labels and error states. It
does not install or run extensions or open sites. `ExtensionInstalledInventory`
uses the `tachiyomi.extension` feature flag to filter system-installed packages
locally, without storing, logging or transmitting the installed-app list. Koorix
requests broad `QUERY_ALL_PACKAGES` visibility solely for this user-requested
inventory; users should understand this permission permits the app to query all
installed apps. It cannot read another app's private extensions or cross-profile
apps, and an unreadable inventory is shown as unknown, not "not installed".
No detected package is automatically trusted.

Hosted run `45734684900` (`7598802`) parsed the pinned live store blob before
failing later because Robolectric could not fetch its Android artifact; it is
not a successful Android run. Run `45735126030` (`f8ce707`) also parsed the
pinned blob with source metadata, but Android/JVM tests could not run because
Maven Central returned HTTP 429. Run `45735586223` (`35b647a`) compiled and
ran the fixed-public-blob parser and installed-first grouping probe successfully,
but failed later when Robolectric could not download Android artifacts; this is
not a passed Android build or installed-app test. The release gate remains; no
test or prototype proves the requested feature works.

The owner requested **no Android emulator** for this work. Commit `7f7dd61`
adds an explicitly offline `ExtensionIdentityPolicy`: it compares approved
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
