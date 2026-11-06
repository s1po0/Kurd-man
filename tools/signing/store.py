#!/usr/bin/env python3
"""Durable Koorix signing storage. Plaintext key material must NEVER enter Git/logs.

Normal builds only restore and verify an existing key. Generation is a separate,
explicit one-time bootstrap. Git contains authenticated ciphertext + public certs;
an independent owner recovery archive is kept in the owner's private storage.
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import zipfile

from cryptography import x509
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

APP_ID = "com.koorix.app"
GENERATION = 2
ALIAS = "koorix-release"
PURPOSE = "koorix-production-signing"
LEGACY_SIGNER = "48c50cf5a99fcde5bbff27f586d520095e10276b4c705236e708ca261c1cca35"
INFO = b"koorix/production-signing/v2/ci-wrap"
KEY_ENV = {"signing-wrap-v1": "KOORIX_SIGNING_WRAP_KEY", "telegram-wrap-v1": "TELEGRAM_SIGNING_WRAP"}


class SigningError(Exception):
    """Safe, non-secret diagnostic text for CI annotations."""


def canonical(value: dict) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def unb64(value: str) -> bytes:
    try:
        if not isinstance(value, str) or len(value) > 200_000:
            raise ValueError()
        return base64.b64decode(value, validate=True)
    except (ValueError, TypeError):
        raise SigningError("Invalid signing envelope encoding") from None


def read_json(path: Path) -> dict:
    if not path.is_file() or path.stat().st_size > 250_000:
        raise SigningError("Required signing/recovery file is missing or oversized")
    try:
        value = json.loads(path.read_bytes())
        if not isinstance(value, dict):
            raise ValueError()
        return value
    except (ValueError, UnicodeError):
        raise SigningError("Invalid signing/recovery JSON") from None


def write_json(path: Path, value: dict, private=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    if private:
        path.parent.chmod(0o700)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
    path.chmod(0o600 if private else 0o644)


def validate_bundle(bundle: dict) -> tuple[bytes, x509.Certificate]:
    try:
        if bundle["generation"] != GENERATION or bundle["application_id"] != APP_ID or bundle["alias"] != ALIAS:
            raise ValueError()
        password = bundle["password"]
        if not isinstance(password, str) or not re.fullmatch(r"[A-Za-z0-9_-]{40,128}", password):
            raise ValueError()
        data = unb64(bundle["keystore"])
        if not 1000 <= len(data) <= 100_000 or sha(data) != bundle["keystore_sha256"]:
            raise ValueError()
        key, certificate, _ = pkcs12.load_key_and_certificates(data, password.encode())
        if not isinstance(key, rsa.RSAPrivateKey) or key.key_size < 3072 or certificate is None:
            raise ValueError()
        if key.public_key().public_numbers() != certificate.public_key().public_numbers():
            raise ValueError()
        if certificate.fingerprint(hashes.SHA256()).hex() != bundle["signer_sha256"]:
            raise ValueError()
        if certificate.fingerprint(hashes.SHA1()).hex() != bundle["signer_sha1"]:
            raise ValueError()
        if certificate.not_valid_after_utc.isoformat() != bundle["valid_until"]:
            raise ValueError()
        certificate.public_key().verify(certificate.signature, certificate.tbs_certificate_bytes,
                                        padding.PKCS1v15(), certificate.signature_hash_algorithm)
        return data, certificate
    except SigningError:
        raise
    except Exception:
        raise SigningError("Signing bundle validation failed; do not generate a replacement") from None


def generate_bundle(valid_until: dt.datetime) -> dict:
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    if valid_until <= now + dt.timedelta(days=3650):
        raise SigningError("Release certificate lifetime is too short")
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    name = x509.Name([x509.NameAttribute(NameOID.COUNTRY_NAME, "IQ"),
                      x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Koorix"),
                      x509.NameAttribute(NameOID.ORGANIZATIONAL_UNIT_NAME, "Android Release"),
                      x509.NameAttribute(NameOID.COMMON_NAME, "Koorix Release Signing v2")])
    certificate = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                   .public_key(private_key.public_key()).serial_number(x509.random_serial_number())
                   .not_valid_before(now - dt.timedelta(days=1)).not_valid_after(valid_until)
                   .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
                   .sign(private_key, hashes.SHA256()))
    password = secrets.token_urlsafe(48)
    data = pkcs12.serialize_key_and_certificates(ALIAS.encode(), private_key, certificate, None,
                                                serialization.BestAvailableEncryption(password.encode()))
    bundle = {"generation": GENERATION, "application_id": APP_ID, "alias": ALIAS,
              "created_at": now.isoformat(), "valid_until": certificate.not_valid_after_utc.isoformat(),
              "password": password, "keystore": b64(data), "keystore_sha256": sha(data),
              "signer_sha256": certificate.fingerprint(hashes.SHA256()).hex(),
              "signer_sha1": certificate.fingerprint(hashes.SHA1()).hex()}
    validate_bundle(bundle)
    return bundle


def header(bundle: dict, kind: str, **extra) -> dict:
    return {"format": 1, "purpose": PURPOSE, "kind": kind, "generation": GENERATION,
            "application_id": APP_ID, "signer_sha256": bundle["signer_sha256"], **extra}


def validate_header(value: dict, kind: str):
    expected = {"format", "purpose", "kind", "generation", "application_id", "signer_sha256"}
    expected.add("wrap_key_id" if kind == "ci" else "recipient_sha256")
    if (not isinstance(value, dict) or set(value) != expected or value.get("format") != 1 or value.get("purpose") != PURPOSE
            or value.get("kind") != kind or value.get("generation") != GENERATION
            or value.get("application_id") != APP_ID
            or not re.fullmatch(r"[0-9a-f]{64}", value.get("signer_sha256", ""))):
        raise SigningError("Unsupported or altered signing envelope header")
    if kind == "ci" and value["wrap_key_id"] not in KEY_ENV:
        raise SigningError("Unknown signing wrapping-key identifier")


def derive(secret: str, salt: bytes) -> bytes:
    if not isinstance(secret, str) or len(secret) < 32 or len(salt) != 32:
        raise SigningError("Signing wrapping secret is missing or too short")
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=INFO).derive(secret.encode())


def seal_ci(bundle: dict, secret: str, key_id: str) -> dict:
    validate_bundle(bundle)
    h = header(bundle, "ci", wrap_key_id=key_id)
    validate_header(h, "ci")
    salt, nonce = os.urandom(32), os.urandom(12)
    ciphertext = AESGCM(derive(secret, salt)).encrypt(nonce, canonical(bundle), canonical(h))
    return {"header": h, "salt": b64(salt), "nonce": b64(nonce), "ciphertext": b64(ciphertext)}


def open_ci(envelope: dict, secret: str) -> dict:
    try:
        if set(envelope) != {"header", "salt", "nonce", "ciphertext"}:
            raise SigningError("Unexpected signing envelope fields")
        h = envelope["header"]
        validate_header(h, "ci")
        nonce = unb64(envelope["nonce"])
        if len(nonce) != 12:
            raise SigningError("Invalid signing envelope nonce")
        plain = AESGCM(derive(secret, unb64(envelope["salt"]))).decrypt(
            nonce, unb64(envelope["ciphertext"]), canonical(h))
        bundle = json.loads(plain)
        validate_bundle(bundle)
        if bundle["signer_sha256"] != h["signer_sha256"]:
            raise SigningError("Signing envelope certificate mismatch")
        return bundle
    except SigningError:
        raise
    except (InvalidTag, ValueError, TypeError, KeyError):
        raise SigningError("Cannot decrypt existing signing key; restore its backup, never regenerate") from None


def seal_owner(bundle: dict, public_pem: bytes) -> dict:
    validate_bundle(bundle)
    public = serialization.load_pem_public_key(public_pem)
    if not isinstance(public, rsa.RSAPublicKey) or public.key_size < 3072:
        raise SigningError("Owner recovery public key must be RSA-3072 or stronger")
    public_der = public.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    h = header(bundle, "owner", recipient_sha256=sha(public_der))
    key, nonce = os.urandom(32), os.urandom(12)
    wrapped = public.encrypt(key, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),
                                             algorithm=hashes.SHA256(), label=None))
    return {"header": h, "wrapped_key": b64(wrapped), "nonce": b64(nonce),
            "ciphertext": b64(AESGCM(key).encrypt(nonce, canonical(bundle), canonical(h)))}


def open_owner(envelope: dict, private_pem: bytes) -> dict:
    try:
        if set(envelope) != {"header", "wrapped_key", "nonce", "ciphertext"}:
            raise SigningError("Unexpected owner recovery envelope fields")
        h = envelope["header"]
        validate_header(h, "owner")
        private = serialization.load_pem_private_key(private_pem, None)
        if not isinstance(private, rsa.RSAPrivateKey) or private.key_size < 3072:
            raise SigningError("Invalid owner recovery private key")
        der = private.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        if sha(der) != h["recipient_sha256"]:
            raise SigningError("Wrong owner recovery key")
        key = private.decrypt(unb64(envelope["wrapped_key"]), padding.OAEP(
            mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
        bundle = json.loads(AESGCM(key).decrypt(unb64(envelope["nonce"]),
                                              unb64(envelope["ciphertext"]), canonical(h)))
        validate_bundle(bundle)
        if bundle["signer_sha256"] != h["signer_sha256"]:
            raise SigningError("Owner recovery certificate mismatch")
        return bundle
    except SigningError:
        raise
    except Exception:
        raise SigningError("Owner recovery failed; keep all existing backups") from None


def manifest_for(bundle: dict, store_bytes: bytes, owner_bytes: bytes) -> dict:
    _, certificate = validate_bundle(bundle)
    return {"format": 1, "generation": GENERATION, "application_id": APP_ID, "alias": ALIAS,
            "signer_sha256": bundle["signer_sha256"], "signer_sha1": bundle["signer_sha1"],
            "keystore_sha256": bundle["keystore_sha256"], "created_at": bundle["created_at"],
            "valid_until": bundle["valid_until"], "store_sha256": sha(store_bytes),
            "owner_envelope_sha256": sha(owner_bytes), "previous_signer_sha256": LEGACY_SIGNER,
            "key_algorithm": f"RSA-{certificate.public_key().key_size}"}


def verify_manifest(bundle: dict, manifest: dict, store_bytes: bytes | None = None):
    validate_bundle(bundle)
    if (manifest.get("format") != 1 or manifest.get("generation") != GENERATION
            or manifest.get("application_id") != APP_ID):
        raise SigningError("Invalid production signing manifest")
    for field in ("alias", "signer_sha256", "signer_sha1", "keystore_sha256", "valid_until"):
        if manifest.get(field) != bundle[field]:
            raise SigningError("Production signing manifest does not match the restored key")
    if store_bytes is not None and manifest["store_sha256"] != sha(store_bytes):
        raise SigningError("Encrypted signing store file hash mismatch")


def verify_backup(manifest: dict, attestation: dict):
    if (attestation.get("format") != 1 or attestation.get("generation") != GENERATION
            or attestation.get("restored_and_verified") is not True
            or attestation.get("signer_sha256") != manifest.get("signer_sha256")
            or attestation.get("keystore_sha256") != manifest.get("keystore_sha256")
            or not attestation.get("private_backup", "").startswith("dropbox:/")
            or not re.fullmatch(r"[0-9a-f]{64}", attestation.get("archive_sha256", ""))):
        raise SigningError("Independent private signing backup must be restored and verified before publication")


def materialize(bundle: dict, out: Path):
    data, certificate = validate_bundle(bundle)
    out.mkdir(parents=True, exist_ok=True)
    out.chmod(0o700)
    key = out / "koorix-release-v2.p12"
    key.write_bytes(data)
    key.chmod(0o600)
    write_json(out / "bundle.json", bundle, private=True)
    write_json(out / "credentials.json", {"alias": ALIAS, "store_password": bundle["password"],
                                          "key_password": bundle["password"]}, private=True)
    (out / "certificate.pem").write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    (out / "README.txt").write_text(
        "KOORIX PRIVATE SIGNING RECOVERY - DO NOT SHARE OR COMMIT THIS ARCHIVE\n\n"
        "This backup contains the original v2 release keystore AND its random password.\n"
        "Keep this archive in private, durable storage. It is sufficient to recover the\n"
        "same signing identity even if CI caches, Telegram tokens or the workspace are lost.\n"
        "Keystore: koorix-release-v2.p12; password and alias: credentials.json.\n"
        "bundle.json can be re-encrypted with tools/signing/store.py without rotating the key.\n"
        f"Application: {APP_ID}\nCertificate SHA-256: {bundle['signer_sha256']}\n"
        f"Certificate SHA-1: {bundle['signer_sha1']}\nValid until: {bundle['valid_until']}\n\n"
        "NEVER generate a replacement key to fix a failed build. Restore this key.\n"
        "The pre-v2 certificate was lost; this identity cannot update pre-v2 APKs.\n")
    (out / "README.txt").chmod(0o600)


def verify_java(out: Path, bundle: dict):
    keytool = shutil.which("keytool")
    if not keytool:
        raise SigningError("keytool is required for Java keystore compatibility verification")
    env = os.environ.copy()
    env["KOORIX_VERIFY_PASSWORD"] = bundle["password"]
    result = subprocess.run([keytool, "-exportcert", "-keystore", str(out / "koorix-release-v2.p12"),
                             "-storetype", "PKCS12", "-alias", ALIAS,
                             "-storepass:env", "KOORIX_VERIFY_PASSWORD"], env=env, capture_output=True)
    if result.returncode != 0 or sha(result.stdout) != bundle["signer_sha256"]:
        raise SigningError("Java could not restore the pinned PKCS12 signing certificate")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    bootstrap = sub.add_parser("bootstrap")
    bootstrap.add_argument("--request", type=Path, required=True)
    bootstrap.add_argument("--out", type=Path, required=True)
    restore = sub.add_parser("restore")
    restore.add_argument("--store", type=Path, required=True)
    restore.add_argument("--manifest", type=Path, required=True)
    restore.add_argument("--backup", type=Path, required=True)
    restore.add_argument("--out", type=Path, required=True)
    restore.add_argument("--github-env", type=Path)
    recover = sub.add_parser("recover")
    recover.add_argument("--envelope", type=Path, required=True)
    recover.add_argument("--manifest", type=Path, required=True)
    recover.add_argument("--owner-key", type=Path, required=True)
    recover.add_argument("--out", type=Path, required=True)
    recover.add_argument("--archive", type=Path)
    rewrap = sub.add_parser("rewrap")
    rewrap.add_argument("--bundle", type=Path, required=True)
    rewrap.add_argument("--out", type=Path, required=True)
    rewrap.add_argument("--key-id", choices=KEY_ENV.keys(), default="signing-wrap-v1")
    rewrap.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "bootstrap":
            if Path("signing/koorix-release-v2.json").exists():
                raise SigningError("A permanent signing identity already exists; bootstrap is forbidden")
            request = read_json(args.request)
            if request.get("generation") != GENERATION or request.get("application_id") != APP_ID:
                raise SigningError("Invalid explicit signing bootstrap request")
            key_id = "signing-wrap-v1" if os.environ.get("KOORIX_SIGNING_WRAP_KEY") else "telegram-wrap-v1"
            secret = os.environ.get(KEY_ENV[key_id], "")
            derive(secret, os.urandom(32))  # Fail before generating anything if no durable CI secret exists.
            bundle = generate_bundle(dt.datetime.fromisoformat(request["valid_until"]))
            materialize(bundle, args.out / "private")
            verify_java(args.out / "private", bundle)
            store = seal_ci(bundle, secret, key_id)
            owner = seal_owner(bundle, Path(request["owner_public_key"]).read_bytes())
            # Verify a fresh authenticated decryption, not a cached plaintext copy.
            if canonical(open_ci(store, secret)) != canonical(bundle):
                raise SigningError("Cold signing-store recovery verification failed")
            write_json(args.out / "koorix-release-v2.enc.json", store)
            write_json(args.out / "koorix-release-v2.owner.enc.json", owner)
            manifest = manifest_for(bundle, (args.out / "koorix-release-v2.enc.json").read_bytes(),
                                    (args.out / "koorix-release-v2.owner.enc.json").read_bytes())
            write_json(args.out / "koorix-release-v2.json", manifest)
            print("Signing bootstrap created authenticated ciphertext and independent owner recovery envelope")
            print("SIGNER_SHA256=" + bundle["signer_sha256"])
            print("SIGNER_SHA1=" + bundle["signer_sha1"])
        elif args.command == "restore":
            envelope = read_json(args.store)
            validate_header(envelope.get("header", {}), "ci")
            secret = os.environ.get(KEY_ENV[envelope["header"]["wrap_key_id"]], "")
            bundle = open_ci(envelope, secret)
            manifest = read_json(args.manifest)
            verify_manifest(bundle, manifest, args.store.read_bytes())
            verify_backup(manifest, read_json(args.backup))
            materialize(bundle, args.out)
            verify_java(args.out, bundle)
            if args.github_env:
                # This generated password is not a repo secret, so explicitly mask it in CI.
                print("::add-mask::" + bundle["password"])
                with args.github_env.open("a") as env:
                    env.write("KOORIX_RELEASE_KEYSTORE=" + str((args.out / "koorix-release-v2.p12").resolve()) + "\n")
                    env.write("KOORIX_RELEASE_ALIAS=" + ALIAS + "\n")
                    env.write("KOORIX_RELEASE_STORE_PASSWORD=" + bundle["password"] + "\n")
                    env.write("KOORIX_RELEASE_KEY_PASSWORD=" + bundle["password"] + "\n")
                    env.write("EXPECTED_SIGNER=" + bundle["signer_sha256"] + "\n")
            print("Restored and Java-verified the existing permanent signing identity: " + bundle["signer_sha256"])
        elif args.command == "recover":
            envelope = read_json(args.envelope)
            manifest = read_json(args.manifest)
            if manifest.get("owner_envelope_sha256") != sha(args.envelope.read_bytes()):
                raise SigningError("Owner recovery envelope file hash mismatch")
            bundle = open_owner(envelope, args.owner_key.read_bytes())
            verify_manifest(bundle, manifest)
            materialize(bundle, args.out)
            verify_java(args.out, bundle)
            if args.archive:
                args.archive.parent.mkdir(parents=True, exist_ok=True)
                with zipfile.ZipFile(args.archive, "w", zipfile.ZIP_DEFLATED) as archive:
                    for name in ("koorix-release-v2.p12", "credentials.json", "bundle.json", "certificate.pem", "README.txt"):
                        archive.write(args.out / name, name)
                    archive.write(args.manifest, "public-manifest.json")
                    archive.write(args.owner_key, "owner-recovery-private.pem")
                args.archive.chmod(0o600)
            print("Independent owner recovery restored and Java-verified: " + bundle["signer_sha256"])
        elif args.command == "rewrap":
            bundle = read_json(args.bundle)
            validate_bundle(bundle)
            manifest = read_json(args.manifest)
            verify_manifest(bundle, manifest)
            secret = os.environ.get(KEY_ENV[args.key_id], "")
            encrypted = seal_ci(bundle, secret, args.key_id)
            if canonical(open_ci(encrypted, secret)) != canonical(bundle):
                raise SigningError("Rewrapped key did not pass cold recovery verification")
            write_json(args.out, encrypted)
            manifest["store_sha256"] = sha(args.out.read_bytes())
            write_json(args.manifest, manifest)
            print("Rewrapped existing key without changing its certificate: " + bundle["signer_sha256"])
    except SigningError as error:
        print("SIGNING_OPERATION_FAILED: " + str(error), file=sys.stderr)
        return 1
    except (OSError, KeyError, ValueError, TypeError):
        # Never include secret arguments, PKCS12 contents, or decrypted JSON in logs.
        print("SIGNING_OPERATION_FAILED: required key, wrapping secret, manifest or recovery verification is invalid. No replacement key is generated.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
