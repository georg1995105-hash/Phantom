"""Self-tests for universal APK ABI and 16 KiB ELF validation."""
import struct
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from audit_universal_apk import ABIS, audit, elf_metadata


def fake_elf(elf_class, machine, alignment):
    blob = bytearray(256)
    blob[:4] = bytes((127, 69, 76, 70))
    blob[4] = elf_class
    blob[5] = 1
    struct.pack_into("<H", blob, 18, machine)
    if elf_class == 2:
        struct.pack_into("<Q", blob, 32, 64)
        struct.pack_into("<HH", blob, 54, 56, 1)
        struct.pack_into("<I", blob, 64, 1)
        struct.pack_into("<Q", blob, 64 + 48, alignment)
    else:
        struct.pack_into("<I", blob, 28, 64)
        struct.pack_into("<HH", blob, 42, 32, 1)
        struct.pack_into("<I", blob, 64, 1)
        struct.pack_into("<I", blob, 64 + 28, alignment)
    return bytes(blob)


class UniversalAuditTest(unittest.TestCase):
    def make_apk(self, path, broken=None, skip=None):
        with ZipFile(path, "w", ZIP_DEFLATED) as archive:
            for abi, (elf_class, machine) in ABIS.items():
                for lib in ("libbox.so", "libgojni.so"):
                    if (abi, lib) == skip:
                        continue
                    alignment = 4096 if (abi, lib) == broken else 16384
                    archive.writestr(f"lib/{abi}/{lib}",
                                     fake_elf(elf_class, machine, alignment))

    def test_valid_universal_has_four_abis_and_both_go_runtimes(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "valid.apk"
            self.make_apk(apk)
            self.assertTrue(audit(apk))

    def test_unaligned_arm64_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "unaligned.apk"
            self.make_apk(apk, broken=("arm64-v8a", "libbox.so"))
            self.assertFalse(audit(apk))
            self.assertTrue(audit(apk, strict=False))

    def test_missing_go_runtime_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "missing.apk"
            self.make_apk(apk, skip=("x86", "libgojni.so"))
            self.assertFalse(audit(apk))

    def test_invalid_magic_is_rejected(self):
        with self.assertRaises(ValueError):
            elf_metadata(b"not an elf" + bytes(100))

    def test_file_offset_and_virtual_address_must_agree_modulo_alignment(self):
        blob = bytearray(fake_elf(2, 62, 16384))
        struct.pack_into("<Q", blob, 64 + 16, 4096)
        with self.assertRaisesRegex(ValueError, "virtual addresses"):
            elf_metadata(blob)

    def test_relro_cannot_protect_writable_data_on_same_16k_page(self):
        blob = bytearray(fake_elf(2, 62, 16384))
        struct.pack_into("<H", blob, 56, 2)
        struct.pack_into("<I", blob, 64 + 4, 2)
        struct.pack_into("<Q", blob, 64 + 40, 8192)
        struct.pack_into("<I", blob, 64 + 56, 0x6474E552)
        struct.pack_into("<Q", blob, 64 + 56 + 40, 4096)
        self.assertFalse(elf_metadata(blob)[3])
        struct.pack_into("<Q", blob, 64 + 56 + 40, 8192)
        self.assertTrue(elf_metadata(blob)[3])


if __name__ == "__main__":
    unittest.main()
