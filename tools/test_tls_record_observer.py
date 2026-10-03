import unittest
from tls_record_observer import ClientHelloCounter, Observations


def hello(body=b'controlled-fixture-only'):
    return b'\x01' + len(body).to_bytes(3, 'big') + body


def record(payload):
    return b'\x16\x03\x01' + len(payload).to_bytes(2, 'big') + payload


class ObserverTests(unittest.TestCase):
    def test_single_record(self):
        c = ClientHelloCounter(); h = hello()
        self.assertEqual({'records': 1, 'bytes': len(h)}, c.feed(record(h)))
        self.assertEqual(0, c.retained_bytes)

    def test_three_records_across_arbitrary_tcp_chunks(self):
        c = ClientHelloCounter(); h = hello(); stream = record(h[:9]) + record(h[9:-2]) + record(h[-2:])
        results = [r for b in stream if (r := c.feed(bytes([b]))) is not None]
        self.assertEqual([{'records': 3, 'bytes': len(h)}], results)
        self.assertEqual(0, c.retained_bytes)

    def test_handshake_header_can_span_records(self):
        c = ClientHelloCounter(); h = hello()
        self.assertEqual({'records': 2, 'bytes': len(h)}, c.feed(record(h[:2]) + record(h[2:])))

    def test_incomplete_record_does_not_claim_observation(self):
        c = ClientHelloCounter(); self.assertIsNone(c.feed(record(hello())[:-1])); self.assertFalse(c.finished)

    def test_application_data_is_not_counted_as_clienthello(self):
        c = ClientHelloCounter(); self.assertIsNone(c.feed(b'\x17\x03\x03\x00\x01x'))
        self.assertTrue(c.invalid); self.assertEqual(0, c.retained_bytes)

    def test_wrong_handshake_type_is_rejected(self):
        c = ClientHelloCounter(); self.assertIsNone(c.feed(record(b'\x02\x00\x00\x01x'))); self.assertTrue(c.invalid)

    def test_oversized_record_and_hello_are_rejected(self):
        for stream in [b'\x16\x03\x03\xff\xff', record(b'\x01\xff\xff\xffx')]:
            c = ClientHelloCounter(); self.assertIsNone(c.feed(stream)); self.assertTrue(c.invalid)
            self.assertEqual(0, c.retained_bytes)

    def test_no_second_observation_or_payload_retention(self):
        c = ClientHelloCounter(); self.assertIsNotNone(c.feed(record(hello())))
        self.assertIsNone(c.feed(record(hello(b'not-exported')))); self.assertEqual(0, c.retained_bytes)

    def test_metadata_ring_is_bounded_and_contains_no_payload(self):
        observations = Observations()
        for i in range(50): observations.add({'records': 3, 'bytes': 100})
        snapshot = observations.snapshot(); self.assertEqual(50, snapshot['latest'])
        self.assertEqual(32, len(snapshot['observations']))
        self.assertEqual({'sequence', 'records', 'bytes'}, set(snapshot['observations'][0]))


if __name__ == '__main__': unittest.main()
