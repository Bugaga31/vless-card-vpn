#!/usr/bin/env python3
"""Reject blank/corrupt screencaps, standard library only; not full visual QA."""
import struct
import sys
import zlib
from pathlib import Path


def require(ok, message):
    if not ok:
        raise ValueError(message)


def check_png(data):
    require(len(data) <= 20_000_000 and data[:8] == b'\x89PNG\r\n\x1a\n', 'Invalid PNG')
    offset = 8
    compressed = bytearray()
    header = None
    ended = False
    while offset + 12 <= len(data):
        size = struct.unpack('!I', data[offset:offset+4])[0]
        tag = data[offset+4:offset+8]
        payload = data[offset+8:offset+8+size]
        require(offset+12+size <= len(data), 'Truncated PNG')
        crc = struct.unpack('!I', data[offset+8+size:offset+12+size])[0]
        require(zlib.crc32(tag+payload) & 0xffffffff == crc, 'PNG CRC mismatch')
        if tag == b'IHDR':
            require(header is None and size == 13, 'Invalid header')
            header = struct.unpack('!IIBBBBB', payload)
        elif tag == b'IDAT':
            compressed.extend(payload)
        elif tag == b'IEND':
            ended = True
            break
        offset += size+12
    require(header is not None and ended, 'Incomplete PNG')
    width, height, depth, color, compression, filtering, interlace = header
    require(32 <= width <= 4096 and 32 <= height <= 4096, 'Invalid screen dimensions')
    require(depth == 8 and color in (2, 6) and (compression, filtering, interlace) == (0, 0, 0), 'Unsupported PNG format')
    channels = 3 if color == 2 else 4
    stride = width*channels
    expected = height*(stride+1)
    require(expected <= 50_000_000, 'PNG too large')
    dec = zlib.decompressobj()
    raw = dec.decompress(compressed, expected+1)
    require(len(raw) == expected and dec.eof and not dec.unused_data, 'Invalid PNG pixels')
    previous = bytearray(stride)
    histogram = [0]*256
    # Ignore system bars and screen edges; count content, not clock/icons.
    for y in range(height):
        start = y*(stride+1)
        method = raw[start]
        require(method <= 4, 'Invalid PNG filter')
        row = bytearray(raw[start+1:start+1+stride])
        if method:
            for x in range(stride):
                left = row[x-channels] if x >= channels else 0
                above = previous[x]
                corner = previous[x-channels] if x >= channels else 0
                if method == 1: predictor = left
                elif method == 2: predictor = above
                elif method == 3: predictor = (left+above)//2
                else:
                    p = left+above-corner
                    distances = (abs(p-left), abs(p-above), abs(p-corner))
                    predictor = (left, above, corner)[distances.index(min(distances))]
                row[x] = (row[x]+predictor) & 255
        if height//10 <= y < height*9//10:
            for x in range(width//10, width*9//10, 3):
                i = x*channels
                histogram[max(row[i:i+3])] += 1
        previous = row
    total = sum(histogram)
    bright = sum(histogram[40:])
    require(total > 0 and bright >= max(20, total//1000), 'Blank/dark screen content')
    require(max(histogram) < total*0.999, 'Uniform screen content')
    return width, height


if __name__ == '__main__':
    try:
        w, h = check_png(Path(sys.argv[1]).read_bytes())
        print(f'Screenshot nonblank: {w}x{h}; full visual QA still required')
    except (ValueError, OSError, IndexError, zlib.error) as exc:
        raise SystemExit(str(exc))
