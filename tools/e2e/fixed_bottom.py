"""A page's fixed bottom bar sits right above the system navigation bar whether the toolbar is shown or hidden,
and the page never shows through the navigation bar.

Usage: python3 -I tools/e2e/fixed_bottom.py --serial HOST:PORT
"""
import argparse
import io
import os
import sys
import time

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8776
PAGE = b"""<!doctype html><meta name="viewport" content="width=device-width">
<style>body{margin:0;background:repeating-linear-gradient(#fff 0 40px,#ddd 40px 80px)}
#bar{position:fixed;left:0;right:0;bottom:0;height:50px;background:#f00}</style>
<div style="height:4000px"></div><div id="bar"></div>"""


def measure(d):
    img = Image.open(io.BytesIO(d.adb("exec-out", "screencap", "-p", binary=True))).convert("RGB")
    px = img.load()
    w, h = img.size
    red = [y for y in range(h) if px[w // 2, y] == (255, 0, 0)]
    if not red:
        d.fail("fixed bar is not on screen")
    below = {px[20, y] for y in range(max(red) + 1, h - 1)}
    return max(red), below


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    server = ReportServer(PORT, {"/fixed": PAGE})
    server.attach(d)
    d.stop()
    d.launch(f"http://localhost:{PORT}/fixed")
    time.sleep(6)
    shown, below_shown = measure(d)
    for _ in range(2):
        d.shell("input swipe 540 1700 540 900 300")
        time.sleep(1)
    time.sleep(2)
    if d.find("Меню", exact=True) is not None:
        d.fail("toolbar did not hide on scroll")
    hidden, below_hidden = measure(d)
    print(f"bar bottom: toolbar shown {shown}, hidden {hidden}")
    if shown != hidden:
        d.fail(f"fixed bar moved when the toolbar hid: {shown} -> {hidden}")
    for name, below in (("shown", below_shown), ("hidden", below_hidden)):
        if len(below) > 1:
            d.fail(f"page shows under the navigation bar (toolbar {name}): {sorted(below)[:4]}")
    server.close()
    print("OK")


if __name__ == "__main__":
    main()
