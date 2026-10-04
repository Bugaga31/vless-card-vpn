"""Strict, loopback-fixture DNS answers. Not a general resolver or public service."""
import re
import struct

_FRESH = re.compile(r"[a-f0-9]{32}-(?:default|bound)\.fixture\.test\Z")


def reply(data):
    if not 12 <= len(data) <= 512 or data[4:6] != b"\0\1":
        return None
    offset, labels = 12, []
    while offset < len(data):
        size = data[offset]
        offset += 1
        if size == 0:
            break
        if size > 63 or offset + size > len(data):
            return None
        try:
            labels.append(data[offset:offset + size].decode("ascii"))
        except UnicodeDecodeError:
            return None
        offset += size
    if offset + 4 > len(data):
        return None
    qtype, qclass = struct.unpack("!HH", data[offset:offset + 4])
    end = offset + 4
    name = ".".join(labels).lower()
    valid = qclass == 1 and (name == "fixture.test" or bool(_FRESH.fullmatch(name)))
    answer = b""
    if valid and qtype == 1:
        answer = b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 0, 4) + bytes([198, 18, 0, 1])
    header = data[:2] + struct.pack("!HHHHH", 0x8180 if valid else 0x8183, 1, 1 if answer else 0, 0, 0)
    return header + data[12:end] + answer
