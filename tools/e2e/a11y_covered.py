"""A page covered by a full-screen page of the browser (settings, the secure storage) is not in the accessibility tree:
TalkBack and other services must not reach a site's content under the storage.
Usage: python3 -I tools/e2e/a11y_covered.py --serial SERIAL
"""
import argparse
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from nav import open_settings  # noqa: E402
from vault import open_vault  # noqa: E402

PORT = 8784
MARK = "covered-page-marker"
TITLE = "covered-page-title"
PAGE = (f'<!doctype html><title>{TITLE}</title><meta name=viewport content="width=device-width">'
        f'<h1>{MARK}</h1><button>{MARK}-button</button>'
        f'<video aria-label="{MARK}-video" controls style="width:100%;height:60vh;background:#000"></video>').encode()


def leaked(d):
    """Page text or title anywhere in the tree, the title also as a description: GeckoView labels its own node with it."""
    return [n.get("text") or n.get("content-desc") for n in d.nodes()
            if any(m in (n.get("text") or "") + (n.get("content-desc") or "") for m in (MARK, TITLE))]


def serve():
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(PAGE)))
            self.end_headers()
            self.wfile.write(PAGE)

        def log_message(self, *args):
            pass

    http = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=http.serve_forever, daemon=True).start()
    return http


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    http = serve()
    d.adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    try:
        d.stop()
        d.launch(f"http://localhost:{PORT}/")
        d.wait(MARK, timeout=30)
        print("1. the page is in the tree while it is shown")

        open_settings(d)
        time.sleep(1)
        if leaked(d):
            d.fail(f"the page is in the accessibility tree under the settings: {leaked(d)}")
        print("2. not under the settings")
        d.shell("input keyevent KEYCODE_BACK")
        time.sleep(1.5)
        d.wait(MARK, timeout=10)
        print("3. back in the tree when the settings close")

        open_vault(d)
        time.sleep(1)
        if leaked(d):
            d.fail(f"the page is in the accessibility tree under the secure storage: {leaked(d)}")
        print("4. not under the secure storage")

        field = d.find_class("android.widget.EditText")
        d.shell("input tap %d %d" % d.center(field))
        d.type("zz")
        time.sleep(1.5)
        if leaked(d):
            d.fail(f"the page is in the accessibility tree under the storage with the keyboard up: {leaked(d)}")
        print("5. not under the storage while its search has the keyboard")
    finally:
        http.shutdown()
    print("OK")


if __name__ == "__main__":
    main()
