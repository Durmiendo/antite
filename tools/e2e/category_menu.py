"""Long press on a switcher chip: a category's chip opens its menu (settings, rename, move, delete), "Все" opens the
tabs settings and "Инкогнито" the incognito settings.

Usage: python3 -I tools/e2e/category_menu.py --serial HOST:PORT

Latin names only: adb input text cannot type Cyrillic. Leaves no category behind.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from nav import long_press, open_switcher, open_tabs_settings, to_browser  # noqa: E402

STAMP = int(time.time()) % 100000
NAME = f"Menu{STAMP}"
RENAMED = f"Moved{STAMP}"
ITEMS = ["Настройки", "Переименовать", "Сдвинуть влево", "Сдвинуть вправо", "Удалить категорию"]


def chips(d):
    """Strip chip names, left to right."""
    names = [n for n in d.nodes() if (t := n.get("text")) and not t.isdigit() and d.bounds(n)[1] < 260]
    return [n.get("text") for n in sorted(names, key=lambda n: d.bounds(n)[0])]


def delete_left(d, names):
    open_tabs_settings(d)
    d.scroll_to("Мои категории")
    d.tap("Мои категории", exact=True)
    time.sleep(1)
    for name in names:
        if d.find(name, exact=True) is None:
            continue
        d.tap(name, exact=True)
        time.sleep(1)
        d.scroll_to("Удалить категорию")
        d.shell("input swipe 540 1600 540 900 250")
        time.sleep(0.6)
        d.tap("Удалить категорию", exact=True)
        d.tap("Удалить", exact=True)
        time.sleep(1.5)
    to_browser(d)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    d.stop()
    d.launch("https://example.com/")
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(2)
    try:
        print("1. a category's chip: the menu")
        open_tabs_settings(d)
        d.scroll_to("Мои категории")
        d.tap("Мои категории", exact=True)
        d.tap("Добавить категорию", exact=True)
        d.type(NAME)
        d.tap("Сохранить", exact=True)
        d.wait("Цвет", exact=True)
        to_browser(d)
        open_switcher(d)
        long_press(d, NAME)
        missing = [i for i in ITEMS if d.find(i, exact=True) is None]
        if missing:
            d.fail(f"menu items missing: {missing}")

        print("2. Настройки opens the category's own page, Back goes to the list of categories")
        d.tap("Настройки", exact=True)
        d.wait("Цвет", exact=True)
        d.wait(NAME, exact=True)
        d.shell("input keyevent BACK")
        time.sleep(1)
        d.wait("Добавить категорию", exact=True)
        to_browser(d)

        print("3. move left, then rename")
        open_switcher(d)
        before = chips(d)
        long_press(d, NAME)
        d.tap("Сдвинуть влево", exact=True)
        time.sleep(1)
        after = chips(d)
        if before.index(NAME) > 1 and after.index(NAME) != before.index(NAME) - 1:
            d.fail(f"not moved left: {before} -> {after}")
        long_press(d, NAME)
        d.tap("Переименовать", exact=True)
        time.sleep(1)
        d.shell("input keyevent KEYCODE_MOVE_END")
        for _ in NAME:
            d.shell("input keyevent DEL")
        d.type(RENAMED)
        d.tap("Сохранить", exact=True)
        time.sleep(1)
        if RENAMED not in chips(d) or NAME in chips(d):
            d.fail(f"not renamed: {chips(d)}")

        print("4. delete from the menu")
        long_press(d, RENAMED)
        d.tap("Удалить категорию", exact=True)
        d.tap("Удалить", exact=True)
        time.sleep(1.5)
        if RENAMED in chips(d):
            d.fail("the category is still in the strip")

        print("5. \"Все\" opens the tabs settings, \"Инкогнито\" the incognito settings")
        long_press(d, "Все")
        d.wait("Мои категории", exact=True)
        to_browser(d)
        open_switcher(d)
        long_press(d, "Инкогнито")
        d.wait("Вход по разблокировке", exact=True)
        to_browser(d)
    finally:
        to_browser(d)
        delete_left(d, [NAME, RENAMED])
    print("OK")


if __name__ == "__main__":
    main()
