import queue
import time
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote


class ReportServer:
    """Serves fixed pages and collects what they send to /report?..."""

    def __init__(self, port, pages, rates=None):
        """rates: path -> bytes per second, to send that page slowly."""
        rates = rates if rates is not None else {}
        self.port = port
        self.reports = queue.Queue()
        self.requests = []
        reports = self.reports
        requests = self.requests

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append(self.path)
                if self.path.startswith("/report?"):
                    reports.put(unquote(self.path[len("/report?"):]))
                    body = b"ok"
                else:
                    body = pages.get(self.path.split("?")[0].split("#")[0], b"")
                self.send_response(200)
                self.send_header("Content-Type", "text/html")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                rate = rates.get(self.path.split("?")[0])
                if not rate:
                    self.wfile.write(body)
                    return
                step = 64 * 1024
                try:
                    for i in range(0, len(body), step):
                        self.wfile.write(body[i:i + step])
                        time.sleep(step / rate)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def log_message(self, *args):
                pass

        self.http = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        threading.Thread(target=self.http.serve_forever, daemon=True).start()

    def attach(self, device):
        device.adb("reverse", f"tcp:{self.port}", f"tcp:{self.port}")

    def report(self, timeout=20):
        try:
            return self.reports.get(timeout=timeout)
        except queue.Empty:
            return None

    def close(self):
        self.http.shutdown()


TOOLBAR_PORT = 8782
TOOLBAR_PAGE = b"""<!doctype html><meta name=viewport content="width=device-width">
<body style="margin:0;height:600vh;background:linear-gradient(#fff,#88f)">long page</body>"""


def serve_toolbar_page():
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(TOOLBAR_PAGE)))
            self.end_headers()
            self.wfile.write(TOOLBAR_PAGE)

        def log_message(self, *args):
            pass

    http = ThreadingHTTPServer(("127.0.0.1", TOOLBAR_PORT), Handler)
    threading.Thread(target=http.serve_forever, daemon=True).start()
    return http
