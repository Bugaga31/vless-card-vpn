"""Controlled fixture only: count TLS records carrying ClientHello, never export payloads."""
import collections
import socket
import socketserver
import threading


class ClientHelloCounter:
    MAX_HELLO = 65536
    MAX_RECORD = 18432

    def __init__(self):
        self._wire = bytearray()
        self._hello = bytearray()
        self.records = 0
        self.finished = False
        self.invalid = False

    def _stop(self, invalid=False):
        self.finished = True
        self.invalid = invalid
        self._wire.clear()
        self._hello.clear()

    @property
    def retained_bytes(self):
        return len(self._wire) + len(self._hello)

    def feed(self, data):
        if self.finished:
            return None
        # recv() chunks in the relay are 4096 bytes; bound arbitrary test callers too.
        if len(self._wire) + len(data) > self.MAX_HELLO + self.MAX_RECORD:
            self._stop(True)
            return None
        self._wire.extend(data)
        while len(self._wire) >= 5:
            kind, major, minor = self._wire[:3]
            size = int.from_bytes(self._wire[3:5], 'big')
            if kind != 22 or major != 3 or minor > 4 or not 0 < size <= self.MAX_RECORD:
                self._stop(True)
                return None
            if len(self._wire) < size + 5:
                return None
            self.records += 1
            self._hello.extend(self._wire[5:5 + size])
            del self._wire[:5 + size]
            if self.records > 64 or len(self._hello) > self.MAX_HELLO:
                self._stop(True)
                return None
            if self._hello and self._hello[0] != 1:  # ClientHello handshake type
                self._stop(True)
                return None
            if len(self._hello) >= 4:
                expected = 4 + int.from_bytes(self._hello[1:4], 'big')
                if not 4 < expected <= self.MAX_HELLO:
                    self._stop(True)
                    return None
                if len(self._hello) >= expected:
                    result = {'records': self.records, 'bytes': expected}
                    self._stop()
                    return result
        return None


class Observations:
    def __init__(self):
        self._lock = threading.Lock()
        self._rows = collections.deque(maxlen=32)
        self._sequence = 0

    def add(self, result):
        with self._lock:
            self._sequence += 1
            self._rows.append({'sequence': self._sequence, **result})

    def snapshot(self):
        with self._lock:
            return {'latest': self._sequence, 'observations': list(self._rows)}


def create_relay(observations, listen=('127.0.0.1', 26443), target=('127.0.0.1', 24443)):
    class Server(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True

    class Relay(socketserver.BaseRequestHandler):
        def handle(self):
            try:
                upstream = socket.create_connection(target, timeout=5)
            except OSError:
                return
            with upstream:
                upstream.settimeout(25)
                self.request.settimeout(25)
                counter = ClientHelloCounter()

                def reverse():
                    try:
                        while True:
                            data = upstream.recv(4096)
                            if not data:
                                break
                            self.request.sendall(data)
                    except OSError:
                        pass
                    finally:
                        try:
                            self.request.shutdown(socket.SHUT_WR)
                        except OSError:
                            pass

                thread = threading.Thread(target=reverse, daemon=True)
                thread.start()
                try:
                    while True:
                        data = self.request.recv(4096)
                        if not data:
                            break
                        result = counter.feed(data)
                        if result is not None:
                            observations.add(result)
                        upstream.sendall(data)  # Byte-for-byte relay, no TLS termination here.
                except OSError:
                    pass
                finally:
                    try:
                        upstream.shutdown(socket.SHUT_WR)
                    except OSError:
                        pass
                    thread.join(timeout=1)

    return Server(listen, Relay)
