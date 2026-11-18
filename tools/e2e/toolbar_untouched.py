"""The toolbar moves only with the user's gestures, as in Chrome, and never stays hidden with no way back.
- jump: the page scrolls itself (window.scrollTo) without a touch: the toolbar stays shown.
- back: a page that scrolled itself, a tap on a link to another page, then Back: the restored scroll of the first page does not hide the toolbar.
- lock: the toolbar is hidden by a swipe, then the page stops scrolling (overflow: hidden, as menus and dialogs do):
  a swipe down that scrolls nothing brings the toolbar back.
Usage: python3 -I tools/e2e/toolbar_untouched.py --serial SERIAL
"""
import argparse
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from PIL import Image  # noqa: E402
import io  # noqa: E402

PORT = 8783
LONG = '<body style="margin:0;height:600vh;background:#fff">'
PAGES = {
    "/jump": '<!doctype html><meta name=viewport content="width=device-width">' + LONG +
             '<script>setTimeout(() => window.scrollTo(0, 2000), 3000)</script></body>',
    # The whole page is a link to /b: Firefox skips history entries made without the user, so the test taps it.
    "/a": '<!doctype html><meta name=viewport content="width=device-width">' + LONG +
          '<a href="/b" style="display:block;height:100%"></a>'
          '<script>setTimeout(() => window.scrollTo(0, 2000), 1500)</script></body>',
    "/b": '<!doctype html><meta name=viewport content="width=device-width"><body style="margin:0;background:#fff">b</body>',
    "/lock": '<!doctype html><meta name=viewport content="width=device-width">' + LONG +
             '<script>setTimeout(() => document.body.style.overflow = "hidden", 6000)</script></body>',
}


def toolbar_bottom(d):
    """Where the dark toolbar ends: the first light pixel at the right edge, clear of the address text."""
    img = Image.open(io.BytesIO(d.adb("exec-out", "screencap", "-p", binary=True))).convert("L")
    x = img.width - 4
    y = 0
    while y < img.height and img.getpixel((x, y)) < 200:
        y += 1
    return y


def serve():
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            body = PAGES.get(self.path, "").encode()
            self.send_response(200 if body else 404)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

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
    w, h = (int(x) for x in d.shell("wm size").split()[-1].split("x"))
    density = int(d.shell("wm density").split()[-1])
    bar = 56 * density // 160
    failed = []

    def open_page(path):
        d.stop()
        d.launch(f"http://localhost:{PORT}{path}")
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(1)
        return toolbar_bottom(d)

    def check(name, got, want):
        ok = abs(got - want) <= 2
        print(f"{name}: toolbar ends at {got}, want {want} -> {'OK' if ok else 'FAIL'}")
        if not ok:
            failed.append(name)

    # Where the shown toolbar ends, on a page too short to scroll: the other pages scroll themselves soon after loading.
    shown = open_page("/b")

    open_page("/jump")
    time.sleep(4)
    check("jump", toolbar_bottom(d), shown)

    open_page("/a")
    time.sleep(3)
    d.shell(f"input tap {w // 2} {h // 2}")
    time.sleep(3)
    d.shell("input keyevent KEYCODE_BACK")
    time.sleep(3)
    check("back", toolbar_bottom(d), shown)

    open_page("/lock")
    d.shell(f"input swipe {w // 2} {h * 3 // 4} {w // 2} {h // 4} 600")
    time.sleep(1.5)
    hidden = toolbar_bottom(d)
    if hidden != shown - bar:
        print(f"lock: setup, toolbar not hidden by the swipe ({hidden}, shown {shown})")
        failed.append("lock (setup)")
    else:
        time.sleep(5)
        d.shell(f"input swipe {w // 2} {h // 3} {w // 2} {h * 2 // 3} 600")
        time.sleep(1.5)
        check("lock", toolbar_bottom(d), shown)

    http.shutdown()
    if failed:
        print("FAIL: " + ", ".join(failed))
        sys.exit(1)
    print("OK")


if __name__ == "__main__":
    main()
