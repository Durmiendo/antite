"""Animation speed setting: opening the menu takes about 250 ms at the normal speed (Chrome's menu_enter.xml), twice
as long at x0.5 and is a single change with animations off. Counted in recorded frames.

Usage: python3 -I tools/e2e/animation_speed.py --serial SERIAL

Records raw frames with screenrecord --output-format=frames: a frame comes only when the screen changes. Leaves the
speed at normal.
"""
import argparse
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from keyboard_frames import frames  # noqa: E402


def set_speed(d, label):
    set_choice(d, "Скорость анимации", label)


def set_choice(d, row, label):
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    d.tap("Настройки", exact=True)
    d.wait("Поиск по настройкам", exact=True)
    time.sleep(1)
    d.tap("Внешний вид", exact=True)
    time.sleep(1)
    d.tap(row, exact=True)
    d.tap(label, exact=True)
    for _ in range(3):
        d.shell("input keyevent BACK")
        time.sleep(1)
    d.wait("Меню", exact=True)
    time.sleep(1)


def menu_frames(d, serial, out, name):
    menu = d.center(d.wait("Меню", exact=True))
    raw_path = os.path.join(out, f"menu_frames_{name}.bin")
    with open(raw_path, "wb") as raw:
        rec = subprocess.Popen(["adb", "-s", serial, "exec-out",
                                "screenrecord --output-format=frames --size 270x600 -"], stdout=raw)
        time.sleep(1.5)
        d.shell(f"input tap {menu[0]} {menu[1]}")
        time.sleep(2)
        d.shell("pkill -INT screenrecord", check=False)
        rec.wait(timeout=20)
    d.shell("input keyevent BACK")
    time.sleep(1)
    # Frames on which the menu area (upper right quarter) changed; other parts of the screen may redraw on their own.
    changed, prev = 0, None
    for img in frames(raw_path):
        w, h = img.size
        area = img.crop((w // 2, h // 10, w, h // 2)).tobytes()
        if prev is not None and area != prev:
            changed += 1
        prev = area
    return changed


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
