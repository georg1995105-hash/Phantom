#!/usr/bin/env python3
"""Repackage a pinned libbox gomobile AAR to coexist with AndroidLibXrayLite.

Both ship go.Seq and libgojni.so, but hold unrelated Go runtimes. Renaming one
without its ELF JNI hash table breaks JNI resolution at runtime. Fail closed
whenever the expected binary layout changes. No executable code is fetched
from the Internet; CI checks the upstream AAR SHA-256 first.
"""
import io
import struct
import sys
import zipfile

CLASS_REPLACEMENTS = [
    (b"go/Seq", b"sb/Seq"),
    (b"go/Universe", b"sb/Universe"),
    (b"go/error", b"sb/error"),
    (b"go.Seq", b"sb.Seq"),
    (b"go.Universe", b"sb.Universe"),
    (b"gojni", b"sbjni"),
]
NATIVE_REPLACEMENTS = [
    (b"Java_go_Seq", b"Java_sb_Seq"),
    (b"Java_go_Universe", b"Java_sb_Universe"),
    (b"go/Seq", b"sb/Seq"),
    (b"go/Universe", b"sb/Universe"),
]
for old, new in CLASS_REPLACEMENTS + NATIVE_REPLACEMENTS:
    assert len(old) == len(new)

DT_HASH = 4
DT_STRTAB = 5
DT_SYMTAB = 6
DT_SYMENT = 11
DT_GNU_HASH = 0x6FFFFEF5


def elf_hash(name):
    result = 0
    for byte in name:
        result = ((result << 4) + byte) & 0xFFFFFFFF
        high = result & 0xF0000000
        if high:
            result ^= high >> 24
        result &= ~high & 0xFFFFFFFF
    return result


class Elf:
    def __init__(self, data):
        self.d = bytearray(data)
        if self.d[:4] != b"\x7fELF":
            raise ValueError("Missing ELF magic")
        self.is64 = self.d[4] == 2
        self.loads = []
        self.dynoff = None
        phoff = struct.unpack_from("<Q" if self.is64 else "<I", self.d, 32 if self.is64 else 28)[0]
        entsize, count = struct.unpack_from("<HH", self.d, 54 if self.is64 else 42)
        for i in range(count):
            off = phoff + i * entsize
            kind = struct.unpack_from("<I", self.d, off)[0]
            if self.is64:
                file_off, virt = struct.unpack_from("<QQ", self.d, off + 8)
                size = struct.unpack_from("<Q", self.d, off + 32)[0]
            else:
                file_off, virt = struct.unpack_from("<II", self.d, off + 4)
                size = struct.unpack_from("<I", self.d, off + 16)[0]
            if kind == 1:
                self.loads.append((virt, file_off, size))
            if kind == 2:
                self.dynoff = file_off
        if self.dynoff is None:
            raise ValueError("Missing ELF dynamic section")

    @property
    def dynamic_format(self):
        return ("<qQ", 16) if self.is64 else ("<iI", 8)

    def entries(self):
        fmt, size = self.dynamic_format
        result = []
        position = self.dynoff
        for i in range(500):
            tag, value = struct.unpack_from(fmt, self.d, position)
            result.append((position, tag, value))
            if tag == 0:
                return result
            position += size
        raise ValueError("Unterminated dynamic table")

    def tags(self):
        return {t: v for _, t, v in self.entries()}

    def offset(self, virt):
        for start, off, length in self.loads:
            if start <= virt < start + length:
                return off + virt - start
        raise ValueError(f"Unmapped ELF address: {virt:#x}")

    def rebuild_symbols(self):
        entries = self.entries()
        tags = self.tags()
        if DT_HASH not in tags:
            raise ValueError("Native library has no SysV symbol table")
        if DT_GNU_HASH in tags:
            gnu_index = next(i for i, (_, tag, _) in enumerate(entries) if tag == DT_GNU_HASH)
            last_real = len(entries) - 2
            fmt, _ = self.dynamic_format
            if gnu_index != last_real:
                _, tag, value = entries[last_real]
                struct.pack_into(fmt, self.d, entries[gnu_index][0], tag, value)
            struct.pack_into(fmt, self.d, entries[last_real][0], 0, 0)
        tags = self.tags()
        if DT_GNU_HASH in tags:
            raise ValueError("GNU hash remains active after replacement")
        hash_at = self.offset(tags[DT_HASH])
        symbols_at = self.offset(tags[DT_SYMTAB])
        names_at = self.offset(tags[DT_STRTAB])
        entry_len = tags.get(DT_SYMENT, 24 if self.is64 else 16)
        nbuckets, nsymbols = struct.unpack_from("<II", self.d, hash_at)
        if nbuckets == 0 or nsymbols == 0:
            raise ValueError("Empty ELF hash table")
        buckets = [0] * nbuckets
        chains = [0] * nsymbols
        def symbol_name(i):
            name_offset = struct.unpack_from("<I", self.d, symbols_at + i * entry_len)[0]
            begin = names_at + name_offset
            return bytes(self.d[begin:self.d.index(0, begin)])
        for i in range(nsymbols - 1, 0, -1):
            slot = elf_hash(symbol_name(i)) % nbuckets
            chains[i] = buckets[slot]
            buckets[slot] = i
        struct.pack_into(f"<{nbuckets}I", self.d, hash_at + 8, *buckets)
        struct.pack_into(f"<{nsymbols}I", self.d,
                         hash_at + 8 + 4 * nbuckets, *chains)
        for probe in (b"Java_sb_Seq_init", b"Java_sb_Seq_setContext"):
            bucket = buckets[elf_hash(probe) % nbuckets]
            while bucket and symbol_name(bucket) != probe:
                bucket = chains[bucket]
            if not bucket:
                raise ValueError("JNI export missing from repaired hash: " + probe.decode())
        return bytes(self.d)


