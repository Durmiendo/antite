"""Overflow menu: it stays composed while closed, so it must be absent from the screen tree when closed and
present when open; Back, a tap outside and an item close it; reopening starts on the main list.

Usage: python3 -I tools/e2e/menu.py --serial HOST:PORT
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    d.stop()
    d.launch("https://example.com/")
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(3)

    def shown(text):
        return d.find(text, exact=True) is not None

    def open_menu():
        d.tap("Меню", exact=True)
        d.wait("История", timeout=5, exact=True)

    print("1. closed menu is not on screen")
    if shown("История"):
        d.fail("closed menu is in the screen tree")

    print("2. Back closes it")
    open_menu()
    d.shell("input keyevent BACK")
    time.sleep(0.8)
    if shown("История") or not shown("Меню"):
        d.fail("Back did not close the menu")

    print("3. a tap outside closes it")
    open_menu()
    d.shell("input tap 40 2200")
    time.sleep(0.8)
    if shown("История") or not shown("Меню"):
        d.fail("tap outside did not close the menu")

    print("4. submenu, then reopening starts on the main list")
    open_menu()
    d.tap("Ещё инструменты")
    d.wait("Копировать ссылку", timeout=5, exact=True)
    d.shell("input keyevent BACK")
    time.sleep(0.8)
    open_menu()
    if shown("Копировать ссылку"):
        d.fail("reopened menu shows the submenu")

    print("5. an item runs and closes the menu")
    d.tap("История", exact=True)
    time.sleep(1.5)
    if shown("Новая вкладка инкогнито"):
        d.fail("menu stayed open after an item")
    d.shell("input keyevent BACK")
    time.sleep(1)
    if not shown("Меню"):
        d.fail("did not return to the page")
    print("OK")


if __name__ == "__main__":
    main()
