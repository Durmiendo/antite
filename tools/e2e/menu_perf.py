"""Frame times of opening and closing the overflow menu.

Usage: python3 -I tools/e2e/menu_perf.py --serial HOST:PORT [--times 20]

For each opening reports the worst frame and the time spent in frames over 16.7 ms, over all windows
of the app, so a menu drawn in its own popup window and one drawn in the page window compare fairly.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from frames import describe, frames, ms, windows  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    ap.add_argument("--times", type=int, default=20)
    ap.add_argument("--url", default="https://example.com/")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    d.stop()
    d.launch(a.url)
    menu = d.wait("Меню", timeout=30, exact=True)
    time.sleep(4)
    x, y = d.center(menu)

    worst, slow = [], []
    for i in range(a.times):
        d.shell(f"dumpsys gfxinfo {PACKAGE} reset >/dev/null")
        d.shell(f"input tap {x} {y}")
        time.sleep(1.2)
        opened = d.shell(f"dumpsys gfxinfo {PACKAGE} framestats")
        frames = [f for _, rows in windows(opened) for f in ms(rows)]
        worst.append(max(frames, default=0))
        slow.append(sum(f for f in frames if f > 16.7))
        if i == 0:
            with open(os.path.join(a.out, "menu_perf_gfxinfo.txt"), "w") as f:
                f.write(opened)
        d.shell("input keyevent BACK")
        time.sleep(1.0)

    print(f"menu opened {a.times} times")
    print("worst frame per opening:       " + describe(worst))
    print("time in slow frames per opening: " + describe(slow))


if __name__ == "__main__":
    main()
