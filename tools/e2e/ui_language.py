"""Interface language: Settings > Languages > Interface language > English turns the interface English at once, without
a restart; the browser goes back to Russian at the end.

Usage: python3 -I tools/e2e/ui_language.py --serial SERIAL
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402


def open_appearance(d, menu, settings, languages):
    d.tap(menu, exact=True)
    d.scroll_to(settings)
    d.tap(settings, exact=True)
    d.scroll_to(languages)
    d.tap(languages, exact=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    d.stop()
    d.launch()
    end = time.time() + 30
    while d.find("Меню", exact=True) is None and d.find("Menu", exact=True) is None:
        if time.time() > end:
            d.fail("no toolbar")
        time.sleep(0.5)
    if d.find("Menu", exact=True) is not None:
        print("0. left in English by an earlier run, back to Russian first")
        open_appearance(d, "Menu", "Settings", "Appearance")
        d.tap("Interface language", exact=True)
        d.tap("Русский", exact=True)
        d.wait("Язык интерфейса", exact=True)
        for _ in range(3):
            d.shell("input keyevent BACK")
        d.wait("Меню", exact=True)
    pid = d.shell(f"pidof {PACKAGE}", check=False).strip()

    print("1. Russian -> English")
    open_appearance(d, "Меню", "Настройки", "Внешний вид")
    d.tap("Язык интерфейса", exact=True)
    d.tap("English", exact=True)
    d.wait("Interface language", exact=True)
    with open(os.path.join(a.out, "ui-language-en.png"), "wb") as f:
        f.write(d.adb("exec-out", "screencap", "-p", binary=True))
    d.shell("input keyevent BACK")
    d.shell("input keyevent BACK")
    d.shell("input keyevent BACK")
    d.wait("Menu", exact=True)
    d.tap("Menu", exact=True)
    d.scroll_to("Settings")
    d.shell("input keyevent BACK")
    if d.shell(f"pidof {PACKAGE}", check=False).strip() != pid:
        d.fail("the browser restarted")

    print("2. English stays after a restart")
    d.stop()
    d.launch()
    d.wait("Menu", timeout=30, exact=True)

    print("3. back to Russian")
    open_appearance(d, "Menu", "Settings", "Appearance")
    d.tap("Interface language", exact=True)
    d.tap("Русский", exact=True)
    d.wait("Язык интерфейса", exact=True)
    d.shell("input keyevent BACK")
    d.shell("input keyevent BACK")
    d.shell("input keyevent BACK")
    print("OK")


if __name__ == "__main__":
    main()
