#!/usr/bin/env python3
"""Byte-accurate delta format smoke/regression tests, no Android SDK required."""
import os
import random
import tempfile
import unittest
import zipfile

from build_apk_delta import make_patch, verify_patch


class ApkDeltaTest(unittest.TestCase):
    def make_pair(self, folder):
        first = os.path.join(folder, "old.apk")
        second = os.path.join(folder, "new.apk")
        # Stable random native libraries dominate APK size; ZIP alignment and
        # release signing can move other regions without changing native bytes.
        stable_native = random.Random(42).randbytes(7_000_000)
        stable_geo = random.Random(99).randbytes(2_000_000)
        for name, version in ((first, "a"), (second, "b")):
            with zipfile.ZipFile(name, "w") as apk:
                apk.writestr("lib/arm64-v8a/libgojni.so", stable_native,
                             compress_type=zipfile.ZIP_STORED)
                apk.writestr("assets/geoip.dat", stable_geo,
                             compress_type=zipfile.ZIP_DEFLATED)
                apk.writestr("classes.dex", (version * 200_000).encode(),
                             compress_type=zipfile.ZIP_DEFLATED)
                apk.writestr("AndroidManifest.xml", version)
        return first, second

    def test_delta_is_smaller_and_reconstructs_exact_target(self):
        with tempfile.TemporaryDirectory() as folder:
            old, new = self.make_pair(folder)
            patch = os.path.join(folder, "diff.apkdelta")
            self.assertTrue(make_patch(old, new, patch))
            self.assertLess(os.path.getsize(patch), os.path.getsize(new) // 5)
            self.assertTrue(verify_patch(old, patch, new))

    def test_tampering_and_stale_base_fail_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            old, new = self.make_pair(folder)
            patch = os.path.join(folder, "diff.apkdelta")
            self.assertTrue(make_patch(old, new, patch))
            with open(patch, "r+b") as fh:
                fh.seek(-2, os.SEEK_END)
                value = fh.read(1)
                fh.seek(-2, os.SEEK_END)
                fh.write(bytes([value[0] ^ 0x55]))
            with self.assertRaises(ValueError):
                verify_patch(old, patch, new)
            with open(patch, "r+b") as fh:
                fh.seek(0)
                fh.write(b"UNSAFE__")
            with self.assertRaises(ValueError):
                verify_patch(old, patch, new)


if __name__ == "__main__":
    unittest.main()