def patch_classes(data):
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        entries = [(x.filename, jar.read(x.filename)) for x in jar.infolist()]
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as jar:
        for filename, blob in entries:
            if filename.endswith(".class"):
                for old, new in CLASS_REPLACEMENTS:
                    blob = blob.replace(old, new)
            if filename.startswith("go/"):
                filename = "sb/" + filename[3:]
            jar.writestr(filename, blob)
    return out.getvalue()


def main(source, target):
    with zipfile.ZipFile(source) as z:
        entries = [(x.filename, z.read(x.filename)) for x in z.infolist()]
    result = []
    native_count = 0
    for name, data in entries:
        if name == "classes.jar":
            data = patch_classes(data)
        if name.startswith("jni/") and name.endswith("/libgojni.so"):
            if b"Java_go_Seq_init" not in data:
                raise ValueError("Expected JNI symbol not found")
            for old, new in NATIVE_REPLACEMENTS:
                data = data.replace(old, new)
            data = Elf(data).rebuild_symbols()
            name = name.replace("/libgojni.so", "/libsbjni.so")
            native_count += 1
        if name == "proguard.txt":
            data = data.replace(b"go.", b"sb.")
        result.append((name, data))
    if native_count < 1:
        native_files = [n for n, _ in entries if n.endswith(".so") or n.endswith(".aar")]
        jar_blob = next((blob for n, blob in entries if n == "classes.jar"), None)
        if jar_blob is not None:
            with zipfile.ZipFile(io.BytesIO(jar_blob)) as jar:
                classes = [n for n in jar.namelist() if n.endswith(".class")]
                print("AAR Java classes sample:", classes[:30], flush=True)
                print("AAR Go runtime classes:",
                      [n for n in classes if n.startswith("go/")][:30], flush=True)
        raise ValueError("No gomobile libgojni.so found; archive native entries: " + repr(native_files[:25]))
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in result:
            z.writestr(name, data)
    with zipfile.ZipFile(target) as z:
        assert not any(x.endswith("/libgojni.so") for x in z.namelist())
        with zipfile.ZipFile(io.BytesIO(z.read("classes.jar"))) as jar:
            assert not any(x.startswith("go/") for x in jar.namelist())
    print(f"Isolated libbox: {native_count} JNI library variants, hash verified")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: isolate_libbox_aar.py original.aar isolated.aar")
    main(sys.argv[1], sys.argv[2])
