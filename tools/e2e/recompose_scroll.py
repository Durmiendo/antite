"""The browser screen is not composed again on every frame while the page scrolls and the toolbar and the navigation
bar hide and come back: those move in layout and drawing only. Counts BrowserScreen compositions (the AntiteCompose
log tag) over a swipe that hides both bars and one that brings them back.
Usage: python3 -I tools/e2e/recompose_scroll.py --serial SERIAL [--limit N]
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from server import TOOLBAR_PORT, serve_toolbar_page  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    # Once per swipe when the bars switch between hidden and shown; before 19.11.2026 it was once a frame (19 and 16).
    ap.add_argument("--limit", type=int, default=2)
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    http = serve_toolbar_page()
    d.adb("reverse", f"tcp:{TOOLBAR_PORT}", f"tcp:{TOOLBAR_PORT}")
    w, h = (int(x) for x in d.shell("wm size").split()[-1].split("x"))
    d.stop()
    d.launch(f"http://localhost:{TOOLBAR_PORT}/long")
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(3)
    d.shell("setprop log.tag.AntiteCompose V")
    try:
        counts = []
        for name, y0, y1 in (("hide", h * 3 // 4, h // 4), ("show", h // 3, h * 2 // 3)):
            d.shell("logcat -c")
            d.shell(f"input swipe {w // 2} {y0} {w // 2} {y1} 800")
            time.sleep(2.5)
            n = d.shell("logcat -d -s AntiteCompose:V").count("BrowserScreen")
            print(f"{name}: BrowserScreen composed {n} times")
            counts.append((name, n))
    finally:
        d.shell("setprop log.tag.AntiteCompose ''")
        http.shutdown()
    bad = [f"{n} ({c})" for n, c in counts if c > a.limit]
    if bad:
        print(f"FAIL: more than {a.limit} compositions: " + ", ".join(bad))
        sys.exit(1)
    print("OK")


if __name__ == "__main__":
    main()
