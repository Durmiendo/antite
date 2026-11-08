"""Full-screen pages (settings, history, tab list) must not let taps or typing reach the address field under them.

Usage: python3 -I tools/e2e/overlay_input.py --serial HOST:PORT
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402


def keyboard_shown(d):
    return "mInputShown=true" in d.shell("dumpsys input_method", check=False)


def check(d, title):
    node = d.wait(title, exact=True)
    x, y = d.center(node)
    d.shell(f"input tap {x} {y}")
    time.sleep(1)
    d.type("zzqq")
    time.sleep(1)
    if keyboard_shown(d):
        d.fail(f"keyboard opened on the {title!r} page")
    d.shell("input keyevent BACK")
    time.sleep(1)
    if d.find("zzqq") is not None:
        d.fail(f"text typed on the {title!r} page went to the address field")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    d.stop()
    d.launch("https://example.com/")
    time.sleep(3)
    for item, title in (("Настройки", "Настройки"), ("История", "История")):
        d.tap("Меню", exact=True)
        d.scroll_to(item)
        d.tap(item, exact=True)
        check(d, title)
        print("ok:", title)
    print("OK")


if __name__ == "__main__":
    main()
