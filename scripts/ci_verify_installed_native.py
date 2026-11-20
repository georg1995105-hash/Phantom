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
import time
import zipfile

SUPPORTED = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}
REQUIRED = ("libbox.so", "libgojni.so")


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True).strip()


def install_apk(apk, *flags):
    # Match PackageInstaller's full-file OTA path. Android 11's Incremental
    # Service confuses x86 with x86_64 when extracting compressed libraries.
    start = time.monotonic()
    output = adb("install", "--no-incremental", *flags, apk)
    if "Success" not in output.splitlines():
        raise AssertionError(f"Full APK install failed: {output}")
    print(f"Full APK install ({'+'.join(flags) or 'fresh'}): {time.monotonic() - start:.2f} s",
          flush=True)


ABI_DIRECTORY = {
    "armeabi-v7a": "arm",
    "arm64-v8a": "arm64",
    "x86": "x86",
    "x86_64": "x86_64",
}


def installed_native_dir(dump, package_paths):
    """Resolve the installed library directory without relying on dumpsys layout.

    Android 15 emulators may omit nativeLibraryDir from 'dumpsys package'.
    'pm path' reports the package's installed base.apk location reliably.
    """
    abi_match = re.search(r"\bprimaryCpuAbi=([\w-]+)", dump)
    if abi_match is None:
        raise AssertionError("Android did not expose an installed package primaryCpuAbi")
    abi = abi_match.group(1)
    if abi not in SUPPORTED:
        raise AssertionError(f"Unsupported device ABI chosen by Android: {abi}")

    # Prefer the explicit PackageManager path when it is present.
    lib_match = re.search(r"\bnativeLibraryDir=(/[^\s]+)", dump)
    if lib_match:
        return abi, lib_match.group(1)

    base = next((
        line[len("package:"):].strip()
        for line in package_paths.splitlines()
        if line.startswith("package:/") and line.endswith("/base.apk")
    ), None)
    if base is None:
        raise AssertionError("Android 'pm path' did not expose the installed base.apk")
    # With useLegacyPackaging=true the .so files are extracted next to base.apk.
    native_dir = base.rsplit("/", 1)[0] + "/lib/" + ABI_DIRECTORY[abi]
    return abi, native_dir


def verify_native(apk, package="xyz.wraithnet.phantom"):
    dump = adb("shell", "dumpsys", "package", package)
    package_paths = adb("shell", "pm", "path", package)
    abi, native_dir = installed_native_dir(dump, package_paths)
    with zipfile.ZipFile(apk) as z:
        for lib in REQUIRED:
            archive_file = f"lib/{abi}/{lib}"
            contents = z.read(archive_file)
            expected = hashlib.sha256(contents).hexdigest()
            result = adb("shell", "sha256sum", f"{native_dir}/{lib}").split()
            actual = result[0] if result else ""
            if not re.fullmatch("[0-9a-f]{64}", actual):
                raise AssertionError(f"Android failed to hash {archive_file}")
            if expected != actual:
                candidates = [other for other in sorted(SUPPORTED) if other != abi and
                              hashlib.sha256(z.read(f"lib/{other}/{lib}")).hexdigest() == actual]
                raise AssertionError(
                    f"{archive_file}: installed SHA-256 {actual} != APK {expected}; "
                    f"matching other APK ABIs: {candidates}"
                )
    print(f"Android installed native ELF verified: ABI={abi}, both Go libraries identical", flush=True)
    return abi


if __name__ == "__main__":
    verify_native(sys.argv[1], sys.argv[2] if len(sys.argv) > 2
                  else "xyz.wraithnet.phantom")
