#!/usr/bin/env python3
"""Fail-closed ABI and ELF audit for the signed Phantom universal APK.

Checks the exact artifact users would install, not just Gradle declarations.
Native JNI libs must be present for every supported ABI, match the CPU
instruction set, and have 16 KiB load-segment alignment on 64-bit ABIs.
"""
import argparse
import collections
import struct
import sys
import zipfile

ABIS = {
    "armeabi-v7a": (1, 40),
    "arm64-v8a": (2, 183),
    "x86": (1, 3),
    "x86_64": (2, 62),
}
REQUIRED = {"libbox.so", "libgojni.so"}


def elf_metadata(data):
    if len(data) < 64 or data[:4] != bytes((127, 69, 76, 70)) or data[5] != 1:
        raise ValueError("not a little-endian ELF shared library")
    bitness = data[4]
    if bitness not in (1, 2):
        raise ValueError("unsupported ELF class")
    machine = struct.unpack_from("<H", data, 18)[0]
    if bitness == 2:
        offset = struct.unpack_from("<Q", data, 32)[0]
        entry_size, count = struct.unpack_from("<HH", data, 54)
        align_offset = 48
    else:
        offset = struct.unpack_from("<I", data, 28)[0]
        entry_size, count = struct.unpack_from("<HH", data, 42)
        align_offset = 28
    if count == 0 or count > 512 or entry_size < align_offset + (8 if bitness == 2 else 4):
        raise ValueError("invalid ELF program-header table")
    alignments = []
    for i in range(count):
        base = offset + i * entry_size
        if base + entry_size > len(data):
            raise ValueError("truncated ELF program headers")
        kind = struct.unpack_from("<I", data, base)[0]
        if kind == 1:  # PT_LOAD
            fmt = "<Q" if bitness == 2 else "<I"
            alignments.append(struct.unpack_from(fmt, data, base + align_offset)[0])
    if not alignments:
        raise ValueError("no ELF PT_LOAD segments")
    return bitness, machine, min(alignments)


def audit(apk, strict=True):
    problems = []
    found = collections.defaultdict(set)
    total_payload = 0
    total_packed = 0
    with zipfile.ZipFile(apk) as archive:
        for member in archive.infolist():
            name = member.filename
            if not name.startswith("lib/") or not name.endswith(".so"):
                continue
            parts = name.split("/")
            if len(parts) != 3 or parts[1] not in ABIS:
                problems.append(f"unexpected native library path: {name}")
                continue
            abi, filename = parts[1], parts[2]
            if filename in found[abi]:
                problems.append(f"duplicate library: {name}")
            found[abi].add(filename)
            total_payload += member.file_size
            total_packed += member.compress_size
            try:
                elf_class, machine, alignment = elf_metadata(archive.read(member))
                if (elf_class, machine) != ABIS[abi]:
                    problems.append(f"{name}: ELF class/CPU mismatch ({elf_class}, {machine})")
                if strict and elf_class == 2 and alignment < 16384:
                    problems.append(f"{name}: PT_LOAD alignment {alignment}, requires 16384")
            except Exception as exc:
                problems.append(f"{name}: {exc}")
    for abi in ABIS:
        missing = REQUIRED - found[abi]
        if missing:
            problems.append(f"{abi}: missing {', '.join(sorted(missing))}")
    unique = [tuple(sorted(found[abi])) for abi in ABIS]
    if len(set(unique)) != 1:
        problems.append("native library sets differ across ABI variants")
    print(f"APK: {apk}")
    print(f"Native ELF payload: {total_payload:,} bytes")
    print(f"Native data in APK: {total_packed:,} bytes")
    for abi in ABIS:
        print(f"  {abi}: {', '.join(sorted(found[abi]))}")
    for issue in problems:
        print("ERROR:", issue)
    return not problems


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--report-only", action="store_true",
                        help="temporary diagnostic: report 16 KiB findings without failing")
    args = parser.parse_args()
    if not audit(args.apk, strict=not args.report_only):
        sys.exit(1)
