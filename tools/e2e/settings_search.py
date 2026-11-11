"""Settings search: the root page lists categories under a search field; typing finds rows inside the pages, shows
where they live, and a result opens that page scrolled to the row. Back returns to the results. Latin queries only:
adb input text cannot type Cyrillic.

Usage: python3 -I tools/e2e/settings_search.py --serial SERIAL
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402


def open_settings(d):
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    d.tap("Настройки", exact=True)
    d.wait("Поиск по настройкам", exact=True)
    time.sleep(1)


def search(d, query):
    d.tap("Поиск по настройкам", exact=True)
    d.type(query)
    time.sleep(1)
    d.shell("input keyevent BACK")
    time.sleep(0.6)


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
    open_settings(d)
    for category in ("Поиск и главная", "Внешний вид", "Конфиденциальность и безопасность", "Система"):
        d.scroll_to(category)

    search(d, "WebRTC")
    d.wait("Конфиденциальность и безопасность › Конфиденциальность", exact=True)
    d.tap("WebRTC", exact=True)
    d.wait("Конфиденциальность и безопасность", exact=True)
    if d.find("WebRTC", exact=True) is None:
        d.fail("the result's page does not show its row")
    d.shell("input keyevent BACK")
    time.sleep(1)
    d.wait("Конфиденциальность и безопасность › Конфиденциальность", exact=True)

    # A row two pages deep: Privacy > Site settings.
    d.tap("Очистить", exact=True)
    search(d, "JavaScript")
    d.wait("Конфиденциальность и безопасность › Настройки сайтов", exact=False)

    d.tap("Очистить", exact=True)
    search(d, "qqqzz")
    d.wait("Ничего не найдено", exact=True)

    # Closing settings forgets the search.
    d.shell("input keyevent BACK")
    time.sleep(1)
    open_settings(d)
    if d.find("Ничего не найдено", exact=True) is not None:
        d.fail("search kept after closing settings")
    d.wait("Внешний вид", exact=True)
    with open(os.path.join(a.out, "settings_root.png"), "wb") as f:
        f.write(d.adb("exec-out", "screencap", "-p", binary=True))
    print("OK")


if __name__ == "__main__":
    main()
