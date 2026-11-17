#!/usr/bin/env python3
"""Apply the car compatibility fixes to the exact stock 4.9.7.2 / 1472 APK."""
import hashlib
import struct
import sys
import zlib
from zipfile import ZipFile

STOCK_SHA256 = {
    "classes.dex": "12d0f6e8cdc6f8fb90ec9e6ecc23069701712d3d9ed3e1d1d2801a072900e8dc",
    "classes3.dex": "0524757a6ede0ebbb74be5de1c1350905bbc78f8e7c208fbe7b2f80abc697a9a",
}

STOCK_RESOURCES_SHA256 = "8f3f7c445f6fb79b06ae7b891c438bd15393ed014e7a5d4954ca717d1112ba09"

# Lp7/c;->b(Context)Z: require a connected supported default network via f(),
# then require the existing cellular permission preference e.k() on cellular.
# g() classifies cellular. Wi-Fi and Ethernet can use the validated default.
CONTENT_NETWORK_POLICY = bytes.fromhex(
    "1200 3801 1200 7110 88f7 0100 0a00 3800 0c00 "
    "7110 89f7 0100 0a00 3800 0700 7110 96f7 0100 0a00 "
    "0f00 1210 0f00"
)


def replace_body(dex: bytearray, offset: int, expected_units: int, code: bytes) -> None:
    registers, incoming, outs, tries, _, size = struct.unpack_from("<4H2I", dex, offset)
    if (size, tries) != (expected_units, 0) or registers < 2 or incoming != 1 or outs != 1:
        raise ValueError(f"Unexpected code item at {offset:#x}")
    body_start = offset + 16
    dex[body_start:body_start + size * 2] = code.ljust(size * 2, b"\0")


def patch_dex(name: str, original: bytes) -> bytes:
    if hashlib.sha256(original).hexdigest() != STOCK_SHA256[name]:
        raise ValueError(f"{name} differs from the verified Apple Music 4.9.7.2 binary")
    dex = bytearray(original)
    if name == "classes.dex":
        # AppleMusicApplication.e()Z: both return paths now return false.
        root_return = 0x344460
        if dex[root_return:root_return + 8] != bytes.fromhex("12100f0012000f00"):
            raise ValueError("Unexpected instructions in AppleMusicApplication.e()Z")
        dex[root_return + 1] = 0
        # BaseViewModel and ListenNowViewModel: use the content network policy.
        content_policy = bytes.fromhex("6201 9b2f 6e10 a625 0100 0c01 7110 51d5 0100 0a00 0f00")
        replace_body(dex, 0x3D0BD0, 48, content_policy)
        replace_body(dex, 0x474D84, 48, content_policy)
    else:
        replace_body(dex, 0x48637C, 22, CONTENT_NETWORK_POLICY)
        # BaseMediaApiRoomViewModel delegates to the same policy with local DEX IDs.
        content_policy = bytes.fromhex("6201 6708 6e10 660e 0100 0c01 7110 84f7 0100 0a00 0f00")
        replace_body(dex, 0x323874, 48, content_policy)
        # In the lyrics load gate, use content connectivity in place of Wi-Fi only.
        lyrics_start = 0x1FEEC4 + 16
        lyrics_end = lyrics_start + 327 * 2
        old, new = bytes.fromhex("7110 8af7 0400"), bytes.fromhex("7110 84f7 0400")
        body = dex[lyrics_start:lyrics_end]
        if body.count(old) != 1:
            raise ValueError("Unexpected lyrics network check")
        dex[lyrics_start:lyrics_end] = body.replace(old, new)
    dex[12:32] = hashlib.sha1(dex[32:]).digest()
    struct.pack_into("<I", dex, 8, zlib.adler32(dex[12:]) & 0xffffffff)
    return bytes(dex)


def patch_resources(original: bytes) -> bytes:
    if hashlib.sha256(original).hexdigest() != STOCK_RESOURCES_SHA256:
        raise ValueError("resources.arsc differs from the verified Apple Music 4.9.7.2 binary")
    # dimen/lyrics_line_text_size (0x7f0701d0), w640dp qualifier.
    offset = 0x4A6318
    if struct.unpack_from("<I", original, offset)[0] != 0x1802:
        raise ValueError("Unexpected w640dp lyric text size")
    resources = bytearray(original)
    struct.pack_into("<I", resources, offset, 0x2402)  # 36sp
    return bytes(resources)


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("Usage: patch-zeekr-1472-host.py ORIGINAL_APK NEW_UNSIGNED_APK")
    with ZipFile(sys.argv[1]) as original:
        replacements = {name: patch_dex(name, original.read(name)) for name in STOCK_SHA256}
        replacements["resources.arsc"] = patch_resources(original.read("resources.arsc"))
        with ZipFile(sys.argv[2], "x") as patched:
            for entry in original.infolist():
                directory, _, name = entry.filename.rpartition("/")
                if directory == "META-INF" and (
                    name.upper() == "MANIFEST.MF" or name.upper().endswith((".SF", ".RSA", ".DSA", ".EC"))
                ):
                    continue
                data = replacements[entry.filename] if entry.filename in replacements else original.read(entry)
                patched.writestr(entry, data)


if __name__ == "__main__":
    main()
