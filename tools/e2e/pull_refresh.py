"""Pull down to refresh, as in Chrome: a slow pull down on a page at the top reloads it; a pull too short for the
trigger and a pull on a scrolled page do nothing. The page shows a stamp that changes on every load.

Usage: python3 -I tools/e2e/pull_refresh.py --serial SERIAL
"""
import argparse
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402

PORT = 8784
PAGE = b"""<!doctype html><meta name=viewport content="width=device-width">
<body style="margin:0;height:400vh;background:#fff">
<div id="stamp" style="font-size:40px;padding:120px 20px">load</div>
<script>document.getElementById('stamp').textContent = 'load ' + Date.now();</script>
</body>"""


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


def stamp(d):
    """The load stamp of the open page: the text that starts with 'load '."""
    n = d.find("load ")
    if n is None:
        d.fail("the page stamp is not on screen")
    return n.get("text")


def reload_stamp(d, old, timeout=15):
    """Wait until the page reloads: the stamp on screen differs from old."""
    end = time.time() + timeout
    while time.time() < end:
        s = d.find("load ")
        if s is not None and s.get("text") != old:
            return s.get("text")
        time.sleep(0.4)
    d.fail(f"the page did not reload, stamp stayed {old!r}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    http = serve()
    d.adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    w, h = (int(x) for x in d.shell("wm size").split()[-1].split("x"))
    density = int(d.shell("wm density").split()[-1])
    # SwipeRefreshLayout triggers at 120 dp; a pull below 60 dp must not reach it, a pull of 800 px must.
    trigger = 120 * density // 160
    short = 60 * density // 160
    try:
        d.stop()
        d.launch(f"http://localhost:{PORT}/")
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(3)
        first = stamp(d)
        # A slow pull from the middle of the page reloads it.
        d.shell(f"input swipe {w // 2} {h // 2} {w // 2} {h // 2 + 800} 900")
        second = reload_stamp(d, first)
        print("1. pull down reloads the page")
        time.sleep(2)
        # A pull shorter than the trigger distance does nothing.
        d.shell(f"input swipe {w // 2} {h // 2} {w // 2} {h // 2 + short} 900")
        time.sleep(2)
        if d.find(second, exact=True) is None:
            d.fail(f"a short pull reloaded the page (trigger {trigger} px)")
        print("2. a pull shorter than the trigger does nothing")
        # A pull on a scrolled page scrolls the page and does not reload it. Scroll well past the pull length, then
        # pull: if the page reloaded, it would come back to the top and show a new stamp.
        for _ in range(3):
            d.shell(f"input swipe {w // 2} {h - 300} {w // 2} 300 500")
            time.sleep(1)
        if d.find("load ") is not None:
            d.fail("the page did not scroll away from the top")
        end = time.time() + 3
        d.shell(f"input swipe {w // 2} {h // 2} {w // 2} {h // 2 + 400} 900")
        while time.time() < end:
            n = d.find("load ")
            if n is not None and n.get("text") != second:
                d.fail("a pull on a scrolled page reloaded it")
            time.sleep(0.4)
        print("3. a pull on a scrolled page does not reload")
    finally:
        http.shutdown()
    print("OK")


if __name__ == "__main__":
    main()
