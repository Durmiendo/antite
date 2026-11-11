"""Frame times of the main transitions, started from the new tab page: menu, bookmarks, settings, tab switcher,
each opened and closed again.

Usage: python3 -I tools/e2e/ui_perf.py --serial HOST:PORT [--times 10]

For every transition reports the worst frame and the time spent in frames over 16.7 ms, over all windows of the app.
Measure a non-debuggable build (assemblePerf); a debug build of Compose is several times slower than the release.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from menu_perf import describe, ms, windows  # noqa: E402


def frames(d):
    return [f for _, rows in windows(d.shell(f"dumpsys gfxinfo {PACKAGE} framestats")) for f in ms(rows)]


def measure(d, action, settle=1.5):
    d.shell(f"dumpsys gfxinfo {PACKAGE} reset >/dev/null")
    action()
    time.sleep(settle)
    return frames(d)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    ap.add_argument("--times", type=int, default=10)
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    d.stop()
    d.launch()
    d.wait("Меню", timeout=30, exact=True)
    d.tap("Новая вкладка", exact=True)
    time.sleep(1.5)
    d.shell("input keyevent BACK")
    time.sleep(1.5)
    if d.find("Закреплённые") is None and d.find("Часто посещаемые") is None:
        d.fail("not on the new tab page")
    density = int(d.shell("wm density").split()[-1]) / 160
    mx, my = d.center(d.find("Меню", exact=True))
    switcher = (mx - int(44 * density), my)

    def tap(x, y):
        return lambda: d.shell(f"input tap {x} {y}")

    back = lambda: d.shell("input keyevent BACK")
    scenarios = [("menu", None), ("bookmarks", "Закладки"), ("settings", "Настройки"), ("switcher", switcher)]
    results = {}
    for name, xy in scenarios:
        opened, closed = [], []
        for _ in range(a.times):
            # Menu items are reached through the menu, whose frames are not part of the item's opening.
            if isinstance(xy, str):
                d.shell(f"input tap {mx} {my}")
                time.sleep(0.8)
                d.scroll_to(xy)
                time.sleep(0.8)
                x, y = d.center(d.find(xy, exact=True))
            else:
                x, y = xy or (mx, my)
            opened.append(measure(d, tap(x, y)))
            closed.append(measure(d, back))
        results[name] = (opened, closed)
        if d.find("Меню", exact=True) is None:
            d.fail(f"did not return to the browser after {name}")

    for name, (opened, closed) in results.items():
        for label, runs in (("open", opened), ("close", closed)):
            worst = [max(r, default=0) for r in runs]
            slow = [sum(f for f in r if f > 16.7) for r in runs]
            print(f"{name:9} {label:5} worst frame: {describe(worst)}")
            print(f"{'':9} {'':5} slow time:   {describe(slow)}")


if __name__ == "__main__":
    main()
