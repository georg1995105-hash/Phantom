#!/usr/bin/env python3
"""Produce a byte-exact, streaming-friendly APK ZIP delta without third-party deps.

PHDELTA1:
  8-byte magic; >QQ base_size,target_size; 32-byte old/new SHA-256; >I op_count.
  Each operation is either >BQI (0, old byte offset, size) or >BI (1, size)
  followed by literal target bytes. All lengths are bounded during application.
  This copies unchanged *compressed* ZIP entry payloads, including native libs,
  without attempting to modify a signed APK. The *complete* new signed APK is
  reconstructed byte for byte and verified against its GitHub release SHA-256.
"""
import argparse
import hashlib
import os
import struct
import tempfile
import zipfile

MAGIC = b"PHDELTA1"
BLOCK = 128 * 1024
INSERT_MAX = 16 * 1024 * 1024
MAX_OPS = 200000


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(BLOCK), b""):
            digest.update(chunk)
    return digest.digest()


def compressed_span(stream, entry):
    stream.seek(entry.header_offset)
    header = stream.read(30)
    if len(header) != 30:
        raise ValueError("truncated local ZIP entry")
    signature, _, _, _, _, _, _, _, _, nlen, xlen = struct.unpack("<IHHHHHIIIHH", header)
    if signature != 0x04034B50:
        raise ValueError("invalid local ZIP signature")
    return entry.header_offset + 30 + nlen + xlen, entry.compress_size


def bytes_equal(old, old_at, new, new_at, size):
    old.seek(old_at)
    new.seek(new_at)
    while size:
        count = min(BLOCK, size)
        if old.read(count) != new.read(count):
            return False
        size -= count
    return True


def operations(base_path, target_path):
    result = []
    with zipfile.ZipFile(base_path) as original, zipfile.ZipFile(target_path) as updated, \
         open(base_path, "rb") as old, open(target_path, "rb") as new:
        entries = {entry.filename: entry for entry in original.infolist()}
        regions = []
        for candidate in updated.infolist():
            previous = entries.get(candidate.filename)
            if previous is None or candidate.file_size != previous.file_size or \
                    candidate.CRC != previous.CRC or \
                    candidate.compress_type != previous.compress_type or \
                    candidate.compress_size != previous.compress_size or candidate.compress_size < 4096:
                continue
            new_at, length = compressed_span(new, candidate)
            old_at, _ = compressed_span(old, previous)
            if bytes_equal(old, old_at, new, new_at, length):
                regions.append((new_at, old_at, length))
        regions.sort()
        position = 0
        target_length = os.path.getsize(target_path)
        for offset, old_at, length in regions:
            if offset < position:
                raise ValueError("overlapping ZIP entry data")
            while position < offset:
                count = min(INSERT_MAX, offset - position)
                result.append((1, position, count))
                position += count
            result.append((0, old_at, length))
            position += length
        while position < target_length:
            count = min(INSERT_MAX, target_length - position)
            result.append((1, position, count))
            position += count
    if len(result) > MAX_OPS:
        raise ValueError("unusually many delta operations")
    return result


def make_patch(base, target, output, max_ratio=0.80):
    old_length, new_length = os.path.getsize(base), os.path.getsize(target)
    if not (1024 <= old_length < 250 * 1024 * 1024 and
            1024 <= new_length < 250 * 1024 * 1024):
        raise ValueError("unsupported APK size")
    ops = operations(base, target)
    literal_bytes = sum(size for mode, _, size in ops if mode == 1)
    predicted = 92 + len(ops) * 13 + literal_bytes
    if predicted >= new_length * max_ratio:
        print(f"SKIP delta: {predicted} >= {max_ratio:.0%} of full APK {new_length}")
        return False
    os.makedirs(os.path.dirname(os.path.abspath(output)), exist_ok=True)
    temp = output + ".tmp"
    try:
        with open(temp, "wb") as dst, open(target, "rb") as new:
            dst.write(MAGIC)
            dst.write(struct.pack(">QQ32s32sI", old_length, new_length,
                                  sha256_file(base), sha256_file(target), len(ops)))
            for mode, offset, size in ops:
                if mode == 0:
                    dst.write(struct.pack(">BQI", mode, offset, size))
                else:
                    dst.write(struct.pack(">BI", mode, size))
                    new.seek(offset)
                    remaining = size
                    while remaining:
                        chunk = new.read(min(BLOCK, remaining))
                        if not chunk:
                            raise ValueError("target APK truncated")
                        dst.write(chunk)
                        remaining -= len(chunk)
        verify_patch(base, temp, target)
        os.replace(temp, output)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)
    print(f"Delta verified: {os.path.getsize(output)} of {new_length} bytes "
          f"({100 * os.path.getsize(output) / new_length:.1f}%), "
          f"{len(ops)} operations")
    return True


def verify_patch(base, patch, target):
    old_length = os.path.getsize(base)
    new_length = os.path.getsize(target)
    with open(base, "rb") as old, open(patch, "rb") as source:
        if source.read(8) != MAGIC:
            raise ValueError("invalid delta magic")
        length, output_size, old_hash, new_hash, count = struct.unpack(
            ">QQ32s32sI", source.read(84))
        if length != old_length or output_size != new_length or count > MAX_OPS:
            raise ValueError("invalid delta header")
        if sha256_file(base) != old_hash or sha256_file(target) != new_hash:
            raise ValueError("delta source or target hash mismatch")
        with tempfile.TemporaryFile() as dest:
            actual_hash = hashlib.sha256()
            remaining_output = output_size
            for _ in range(count):
                mode_raw = source.read(1)
                if not mode_raw:
                    raise ValueError("truncated delta operation")
                mode = mode_raw[0]
                if mode == 0:
                    offset, size = struct.unpack(">QI", source.read(12))
                    if offset > old_length or size > old_length - offset:
                        raise ValueError("invalid source copy range")
                    old.seek(offset)
                    stream = old
                elif mode == 1:
                    (size,) = struct.unpack(">I", source.read(4))
                    stream = source
                else:
                    raise ValueError("unknown delta instruction")
                if size > remaining_output:
                    raise ValueError("delta expands target beyond expected length")
                remaining_output -= size
                while size:
                    chunk = stream.read(min(BLOCK, size))
                    if not chunk:
                        raise ValueError("truncated delta payload")
                    actual_hash.update(chunk)
                    dest.write(chunk)
                    size -= len(chunk)
            if remaining_output != 0 or source.read(1):
                raise ValueError("delta does not match expected exact length")
            if actual_hash.digest() != new_hash:
                raise ValueError("reconstructed APK digest differs")
    return True


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--max-ratio", type=float, default=0.80)
    args = parser.parse_args()
    make_patch(args.base, args.target, args.output, args.max_ratio)
