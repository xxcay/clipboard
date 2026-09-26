"""Writes a test photo (orange gradient with a white sun) as PNG, stdlib only."""
import struct
import sys
import zlib

W, H = 900, 600


def pixel(x, y):
    t = (x + y) / (W + H)
    r, g, b = 255, int(176 - 86 * t), int(112 - 81 * t)
    if (x - W / 2) ** 2 + (y - H / 2) ** 2 < 150 ** 2:
        r, g, b = 255, 250, 240
    return bytes((r, g, b))


rows = b"".join(b"\0" + b"".join(pixel(x, y) for x in range(W)) for y in range(H))


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0)) \
    + chunk(b"IDAT", zlib.compress(rows, 6)) + chunk(b"IEND", b"")
open(sys.argv[1], "wb").write(png)
