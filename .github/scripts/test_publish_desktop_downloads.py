"""Regression checks for corrupted uploads and interrupted or stale promotions."""

from pathlib import Path
import tempfile
import unittest

import publish_desktop_downloads as downloads


class MemoryStorage:
    bucket = "sonadesktop"

    def __init__(self):
        self.objects = {}
        self.uploads = []
        self.fail_key = None
        self.corrupt_key = None

    def head(self, key):
        entry = self.objects.get(key)
        return entry[1] if entry else None

    def download(self, key, target):
        target.write_bytes(self.objects[key][0])

    def upload(self, path, key, cache, release, digest, filename, kind):
        if key == self.fail_key:
            raise RuntimeError("injected upload failure")
        data = path.read_bytes()
        if key == self.corrupt_key:
            data = b"x" * len(data)
        self.objects[key] = (data, {
            "ContentLength": len(data), "CacheControl": cache, "ContentType": kind,
            "ContentDisposition": f'attachment; filename="{filename}"',
            "Metadata": {"release-version": release, "sha256": digest},
        })
        self.uploads.append(key)

    def run(self, *args):
        if args[0] != "delete-object":
            raise AssertionError(args)
        self.objects.pop(args[-1])


class PromotionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.storage = MemoryStorage()

    def assets(self, version):
        root = self.root / version
        root.mkdir(exist_ok=True)
        for name in downloads.artifact_names(version):
            path = root / name
            path.write_bytes(f"installer {name}".encode())
            (root / (name + ".sha256")).write_text(f"{downloads.sha256(path)}  {name}\n")
        return root

    def test_latest_downloads_have_matching_checksums_and_no_versioned_history(self):
        for version in ("1.0.0", "1.1.0"):
            self.assertEqual("published", downloads.publish(self.storage, version, self.assets(version)))
        self.assertEqual(7, len(self.storage.objects))
        for suffix in downloads.SUFFIXES:
            name = f"sona-latest-{suffix}"
            data = self.storage.objects[f"latest/{name}"][0]
            checksum = self.storage.objects[f"latest/{name}.sha256"][0].decode()
            self.assertEqual(f"{downloads.hashlib.sha256(data).hexdigest()}  {name}\n", checksum)
            self.assertIn(b"1.1.0", data)
        self.assertEqual("1.1.0", downloads.read_state(self.storage, "latest/release.json")["release_version"])
        writes = len(self.storage.uploads)
        self.assertEqual("superseded", downloads.publish(self.storage, "1.0.1", self.assets("1.0.1")))
        self.assertEqual(writes, len(self.storage.uploads))

    def test_interrupted_promotion_blocks_older_release_and_resumes_verified_objects(self):
        assets = self.assets("2.0.0")
        self.storage.fail_key = "latest/sona-latest-windows-x64.msi"
        with self.assertRaisesRegex(RuntimeError, "injected"):
            downloads.publish(self.storage, "2.0.0", assets)
        self.assertNotIn("latest/release.json", self.storage.objects)
        self.assertEqual("superseded", downloads.publish(self.storage, "1.0.0", self.assets("1.0.0")))
        self.storage.fail_key = None
        self.assertEqual("published", downloads.publish(self.storage, "2.0.0", assets))
        self.assertNotIn("latest/promotion.json", self.storage.objects)
        self.assertEqual(1, self.storage.uploads.count("latest/sona-latest-macos-arm64.dmg"))
        self.assertEqual("published", downloads.publish(self.storage, "2.0.0", assets))
        self.assertEqual(1, self.storage.uploads.count("latest/sona-latest-macos-arm64.dmg"))

    def test_corrupt_remote_bytes_cannot_mark_release_complete(self):
        self.storage.corrupt_key = "latest/sona-latest-macos-arm64.dmg"
        with self.assertRaisesRegex(ValueError, "remote verification"):
            downloads.publish(self.storage, "1.0.0", self.assets("1.0.0"))
        self.assertNotIn("latest/release.json", self.storage.objects)
        self.assertIn("latest/promotion.json", self.storage.objects)

    def test_bad_checksum_or_prerelease_cannot_write_to_storage(self):
        assets = self.assets("1.0.0")
        (assets / downloads.artifact_names("1.0.0")[0]).write_bytes(b"corrupted")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            downloads.publish(self.storage, "1.0.0", assets)
        with self.assertRaisesRegex(ValueError, "final numeric semver"):
            downloads.publish(self.storage, "1.0.0-rc.1", assets)
        self.assertFalse(self.storage.objects)

    def test_same_version_cannot_replace_published_installer_bytes(self):
        assets = self.assets("1.0.0")
        downloads.publish(self.storage, "1.0.0", assets)
        name = downloads.artifact_names("1.0.0")[0]
        (assets / name).write_bytes(b"replacement")
        (assets / (name + ".sha256")).write_text(f"{downloads.sha256(assets / name)}  {name}\n")
        writes = len(self.storage.uploads)
        with self.assertRaisesRegex(ValueError, "different installer bytes"):
            downloads.publish(self.storage, "1.0.0", assets)
        self.assertEqual(writes, len(self.storage.uploads))


if __name__ == "__main__":
    unittest.main()
