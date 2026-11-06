"""Cryptographic recovery/fail-closed tests, not translation quality tests."""
import copy
import datetime as dt
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

import store as s


class StoreTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.bundle = s.generate_bundle(dt.datetime(2126, 11, 6, tzinfo=dt.timezone.utc))
        cls.secret = "only-a-test-wrapping-secret-" + "x" * 48
        cls.key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        cls.pem = cls.key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                        serialization.NoEncryption())
        cls.public = cls.key.public_key().public_bytes(serialization.Encoding.PEM,
                                                       serialization.PublicFormat.SubjectPublicKeyInfo)
        cls.envelope = s.seal_ci(cls.bundle, cls.secret, "telegram-wrap-v1")
        cls.owner = s.seal_owner(cls.bundle, cls.public)
        cls.manifest = s.manifest_for(cls.bundle, s.canonical(cls.envelope), s.canonical(cls.owner))
        cls.backup = {"format": 1, "generation": 2, "restored_and_verified": True,
                      "signer_sha256": cls.bundle["signer_sha256"],
                      "keystore_sha256": cls.bundle["keystore_sha256"],
                      "private_backup": "dropbox:/test-only/backup.zip", "archive_sha256": "a" * 64}

    def test_ci_roundtrip(self):
        self.assertEqual(s.open_ci(self.envelope, self.secret), self.bundle)

    def test_randomized_encryption_preserves_identity(self):
        other = s.seal_ci(self.bundle, self.secret, "telegram-wrap-v1")
        self.assertNotEqual(other["ciphertext"], self.envelope["ciphertext"])
        self.assertNotEqual(other["salt"], self.envelope["salt"])
        self.assertEqual(s.open_ci(other, self.secret), self.bundle)

    def test_wrong_missing_short_secrets_fail(self):
        for secret in ("", "short", "wrong-key-" + "0" * 64):
            with self.subTest(secret_length=len(secret)), self.assertRaises(s.SigningError):
                s.open_ci(self.envelope, secret)

    def test_header_tampering_fails(self):
        for field, value in (("application_id", "com.koorix.app.preview"), ("generation", 3),
                             ("signer_sha256", "f" * 64), ("kind", "owner"),
                             ("format", 2), ("wrap_key_id", "signing-wrap-v1"),
                             ("extra", "untrusted")):
            broken = copy.deepcopy(self.envelope)
            broken["header"][field] = value
            with self.subTest(field=field), self.assertRaises(s.SigningError):
                s.open_ci(broken, self.secret)

    def test_ciphertext_and_encoding_tampering_fail(self):
        for field in ("ciphertext", "salt", "nonce"):
            for value in ("!not-base64!", s.b64(b"bad-size"), ""):
                broken = copy.deepcopy(self.envelope)
                broken[field] = value
                with self.subTest(field=field, value=value), self.assertRaises(s.SigningError):
                    s.open_ci(broken, self.secret)

    def test_unknown_envelope_field_rejected(self):
        broken = copy.deepcopy(self.envelope)
        broken["plaintext_fallback"] = True
        with self.assertRaises(s.SigningError):
            s.open_ci(broken, self.secret)

    def test_owner_recovery_independent_of_ci_secret(self):
        self.assertEqual(s.open_owner(self.owner, self.pem), self.bundle)

    def test_wrong_owner_key_fails(self):
        key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                serialization.NoEncryption())
        with self.assertRaises(s.SigningError):
            s.open_owner(self.owner, pem)

    def test_owner_envelope_tampering_fails(self):
        for field in ("ciphertext", "wrapped_key", "nonce"):
            broken = copy.deepcopy(self.owner)
            broken[field] = s.b64(b"corrupted")
            with self.subTest(field=field), self.assertRaises(s.SigningError):
                s.open_owner(broken, self.pem)

    def test_weak_owner_key_rejected(self):
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        public = key.public_key().public_bytes(serialization.Encoding.PEM,
                                                serialization.PublicFormat.SubjectPublicKeyInfo)
        with self.assertRaises(s.SigningError):
            s.seal_owner(self.bundle, public)

    def test_bundle_integrity(self):
        for field, value in (("alias", "androiddebugkey"), ("password", "q" * 64),
                             ("application_id", "com.koorix.app.preview"), ("generation", 3),
                             ("signer_sha256", "0" * 64), ("signer_sha1", "0" * 40),
                             ("keystore_sha256", "0" * 64), ("keystore", "AA=="),
                             ("valid_until", "2125-01-01T00:00:00+00:00")):
            broken = dict(self.bundle)
            broken[field] = value
            with self.subTest(field=field), self.assertRaises(s.SigningError):
                s.validate_bundle(broken)

    def test_manifest_pins_certificate_and_store(self):
        s.verify_manifest(self.bundle, self.manifest, s.canonical(self.envelope))
        for field in ("application_id", "generation", "alias", "signer_sha256", "keystore_sha256", "store_sha256"):
            broken = dict(self.manifest)
            broken[field] = "wrong"
            with self.subTest(field=field), self.assertRaises(s.SigningError):
                s.verify_manifest(self.bundle, broken, s.canonical(self.envelope))

    def test_required_backup_attestation(self):
        s.verify_backup(self.manifest, self.backup)
        for field, value in (("generation", 3), ("restored_and_verified", False),
                             ("signer_sha256", "0" * 64), ("keystore_sha256", "0" * 64),
                             ("private_backup", "https://public.example/key.zip"),
                             ("archive_sha256", "unknown")):
            broken = dict(self.backup)
            broken[field] = value
            with self.subTest(field=field), self.assertRaises(s.SigningError):
                s.verify_backup(self.manifest, broken)
        with self.assertRaises(s.SigningError):
            s.verify_backup(self.manifest, {})

    @unittest.skipUnless(shutil.which("keytool"), "keytool unavailable")
    def test_java_cold_restores_identical_keystore_twice(self):
        with tempfile.TemporaryDirectory() as temp:
            for name in ("cold-run-a", "cold-run-b"):
                out = Path(temp) / name
                recovered = s.open_ci(json.loads(json.dumps(self.envelope)), self.secret)
                s.materialize(recovered, out)
                s.verify_java(out, recovered)
                self.assertEqual((out / "koorix-release-v2.p12").stat().st_mode & 0o777, 0o600)
            self.assertEqual((Path(temp) / "cold-run-a/koorix-release-v2.p12").read_bytes(),
                             (Path(temp) / "cold-run-b/koorix-release-v2.p12").read_bytes())

    def test_cli_missing_store_does_not_generate_or_publish_a_key(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp) / "must-not-exist"
            result = subprocess.run([sys.executable, str(Path(s.__file__)), "restore", "--store", temp + "/missing.json",
                                     "--manifest", temp + "/missing.json", "--backup", temp + "/missing.json",
                                     "--out", str(out)], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(out.exists())
            self.assertNotIn(self.bundle["password"], result.stdout + result.stderr)

    @unittest.skipUnless(shutil.which("keytool"), "keytool unavailable")
    def test_cli_owner_archive_is_complete_and_does_not_log_secrets(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "private.pem").write_bytes(self.pem)
            s.write_json(root / "owner.json", self.owner)
            manifest = dict(self.manifest)
            manifest["owner_envelope_sha256"] = s.sha((root / "owner.json").read_bytes())
            s.write_json(root / "manifest.json", manifest)
            result = subprocess.run([sys.executable, str(Path(s.__file__)), "recover",
                "--envelope", str(root / "owner.json"), "--manifest", str(root / "manifest.json"),
                "--owner-key", str(root / "private.pem"), "--out", str(root / "restored"),
                "--archive", str(root / "backup.zip")], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertNotIn(self.bundle["password"], result.stdout + result.stderr)
            with zipfile.ZipFile(root / "backup.zip") as archive:
                self.assertEqual(archive.testzip(), None)
                self.assertIn("owner-recovery-private.pem", archive.namelist())
                self.assertEqual(s.sha(archive.read("koorix-release-v2.p12")), self.bundle["keystore_sha256"])
                self.assertEqual(json.loads(archive.read("bundle.json")), self.bundle)

    def test_cli_rewrap_updates_manifest_without_rotating_signer(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            s.write_json(root / "bundle.json", self.bundle, private=True)
            s.write_json(root / "manifest.json", self.manifest)
            env = os.environ.copy()
            env["KOORIX_SIGNING_WRAP_KEY"] = "new-private-test-only-secret-" + "q" * 64
            result = subprocess.run([sys.executable, str(Path(s.__file__)), "rewrap",
                "--bundle", str(root / "bundle.json"), "--manifest", str(root / "manifest.json"),
                "--out", str(root / "store.json")], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            recovered = s.open_ci(s.read_json(root / "store.json"), env["KOORIX_SIGNING_WRAP_KEY"])
            self.assertEqual(recovered["keystore_sha256"], self.bundle["keystore_sha256"])
            s.verify_manifest(recovered, s.read_json(root / "manifest.json"), (root / "store.json").read_bytes())
            self.assertNotIn(self.bundle["password"], result.stdout + result.stderr)

    def test_rewrap_changes_wrapping_key_not_signer(self):
        other = s.seal_ci(self.bundle, "a-new-independent-wrap-secret-" + "a" * 64, "signing-wrap-v1")
        recovered = s.open_ci(other, "a-new-independent-wrap-secret-" + "a" * 64)
        self.assertEqual(recovered["keystore"], self.bundle["keystore"])
        self.assertEqual(recovered["signer_sha256"], self.bundle["signer_sha256"])
        with self.assertRaises(s.SigningError):
            s.open_ci(other, self.secret)


if __name__ == "__main__":
    unittest.main(verbosity=2)
