"""Pull down to refresh, as in Chrome: a slow pull down on a page at the top reloads it; the circle stays while
the reload is in progress and goes away after it, also when the reload fails; a pull too short for the trigger and a pull on a scrolled page do nothing, also on a
scrolled tab just switched to. The page shows a stamp that changes on every load.

Usage: python3 -I tools/e2e/pull_refresh.py --serial SERIAL
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
from tab_categories import open_switcher  # noqa: E402

PORT = 8784
# Set while the server should answer slowly, so the refresh circle can be seen during the reload.
slow = threading.Event()
PAGE = b"""<!doctype html><meta name=viewport content="width=device-width"><title>Pull</title>
<body style="margin:0;height:400vh;background:#fff">
<div id="stamp" style="font-size:40px;padding:120px 20px">load</div>
<script>var t = Date.now(); document.getElementById('stamp').textContent = 'load ' + t; document.title = 'Pull ' + t;</script>
</body>"""


def serve():
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if slow.is_set() and self.path == "/":
                time.sleep(4)
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


def circle_shown(d, w, top):
    """The refresh circle: its arc is the accent pink, nothing else that colour sits under the toolbar at the screen
    middle (the error page's pink button is lower). uiautomator does not list the circle view, so the screen is
    sampled."""
    img = Image.open(io.BytesIO(d.adb("exec-out", "screencap", "-p", binary=True))).convert("RGB")
    for y in range(top, top + 250, 2):
        for x in range(w // 2 - 70, w // 2 + 70, 2):
            r, g, b = img.getpixel((x, y))
            if r > 225 and 150 < g < 205 and 155 < b < 210:
                return True
    return False


def wait_circle_gone(d, w, top, timeout=20):
    end = time.time() + timeout
    while time.time() < end:
        if not circle_shown(d, w, top):
            return
        time.sleep(0.5)
    d.fail("the refresh circle stayed after the reload finished")


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
        # While the reload it started is in progress, the circle stays on screen; it is gone once the page loaded.
        slow.set()
        d.shell(f"input swipe {w // 2} {h // 2} {w // 2} {h // 2 + 800} 900")
        time.sleep(1.5)
        top = d.bounds(d.find("Меню", exact=True))[3] + 40
        if not circle_shown(d, w, top):
            d.fail("the refresh circle is not shown while the reload is in progress")
        second = reload_stamp(d, second)
        slow.clear()
        wait_circle_gone(d, w, top)
        print("3. the circle stays while the reload runs and goes away after it")
        # A reload that fails at once (nothing listens on the port) also ends the circle, on the error page.
        d.adb("reverse", "--remove", f"tcp:{PORT}")
        d.shell(f"input swipe {w // 2} {h // 2} {w // 2} {h // 2 + 800} 900")
        d.wait("Повторить", exact=True)
        wait_circle_gone(d, w, top)
        d.adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
        d.tap("Повторить", exact=True)
        second = reload_stamp(d, second)
        time.sleep(2)
        print("4. a failed reload ends the circle too")
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
        print("5. a pull on a scrolled page does not reload")
        # A scrolled tab switched to from another tab: the pull scrolls it too, nothing reloads.
        d.tap("Меню", exact=True)
        d.tap("Новая вкладка", exact=True)
        time.sleep(1.5)
        open_switcher(d)
        # Older runs leave tabs with the same page behind; the card title carries this load's stamp.
        d.tap("Pull " + second.split()[1], exact=True)
        d.wait("Меню", timeout=10, exact=True)
        time.sleep(1.5)
        if d.find("load ") is not None:
            d.fail("the switched-to tab is not scrolled any more")
        end = time.time() + 3
        d.shell(f"input swipe {w // 2} {h // 2} {w // 2} {h // 2 + 400} 900")
        while time.time() < end:
            n = d.find("load ")
            if n is not None and n.get("text") != second:
                d.fail("a pull on a scrolled tab just switched to reloaded it")
            time.sleep(0.4)
        print("6. a pull on a scrolled tab just switched to does not reload")
    finally:
        http.shutdown()
    print("OK")


if __name__ == "__main__":
    main()
