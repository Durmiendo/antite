"""A value of one's own where a choice allows it: a search engine address, a secure DNS provider, the text size and the
number of tabs kept in memory. A wrong value is refused with the dialog left open; a right one shows in the row and
the search goes to the address typed.

Usage: python3 -I tools/e2e/custom_choices.py --serial HOST:PORT

adb input text turns %s into a space, so the search address here has the query at its end. Puts DuckDuckGo, 100%,
6 tabs and secure DNS off back at the end.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from nav import open_settings_page, to_browser  # noqa: E402

SEARCH = "https://search.example/find?q="
DOH = "https://dns.example/dns-query"


def own_value(d, row, text):
    """Opens a choice, picks "Свой вариант…" and types text into its field."""
    d.scroll_to(row)
    d.tap(row, exact=True)
    time.sleep(1)
    d.scroll_to("Свой")
    d.tap("Свой", exact=False)
    time.sleep(1)
    d.type(f"'{text}'")
    time.sleep(0.5)
    d.tap("Сохранить", exact=True)
    time.sleep(1.5)


def pick(d, row, label):
    d.scroll_to(row)
    d.tap(row, exact=True)
    time.sleep(1)
    d.scroll_to(label)
    d.tap(label, exact=True)
    time.sleep(1)
    d.shell("input keyevent BACK")
    time.sleep(1)


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
        print("1. a wrong search address is refused, the dialog stays")
        open_settings_page(d, "Поиск и главная")
        own_value(d, "Поисковая система", "http://search.example/?q=")
        if d.find("Сохранить", exact=True) is None:
            d.fail("the dialog closed on a wrong address")
        d.shell("input keyevent BACK")
        time.sleep(1)
        if d.find("Сохранить", exact=True) is not None:
            d.shell("input keyevent BACK")
            time.sleep(1)

        print("2. an own search address: shown in the row, the search goes there")
        d.scroll_to("Свой")
        d.tap("Свой", exact=False)
        time.sleep(1)
        d.type(f"'{SEARCH}'")
        d.tap("Сохранить", exact=True)
        time.sleep(1.5)
        d.wait("Свой: search.example", exact=True)
        d.shell("input keyevent BACK")
        time.sleep(1)
        d.wait("search.example", exact=True)
        to_browser(d)
        d.tap("Меню", exact=True)
        d.tap("Новая вкладка", exact=True)
        time.sleep(2)
        d.tap("Введите запрос или URL", exact=True)
        time.sleep(1)
        d.type("antitecheck")
        d.shell("input keyevent 66")
        time.sleep(4)
        if d.find("search.example") is None:
            d.fail("the search did not go to the own address")

        print("3. an own DNS provider")
        to_browser(d)
        open_settings_page(d, "Безопасность")
        d.scroll_to("Безопасный DNS")
        d.tap("Безопасный DNS", exact=True)
        time.sleep(1)
        if d.find("Провайдер", exact=True) is None:
            d.tap("Использовать безопасный DNS", exact=True)
            time.sleep(1)
        d.scroll_to("Свой")
        d.tap("Свой", exact=False)
        time.sleep(1)
        d.type(f"'{DOH}'")
        d.tap("Сохранить", exact=True)
        time.sleep(1.5)
        d.wait("Свой: dns.example", exact=True)
        d.shell("input keyevent BACK")
        time.sleep(1)
        d.wait("dns.example", exact=True)

        print("4. an own text size and number of tabs in memory")
        to_browser(d)
        open_settings_page(d, "Внешний вид")
        own_value(d, "Размер текста", "125")
        d.wait("Свой: 125%", exact=True)
        d.shell("input keyevent BACK")
        time.sleep(1)
        d.wait("125%", exact=True)
        to_browser(d)
        open_settings_page(d, "Вкладки")
        own_value(d, "Вкладок в памяти", "3")
        d.wait("Свой: 3", exact=True)
    finally:
        to_browser(d)
        open_settings_page(d, "Поиск и главная")
        pick(d, "Поисковая система", "DuckDuckGo")
        to_browser(d)
        open_settings_page(d, "Внешний вид")
        pick(d, "Размер текста", "100%")
        to_browser(d)
        open_settings_page(d, "Вкладки")
        pick(d, "Вкладок в памяти", "6")
        to_browser(d)
        open_settings_page(d, "Безопасность")
        d.scroll_to("Безопасный DNS")
        d.tap("Безопасный DNS", exact=True)
        time.sleep(1)
        if d.find("Провайдер", exact=True) is not None:
            d.tap("Использовать безопасный DNS", exact=True)
            time.sleep(1)
        to_browser(d)
    print("OK")


if __name__ == "__main__":
    main()
