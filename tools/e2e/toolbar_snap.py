"""Toolbar after a scroll, as in Chrome: while the finger is down it follows the page and can stop half way; once the
finger is up it finishes showing (at least half shown) or hiding (less than half shown).

Usage: python3 -I tools/e2e/toolbar_snap.py --serial SERIAL
"""
import argparse
import io
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402

PORT = 8782
PAGE = b"""<!doctype html><meta name=viewport content="width=device-width">
<body style="margin:0;height:600vh;background:linear-gradient(#fff,#88f)">long page</body>"""


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


def toolbar_bottom(d):
    """Where the dark toolbar ends on screen: the page is white, so the first light pixel below the status bar."""
    img = Image.open(io.BytesIO(d.adb("exec-out", "screencap", "-p", binary=True))).convert("L")
    x = img.width // 2
    y = 0
    while y < img.height and img.getpixel((x, y)) < 200:
        y += 1
    return y


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
    bar = 56 * density // 160
    # Gecko starts to pan after apz.touch_start_tolerance, 0.1 inch; a little more for the time the swipe is short of its end.
    slop = density * 12 // 100
    failed = []
    for name, share, expect in (("short", 0.3, "shown"), ("long", 0.95, "hidden")):
        d.stop()
        d.launch(f"http://localhost:{PORT}/{name}")
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(3)
        shown = toolbar_bottom(d)
        hidden = shown - bar
        # A slow swipe has no fling; the screenshot in its middle sees the finger still down and the toolbar half way.
        # The page view moves up with the toolbar, so the finger travels about twice the distance the toolbar hides.
        dy = int(2 * bar * share) + slop
        swipe = threading.Thread(target=d.shell, args=(f"input swipe {w // 2} {h // 2} {w // 2} {h // 2 - dy} 4000",))
        swipe.start()
        time.sleep(3.8)
        held = toolbar_bottom(d)
        swipe.join()
        time.sleep(1)
        after = toolbar_bottom(d)
        # The swipe is short of its end when the screenshot is taken, so the case checks the side of the middle it got to.
        middle = shown - bar / 2
        partial = hidden < held < shown and (held > middle) == (expect == "shown")
        ok = after == (shown if expect == "shown" else hidden)
        print(f"{name}: shown={shown} hidden={hidden} while held={held} (partial={partial}) after={after} -> {'OK' if ok else 'FAIL'}")
        if not partial:
            print("  the toolbar was not half way while the finger was down, the case proves nothing")
            failed.append(name + " (setup)")
        elif not ok:
            failed.append(name)
    http.shutdown()
    if failed:
        print("FAIL: " + ", ".join(failed))
        sys.exit(1)
    print("OK")


if __name__ == "__main__":
    main()
