import struct
import unittest
from fixture_dns import reply


def query(name, qtype=1, qclass=1):
    wire = b"".join(bytes([len(part)]) + part.encode("ascii") for part in name.split(".")) + b"\0"
    return struct.pack("!HHHHHH", 123, 0x0100, 1, 0, 0, 0) + wire + struct.pack("!HH", qtype, qclass)


class FixtureDnsTests(unittest.TestCase):
    def test_existing_fixture_answer_remains_supported(self):
        response = reply(query("fixture.test"))
        self.assertEqual(response[:2], struct.pack("!H", 123))
        self.assertEqual(response[-4:], bytes([198, 18, 0, 1]))
        self.assertEqual(struct.unpack("!H", response[6:8])[0], 1)

    def test_default_and_bound_get_separate_uncached_names(self):
        for kind in ["default", "bound"]:
            response = reply(query("a" * 32 + "-" + kind + ".fixture.test"))
            self.assertEqual(response[-4:], bytes([198, 18, 0, 1]))
            self.assertEqual(struct.unpack("!I", response[-10:-6])[0], 0)  # TTL zero

    def test_arbitrary_domains_are_not_answered(self):
        for name in ["example.org", "a" * 32 + "-default.fixture.test.evil.org", "bad-default.fixture.test",
                     "a" * 32 + "-other.fixture.test", "fixture.test.evil.org"]:
            response = reply(query(name))
            self.assertEqual(response[3] & 15, 3)
            self.assertEqual(struct.unpack("!H", response[6:8])[0], 0)

    def test_aaaa_returns_no_fabricated_ipv6_address(self):
        response = reply(query("fixture.test", 28))
        self.assertEqual(response[3] & 15, 0)
        self.assertEqual(struct.unpack("!H", response[6:8])[0], 0)

    def test_non_internet_class_is_rejected(self):
        self.assertEqual(reply(query("fixture.test", qclass=3))[3] & 15, 3)

    def test_malformed_queries_are_ignored(self):
        self.assertIsNone(reply(b"x" * 10))
        self.assertIsNone(reply(query("fixture.test")[:-2]))
        self.assertIsNone(reply(struct.pack("!HHHHHH", 1, 256, 1, 0, 0, 0) + b"\xc0\x0c\0\1\0\1"))


if __name__ == "__main__":
    unittest.main()
