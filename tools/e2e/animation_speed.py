"""Animation speed setting: opening the menu takes about 250 ms at the normal speed (Chrome's menu_enter.xml), twice
as long at x0.5 and is a single change with animations off. Counted in recorded frames.

Usage: python3 -I tools/e2e/animation_speed.py --serial SERIAL

Records raw frames with screenrecord --output-format=frames: a frame comes only when the screen changes. Leaves the
speed at normal.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from frames import menu_frames, set_speed  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    d.stop()
    d.launch()
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(2)
    counts = {}
    for name, label in (("off", "Без анимации"), ("normal", "Обычно, как в Chrome"), ("slow", "Медленно (×0.5)")):
        set_speed(d, label)
        counts[name] = menu_frames(d, a.serial, a.out, name)
        print(f"{name}: {counts[name]} frames")
    set_speed(d, "Обычно, как в Chrome")
    if not (counts["off"] < counts["normal"] < counts["slow"]):
        d.fail(f"frame counts do not follow the speed: {counts}")
    print("OK")


if __name__ == "__main__":
    main()
