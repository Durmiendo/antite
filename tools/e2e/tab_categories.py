"""Tab categories: one made in settings shows in the switcher strip between "Все" and "Инкогнито", a new tab from the
switcher lands in the open category, a swipe moves to the next category, the per-category count follows, a pin tied
to the category opens its page there, deleting the category leaves its tabs in "Все", and unlock to enter hides
incognito tabs.

Usage: python3 -I tools/e2e/tab_categories.py --serial SERIAL

Needs example.com among the frequent sites (visited before) and no incognito tabs. Latin names only: adb input text
cannot type Cyrillic. Leaves the per-category count off and no category or pin behind.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402

NAME = "Work"


def open_tabs_settings(d):
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    time.sleep(1)
    d.tap("Настройки", exact=True)
    d.wait("Поиск по настройкам", exact=True)
    time.sleep(1)
    d.tap("Вкладки", exact=True)
    time.sleep(1)


def back(d, times):
    for _ in range(times):
        d.shell("input keyevent BACK")
        time.sleep(1)


def to_browser(d):
    """Back until the toolbar shows; counting presses breaks when a page pops itself or the keyboard takes one."""
    for _ in range(6):
        if d.find("Меню", exact=True) is not None:
            return
        d.shell("input keyevent BACK")
        time.sleep(1)
    d.fail("could not get back to the browser")


def open_switcher(d):
    count = [n for n in d.nodes() if (n.get("text") or "").isdigit() and d.bounds(n)[1] < 230]
    if not count:
        d.fail("no tab count in the toolbar")
    x, y = d.center(count[0])
    d.shell(f"input tap {x} {y}")
    time.sleep(1.5)


def chip_count(d, name):
    """The number drawn right of a strip chip's name, or None."""
    nodes = d.nodes()
    label = next((n for n in nodes if n.get("text") == name and d.bounds(n)[1] < 260), None)
    if label is None:
        return None
    x1, y1, x2, y2 = d.bounds(label)
    for n in nodes:
        t = n.get("text") or ""
        bx1, by1, bx2, by2 = d.bounds(n)
        if t.isdigit() and abs((by1 + by2) - (y1 + y2)) < 20 and 0 <= bx1 - x2 < 60:
            return int(t)
    return None


def delete_categories(d):
    """Deletes every category named NAME; returns whether there was one."""
    open_tabs_settings(d)
    d.scroll_to("Мои категории")
    d.tap("Мои категории", exact=True)
    time.sleep(1)
    found = False
    while d.find(NAME, exact=True) is not None:
        found = True
        d.tap(NAME, exact=True)
        time.sleep(1)
        d.scroll_to("Удалить категорию")
        d.tap("Удалить категорию", exact=True)
        d.tap("Удалить", exact=True)
        time.sleep(1.5)
    to_browser(d)
    return found


def set_each_count(d, on):
    """The switch row exposes no checked state, so the strip tells whether counts are drawn."""
    open_switcher(d)
    shown = chip_count(d, "Все") is not None
    d.shell("input keyevent BACK")
    time.sleep(1)
    if shown == on:
        return
    open_tabs_settings(d)
    d.scroll_to("Число вкладок у каждой категории")
    d.tap("Число вкладок у каждой категории", exact=True)
    to_browser(d)


def set_incognito_lock(d):
    open_tabs_settings(d)
    d.scroll_to("Инкогнито")
    d.tap("Инкогнито", exact=True)
    d.tap("Вход по разблокировке", exact=True)
    to_browser(d)


def long_press(d, text):
    x, y = d.center(d.wait(text, exact=True))
    d.shell(f"input swipe {x} {y} {x} {y} 900")
    time.sleep(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    d.stop()
    d.launch()
    d.wait("Меню", timeout=60, exact=True)
    time.sleep(3)

    delete_categories(d)
    print("1. a category made in settings, with counts on each category")
    set_each_count(d, True)
    open_tabs_settings(d)
    d.scroll_to("Мои категории")
    d.tap("Мои категории", exact=True)
    d.tap("Добавить категорию", exact=True)
    d.type(NAME)
    d.tap("Сохранить", exact=True)
    d.wait("Цвет", exact=True)
    to_browser(d)

    print("2. the strip shows it; a new tab from the switcher lands in it")
    open_switcher(d)
    for chip in ("Все", NAME, "Инкогнито"):
        d.wait(chip, exact=True)
    d.tap(NAME, exact=True)
    time.sleep(1)
    d.wait("Здесь пока нет вкладок", exact=True)
    if chip_count(d, NAME) != 0:
        d.fail(f"count of an empty category: {chip_count(d, NAME)}")
    plus = [n for n in d.nodes() if n.get("content-desc") == "Новая вкладка"]
    x, y = d.center(plus[0])
    d.shell(f"input tap {x} {y}")
    time.sleep(2)
    d.shell("input keyevent BACK")
    time.sleep(1)
    open_switcher(d)
    if d.find("Здесь пока нет вкладок", exact=True) is not None:
        d.fail("the switcher did not open on the new tab's category")
    if chip_count(d, NAME) != 1:
        d.fail(f"category count after a new tab: {chip_count(d, NAME)}")

    print("3. a swipe goes to the next category (incognito, empty)")
    d.shell("input swipe 900 1500 150 1500 250")
    time.sleep(1.5)
    d.wait("Здесь пока нет вкладок", exact=True)
    d.shell("input swipe 150 1500 900 1500 250")
    time.sleep(1.5)
    if d.find("Здесь пока нет вкладок", exact=True) is not None:
        d.fail("swiping back did not return to the category")
    d.shell("input keyevent BACK")
    time.sleep(1)

    print("4. a pin tied to the category opens there")
    d.tap("Меню", exact=True)
    d.tap("Новая вкладка", exact=True)
    time.sleep(2)
    d.wait("Часто посещаемые", exact=True)
    long_press(d, "example.com")
    d.tap("Закрепить", exact=True)
    time.sleep(1.5)
    long_press(d, "example.com")
    d.tap("Категория: Все", exact=True)
    d.tap(NAME, exact=True)
    time.sleep(1.5)
    d.tap("example.com", exact=True)
    time.sleep(3)
    open_switcher(d)
    if chip_count(d, NAME) != 2:
        d.fail(f"category count after opening the pin: {chip_count(d, NAME)}")
    d.shell("input keyevent BACK")
    time.sleep(1)

    print("5. deleting the category keeps its tabs in All")
    d.tap("Меню", exact=True)
    d.tap("Новая вкладка", exact=True)
    time.sleep(2)
    long_press(d, "example.com")
    d.tap("Открепить", exact=True)
    time.sleep(1)
    if not delete_categories(d):
        d.fail("the category is gone from settings")
    set_each_count(d, False)
    open_switcher(d)
    if d.find(NAME, exact=True) is not None:
        d.fail("the deleted category is still in the strip")
    d.wait("Все", exact=True)
    d.shell("input keyevent BACK")
    time.sleep(1)

    print("6. with unlock to enter, incognito shows the lock (Waydroid has no screen lock, so it stays locked)")
    set_incognito_lock(d)
    open_switcher(d)
    d.tap("Инкогнито", exact=True)
    d.wait("Вкладки инкогнито заблокированы", exact=True)
    d.shell("input keyevent BACK")
    time.sleep(1)
    set_incognito_lock(d)
    open_switcher(d)
    d.tap("Инкогнито", exact=True)
    d.wait("Здесь пока нет вкладок", exact=True)
    d.shell("input keyevent BACK")
    print("OK")


if __name__ == "__main__":
    main()
