import importlib.util
from pathlib import Path
import socket
import threading
import unittest

spec=importlib.util.spec_from_file_location('dns_smoke',Path(__file__).with_name('dns-failover-smoke.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class DnsRaceFixtureTest(unittest.TestCase):
    def test_healthy_reply_waits_for_silent_primary_observation(self):
        seen=threading.Event()
        primary=m.Resolver(False, signal=seen)
        healthy=m.Resolver(True, wait_for=seen)
        try:
            packet=m.query('healthy.fixture.test',0x1234)
            with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as client:
                client.settimeout(.1);client.sendto(packet,('127.0.0.1',healthy.port))
                with self.assertRaises(socket.timeout): client.recv(4096)
                client.sendto(packet,('127.0.0.1',primary.port));client.settimeout(1)
                response=client.recv(4096)
                self.assertEqual(packet[:2],response[:2]);self.assertEqual([198,18,0,1],list(response[-4:]))
                self.assertGreater(primary.count,0);self.assertGreater(healthy.count,0)
        finally:
            primary.close();healthy.close()

    def test_missing_primary_not_invented_after_bounded_wait(self):
        seen=threading.Event();healthy=m.Resolver(True,wait_for=seen)
        try:
            with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as client:
                client.settimeout(1.5);packet=m.query('healthy.fixture.test',0x1234)
                client.sendto(packet,('127.0.0.1',healthy.port));response=client.recv(4096)
                self.assertEqual(packet[:2],response[:2]);self.assertFalse(seen.is_set())
        finally:healthy.close()
