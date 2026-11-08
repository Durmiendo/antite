"""Frame times of opening and closing the overflow menu.

Usage: python3 -I tools/e2e/menu_perf.py --serial HOST:PORT [--times 20]

For each opening reports the worst frame and the time spent in frames over 16.7 ms, over all windows
of the app, so a menu drawn in its own popup window and one drawn in the page window compare fairly.
"""
import argparse
import os
import re
import statistics
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402


def windows(dump):
    """Splits `gfxinfo framestats` into (window name, [frame rows as dicts])."""
    out = []
    for block in re.split(r"\n(?=Window: )", dump):
        m = re.match(r"Window: (.+)", block)
        if not m:
            continue
        stats = re.search(r"---PROFILEDATA---\n(.+?)\n(.*?)---PROFILEDATA---", block, re.S)
        rows = []
        if stats:
            head = stats.group(1).strip().rstrip(",").split(",")
            for line in stats.group(2).strip().splitlines():
                vals = line.strip().rstrip(",").split(",")
                if len(vals) == len(head):
                    rows.append(dict(zip(head, map(int, vals))))
        out.append((m.group(1).strip(), rows))
    return out


def ms(rows):
    return [(r["FrameCompleted"] - r["IntendedVsync"]) / 1e6 for r in rows
            if r["IntendedVsync"] > 0 and 0 < r["FrameCompleted"] - r["IntendedVsync"] < 5e9]


def describe(xs):
    if not xs:
        return "no frames"
    xs = sorted(xs)
    p = lambda q: xs[min(len(xs) - 1, int(q * len(xs)))]
    return f"n={len(xs)} median={statistics.median(xs):.1f} p90={p(0.9):.1f} max={xs[-1]:.1f} ms, >16.7ms: {sum(x > 16.7 for x in xs)}"


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
