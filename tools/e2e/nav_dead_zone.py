"""Navigation bar dead zone: once scrolling down has hidden the toolbar and the navigation bar, a small scroll up
must not bring the navigation bar back; a longer one must.

Usage: python3 -I tools/e2e/nav_dead_zone.py --serial HOST:PORT
"""
import argparse
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8778
PAGE = b"""<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<body style="margin:0"><div style="height:9000px;background:repeating-linear-gradient(#fff 0 40px,#ddd 40px 80px)"></div>"""


def nav_visible(d):
    m = re.search(r"InsetsSource type=ITYPE_NAVIGATION_BAR[^\n]*visible=(true|false)", d.shell("dumpsys window"))
    return m and m.group(1) == "true"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    server = ReportServer(PORT, {"/long": PAGE})
    server.attach(d)
    d.stop()
    d.launch(f"http://localhost:{PORT}/long")
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(3)

    print("1. scrolling down hides the navigation bar")
    for _ in range(2):
        d.shell("input swipe 540 1700 540 900 300")
        time.sleep(1)
    time.sleep(1)
    if nav_visible(d):
        d.fail("navigation bar did not hide")

    print("2. a small scroll up keeps it hidden")
    d.shell("input swipe 540 1000 540 1060 400")
    time.sleep(1.5)
    if nav_visible(d):
        d.fail("navigation bar came back on a small scroll up")

    print("3. a longer scroll up brings it back")
    d.shell("input swipe 540 900 540 1400 400")
    time.sleep(1.5)
    if not nav_visible(d):
        d.fail("navigation bar did not come back on a long scroll up")
    server.close()
    print("OK")


if __name__ == "__main__":
    main()
