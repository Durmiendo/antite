"""Animation style setting: in Chrome's style the menu grows for about 250 ms, in the instant style it is a single
change, in the window style it moves as in Chrome. Settings slide in only in the Chrome and window styles. Counted in recorded
frames.

Usage: python3 -I tools/e2e/animation_style.py --serial SERIAL

Leaves the style at Chrome's and the speed at normal.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from animation_speed import menu_frames, set_choice, set_speed  # noqa: E402
from device import Device  # noqa: E402
from keyboard_frames import frames  # noqa: E402
import subprocess  # noqa: E402

STYLES = (("chrome", "Как в Chrome"), ("instant", "Сразу, без движения"), ("window", "Окно из угла"))


def settings_frames(d, serial, out, name):
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    item = d.center(d.wait("Настройки", exact=True))
    raw_path = os.path.join(out, f"settings_frames_{name}.bin")
    with open(raw_path, "wb") as raw:
        rec = subprocess.Popen(["adb", "-s", serial, "exec-out",
                                "screenrecord --output-format=frames --size 270x600 -"], stdout=raw)
        time.sleep(1.5)
        d.shell(f"input tap {item[0]} {item[1]}")
        time.sleep(2)
        d.shell("pkill -INT screenrecord", check=False)
        rec.wait(timeout=20)
    d.shell("input keyevent BACK")
    time.sleep(1)
    # The lower half: the closing menu does not reach it, the incoming settings page does.
    changed, prev = 0, None
    for img in frames(raw_path):
        w, h = img.size
        area = img.crop((0, h // 2, w, h * 9 // 10)).tobytes()
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
    set_speed(d, "Обычно, как в Chrome")
    menu, settings = {}, {}
    for name, label in STYLES:
        set_choice(d, "Стиль анимации", label)
        menu[name] = menu_frames(d, a.serial, a.out, name + "_style")
        settings[name] = settings_frames(d, a.serial, a.out, name)
        print(f"{name}: menu {menu[name]} frames, settings {settings[name]} frames")
    set_choice(d, "Стиль анимации", "Как в Chrome")
    if not (menu["instant"] < menu["window"] and menu["instant"] < menu["chrome"]):
        d.fail(f"menu frame counts do not follow the style: {menu}")
    if not (settings["instant"] < settings["chrome"] and settings["instant"] < settings["window"]):
        d.fail(f"settings frame counts do not follow the style: {settings}")
    print("OK")


if __name__ == "__main__":
    main()
