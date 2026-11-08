"""Leaving address editing: Back on a new tab and opening a tile must bring the toolbar back to showing the address.

Usage: python3 -I tools/e2e/ntp_tile.py --serial HOST:PORT

Needs a browser with an engine; the tile comes from history, so example.com is visited first.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    d.stop()
    d.launch("https://example.com/")
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(3)

    print("1. Back on a new tab leaves address editing")
    d.tap("Новая вкладка")
    time.sleep(2)
    d.shell("input keyevent BACK")
    time.sleep(2)
    if d.find("Меню", exact=True) is None:
        d.fail("toolbar stayed in address editing after Back")

    print("2. a tile opens the site and the toolbar shows its address")
    d.tap("example.com", exact=True)
    d.wait("Меню", timeout=10, exact=True)
    time.sleep(3)
    if d.find("Введите запрос или URL") is not None:
        d.fail("toolbar still shows the address placeholder")
    d.wait("example.com")
    print("OK")


if __name__ == "__main__":
    main()
