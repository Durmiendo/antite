"""Default browser offer: the bar shows while Antite is not the default browser; "Да" goes through the system
role dialog and makes it the default, "Позже" hides the bar so that it does not come back on the next start.

Usage: python3 -I tools/e2e/default_browser.py --serial HOST:PORT

The previous default browser is restored at the end. "Позже" postpones the bar for 3 days on this device, and
"Нет" is not covered: either one stops the bar until the browser data is cleared.
"""
import argparse
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402

ROLE = "android.app.role.BROWSER"
BAR = "Сделать Antite браузером по умолчанию?"


def holder(d):
    m = re.search(r"name=" + re.escape(ROLE) + r"\s+holders=(\S*)", d.shell("dumpsys role"))
    return m.group(1) if m else ""


def start(d):
    d.stop()
    d.launch()
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    before = holder(d)
    print("default browser before:", before or "none")
    if before == PACKAGE:
        d.fail("Antite is already the default browser, the offer cannot be checked")
    try:
        print("1. the bar is offered")
        start(d)
        d.wait(BAR, timeout=5, exact=True)

        print("2. Да goes through the system dialog and makes Antite the default")
        d.tap("Да", exact=True)
        d.wait('Сделать "Antite" браузером по умолчанию?', timeout=10, exact=True)
        d.tap("Antite", exact=True)
        d.tap("По умолчанию", exact=True)
        time.sleep(2)
        if holder(d) != PACKAGE:
            d.fail(f"default browser is {holder(d)!r}")
        if d.find(BAR, exact=True) is not None:
            d.fail("bar stayed after Да")

        print("3. while Antite is the default the bar is not offered")
        start(d)
        if d.find(BAR, exact=True) is not None:
            d.fail("bar offered although Antite is the default")

        print("4. Позже hides the bar, and the next start does not show it")
        if before:
            d.shell(f"cmd role add-role-holder {ROLE} {before}")
        else:
            d.shell(f"cmd role remove-role-holder {ROLE} {PACKAGE}")
        start(d)
        d.tap("Позже", exact=True)
        time.sleep(1)
        if d.find(BAR, exact=True) is not None:
            d.fail("bar stayed after Позже")
        start(d)
        if d.find(BAR, exact=True) is not None:
            d.fail("bar came back on the next start after Позже")
    finally:
        if before and holder(d) != before:
            d.shell(f"cmd role add-role-holder {ROLE} {before}")
        print("default browser after:", holder(d) or "none")
    print("OK")


if __name__ == "__main__":
    main()
