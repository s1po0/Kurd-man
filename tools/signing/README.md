# Permanent Koorix production signer (v2)

The owner authorized **a new production key**, not another Preview application.
The old certificate/private key cannot be reconstructed from an APK. The regular
package remains `com.koorix.app`. A new certificate cannot update the old-certificate
app; before that **one-time** reinstall, users must export anything they need from
the old app. Uninstalling deletes its private project, settings and downloads.
Nothing here uninstalls an app or claims to migrate its private data automatically.
Subsequent production builds must keep this v2 key and increase versionCode.

## Storage — not an Actions cache

* `signing/koorix-release-v2.enc.json`: AES-256-GCM authenticated encryption of the
  PKCS12 keystore, strong random password and identity metadata. HKDF-SHA256,
  independent 32-byte salt and 12-byte nonce. Headers are authenticated.
* `signing/koorix-release-v2.owner.enc.json`: independent RSA-OAEP-SHA256 / AES-GCM
  envelope for owner recovery, unrelated to the CI wrapping secret.
* `signing/koorix-release-v2.json`: public SHA-256/SHA-1 certificate pins, exact
  encrypted-file and keystore hashes, alias and long certificate lifetime.
* `signing/recovery-verification.json`: evidence of an actual independent owner
  restore, private Dropbox upload and server content-hash verification before
  production distribution.
* Owner-only Dropbox archive: the actual PKCS12, password, recovery key, public
  certificate and a complete validated bundle. **The archive is secret**, is not
  a public download, and must not be shared or committed. A password stored next
  to a PKCS12 does not make the whole archive encrypted. Dropbox account access
  protects it. Keep another owner-controlled offline copy for extra resilience.

The GitHub integration cannot write Actions secrets. Bootstrap therefore prefers
`KOORIX_SIGNING_WRAP_KEY` if configured; otherwise it uses the **existing**
`TELEGRAM_BOT_TOKEN` only as HKDF wrapping-key input (`telegram-wrap-v1`). It does
not put the Telegram token in Git, the APK, backups or logs. Production uses the
key ID inside the authenticated envelope and fails closed if that exact secret
is missing/wrong. This is an explicit dependency, not perpetual storage magic.

**Do not rotate/delete the wrapping secret without rewrapping first.** The owner
backup restores the SAME signing identity even if the original secret is lost.
Prefer a dedicated high-entropy `KOORIX_SIGNING_WRAP_KEY` when the owner can set it
securely in the GitHub Actions settings. Never request credentials in chat.

## Build contracts

Normal Koorix CI never invokes generation. It installs pinned Python tooling,
checks rule/safety and crypto tests, decrypts the committed store into ephemeral
runner files, checks the backup record + public pins, cold-restores a second copy,
and exports the certificate through **Java keytool**. Only then does Gradle build
`assembleKoorixRelease`. `apksigner` and `aapt` check signer, package, normal label,
non-debuggable status and version before blob/Release/Telegram delivery.

No private material is uploaded as an Actions artifact or cached. Gradle config
cache is disabled for signing builds. The old debug cache is not a dependency.
A missing/changed key blocks publication instead of generating another one.

The separate push-triggered bootstrap handles only an explicit request. It
refuses to run after a v2 manifest exists. It publishes only three allowlisted
ciphertext/public-metadata blobs, never a `private/` directory or plaintext ZIP.

## Owner recovery / wrapping-secret rotation

1. Retrieve the archive from the private folder recorded in
   `signing/recovery-verification.json`; do not create a public share link.
2. Unpack it into an ignored, permission-restricted `.private/` directory.
3. Verify PKCS12 bytes and certificate fingerprints against the committed public
   manifest before signing. `credentials.json` contains its alias/password.
4. To change only the encryption wrapper, install `tools/signing/requirements.txt`
   and supply the new random wrapping secret via a secure environment, not a
   command-line literal or chat. Run:

   ```sh
   python tools/signing/store.py rewrap \
     --bundle .private/recovered/bundle.json \
     --key-id signing-wrap-v1 \
     --out signing/koorix-release-v2.enc.json \
     --manifest signing/koorix-release-v2.json
   ```

   The command validates the original key, updates the ciphertext hash and
   verifies a cold decrypt. The certificate and PKCS12 must remain identical.
   Configure the matching `KOORIX_SIGNING_WRAP_KEY` securely for Actions and
   commit only the new ciphertext + updated public manifest. Keep the old
   wrapper/secret until a normal cold build succeeds.
5. If only the owner envelope is available, recover with `store.py recover`,
   `--owner-key`, `--envelope`, `--manifest`, and an ignored `--out` directory.
   It verifies the envelope hash and Java compatibility before accepting it.

Backups can be deleted, accounts can be lost, and secrets can be rotated; no
system can promise literally forever or zero future issues. Independent verified
copies plus fail-closed certificate pinning avoid the previous cache-loss failure.

## Distribution continuity

The permanent release tag retains the encrypted key and public pins even if the
working branch is later removed. This repository is private: GitHub release URLs
are stable but require repository access. Chat/Telegram APK files are provided
separately; no repo-visibility or public-sharing change is implied.

If the owner later enables Google Play App Signing, keep the same **app-signing**
identity for compatibility with existing v2 installs (do not silently choose a
new distribution key). A Play upload key is a different role. Google Android
OAuth clients must use the current release certificate SHA-1 from the manifest,
not the pre-v2 debug certificate. No Cloud/Play configuration is claimed here.
