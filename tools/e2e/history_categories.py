"""History by category: a page opened in a category is in its history and not in another's, "Все" shows both, the
history page opens on the category of the current tab, and deleting the category erases its history.

Usage: python3 -I tools/e2e/history_categories.py --serial HOST:PORT

Latin names only: adb input text cannot type Cyrillic. Leaves no category behind.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from nav import long_press, open_switcher, open_tabs_settings, to_browser  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8793
STAMP = int(time.time()) % 100000
NAME = f"Hist{STAMP}"
IN_CAT = f"InCat{STAMP}"
OUTSIDE = f"Outside{STAMP}"


def page(title):
    return f"<!doctype html><title>{title}</title><meta name=viewport content='width=device-width'><h1>{title}</h1>".encode()


def open_in_new_tab(d, url):
    plus = [n for n in d.nodes() if n.get("content-desc") == "Новая вкладка"]
    x, y = d.center(plus[0])
    d.shell(f"input tap {x} {y}")
    time.sleep(2)
    d.type(url)
    d.shell("input keyevent 66")
    time.sleep(4)


def history_titles(d):
    d.tap("Меню", exact=True)
    d.tap("История", exact=True)
    time.sleep(2.5)
    return {n.get("text") for n in d.nodes() if n.get("text") in (IN_CAT, OUTSIDE)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    server = ReportServer(PORT, {"/in": page(IN_CAT), "/out": page(OUTSIDE)})
    server.attach(d)
    try:
        d.stop()
        d.launch(f"http://localhost:{PORT}/out")
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(3)

        print("1. a page outside any category and one in a new category")
        open_tabs_settings(d)
        d.scroll_to("Мои категории")
        d.tap("Мои категории", exact=True)
        d.tap("Добавить категорию", exact=True)
        d.type(NAME)
        d.tap("Сохранить", exact=True)
        d.wait("Цвет", exact=True)
        to_browser(d)
        open_switcher(d)
        d.tap(NAME, exact=True)
        time.sleep(1)
        open_in_new_tab(d, f"http://localhost:{PORT}/in")

        print("2. from the category's tab the history shows the category alone")
        seen = history_titles(d)
        if d.find(NAME, exact=True) is None:
            d.fail("no category choice on the history page")
        if seen != {IN_CAT}:
            d.fail(f"category history: {seen}")

        print("3. \"Все\" shows both")
        d.tap("Все", exact=True)
        time.sleep(2)
        seen = {n.get("text") for n in d.nodes() if n.get("text") in (IN_CAT, OUTSIDE)}
        if seen != {IN_CAT, OUTSIDE}:
            d.fail(f"all history: {seen}")
        to_browser(d)

        print("4. deleting the category erases its history")
        open_switcher(d)
        long_press(d, NAME)
        d.tap("Удалить категорию", exact=True)
        d.tap("Удалить", exact=True)
        time.sleep(1.5)
        d.shell("input keyevent BACK")
        time.sleep(1)
        to_browser(d)
        seen = history_titles(d)
        if seen != {OUTSIDE}:
            d.fail(f"after deleting the category: {seen}")
        to_browser(d)
    finally:
        server.close()
    print("OK")


if __name__ == "__main__":
    main()
