"""A page whose tab process crashes is restored once, not in an endless loop: about:crashcontent brings the content
process down, the tab restores the page, and a second crash within a minute leaves the tab empty until reload.

Usage: python3 -I tools/e2e/crash_loop.py --serial HOST:PORT
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402


TEXT = "documentation"


def crash(d):
    d.tap("example.com")
    time.sleep(1)
    d.type("about:crashcontent")
    d.shell("input keyevent 66")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    d.stop()
    d.launch("https://example.com/")
    d.wait("Меню", timeout=30, exact=True)
    d.wait(TEXT, timeout=30)
    print("1. the first crash: the page comes back by itself")
    crash(d)
    time.sleep(3)
    d.wait(TEXT, timeout=20)
    print("2. a second crash within a minute: the tab stays empty")
    crash(d)
    time.sleep(10)
    if d.find(TEXT) is not None:
        d.fail("the page was reloaded after the second crash")
    print("3. reload from the menu brings it back")
    d.tap("Меню", exact=True)
    time.sleep(1)
    # The menu's top icons have no labels; reload is the last one in the row.
    row = [d.bounds(n) for n in d.nodes() if n.get("clickable") == "true"]
    row = [b for b in row if 100 < b[1] < 300 and abs((b[2] - b[0]) - (b[3] - b[1])) < 10]
    if not row:
        d.fail("menu icons not found")
    x1, y1, x2, y2 = max(row, key=lambda b: b[0])
    d.shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    d.wait(TEXT, timeout=20)
    print("OK")


if __name__ == "__main__":
    main()
