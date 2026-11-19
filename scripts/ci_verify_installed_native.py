#!/usr/bin/env python3
"""Verify Android ACTUALLY extracted the expected JNI bytes for its selected ABI.

The host APK ZIP audit alone is insufficient: it cannot prove the native loader
sees the same bytes in /data/app/.../lib/<abi> after PackageManager installation.
Never prints user data, subscription URLs or file contents.
"""
import hashlib
import re
import subprocess
import sys
import zipfile

SUPPORTED = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}
REQUIRED = ("libbox.so", "libgojni.so")


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True).strip()


def verify_native(apk, package="xyz.wraithnet.phantom"):
    dump = adb("shell", "dumpsys", "package", package)
    abi_match = re.search(r"\bprimaryCpuAbi=([\w-]+)", dump)
    lib_match = re.search(r"\bnativeLibraryDir=(/[^\s]+)", dump)
    if abi_match is None or lib_match is None:
        raise AssertionError("Android PackageManager did not report the native ABI/library directory")
    abi = abi_match.group(1)
    if abi not in SUPPORTED:
        raise AssertionError(f"Unsupported device ABI chosen by Android: {abi}")
    native_dir = lib_match.group(1)
    with zipfile.ZipFile(apk) as z:
        for lib in REQUIRED:
            archive_file = f"lib/{abi}/{lib}"
            contents = z.read(archive_file)
            expected = hashlib.sha256(contents).hexdigest()
            actual = adb("shell", "sha256sum", f"{native_dir}/{lib}").split()[0]
            if not re.fullmatch("[0-9a-f]{64}", actual):
                raise AssertionError(f"Android failed to hash {archive_file}")
            if expected != actual:
                raise AssertionError(
                    f"{archive_file}: Android extracted different ELF bytes than the signed APK"
                )
    print(f"Android installed native ELF verified: ABI={abi}, both Go libraries identical", flush=True)


if __name__ == "__main__":
    verify_native(sys.argv[1], sys.argv[2] if len(sys.argv) > 2
                  else "xyz.wraithnet.phantom")
