"""Settings, the library and the tab switcher stay composed between openings; each opening must still look fresh:
settings at the top of their first page, the library with an empty search, and Back in the browser not taken by a
hidden page.

Usage: python3 -I tools/e2e/kept_pages.py --serial HOST:PORT
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402


def open_menu_item(d, name):
    d.tap("Меню", exact=True)
    time.sleep(1)
    d.scroll_to(name)
    d.tap(name, exact=True)
    time.sleep(1.5)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    d.stop()
    d.launch()
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(3)

    print("1. settings open at the top of their first page after being left scrolled and on a subpage")
    open_menu_item(d, "Настройки")
    d.tap("Внешний вид", exact=True)
    d.wait("Скорость анимации", exact=True)
    d.shell("input keyevent BACK")
    time.sleep(1)
    d.shell("input keyevent BACK")
    time.sleep(1.5)
    d.wait("Меню", exact=True)
    open_menu_item(d, "Настройки")
    if d.find("Поиск по настройкам", exact=True) is None or d.find("Поиск и главная", exact=True) is None:
        d.fail("settings did not open at the top of their first page")
    d.shell("input keyevent BACK")
    time.sleep(1.5)

    print("2. the library opens with an empty search")
    open_menu_item(d, "История")
    d.tap("Поиск", exact=False)
    d.type("zzzqqq")
    time.sleep(1)
    d.shell("input keyevent BACK")
    time.sleep(0.5)
    d.shell("input keyevent BACK")
    time.sleep(1.5)
    d.wait("Меню", exact=True)
    open_menu_item(d, "История")
    if d.find("zzzqqq") is not None:
        d.fail("the library kept the old search")
    d.shell("input keyevent BACK")
    time.sleep(1.5)

    print("3. Back in the browser is not taken by a hidden page")
    d.wait("Меню", exact=True)
    d.shell("input keyevent BACK")
    time.sleep(1.5)
    top = d.top_activity()
    if "dev.antite.browser" in top and d.find("Поиск по настройкам", exact=True) is not None:
        d.fail("Back opened or stayed in settings")
    print("OK")


if __name__ == "__main__":
    main()
