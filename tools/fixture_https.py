"""TLS handshake belongs to each worker, not the listening accept loop.
A deliberately cancelled probe may leave an idle TCP connection; it must not
block unrelated controlled TLS checks. Fixture only, never production TLS.
"""
import http.server
import ssl

class ThreadedTLSHTTPServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, handler, context):
        self.tls_context = context
        super().__init__(address, handler)

    def process_request_thread(self, request, client_address):
        try:
            request.settimeout(5)
            request = self.tls_context.wrap_socket(request, server_side=True)
            super().process_request_thread(request, client_address)
        except (OSError, ssl.SSLError):
            request.close()
