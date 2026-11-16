"""Dead index warning: when the update source answers with an index past its date, a bar on the browser screen says
that no updates are coming; "Проверить" asks the source again and the bar goes away once the index is alive.

Usage: python3 -I tools/e2e/index_expired.py --serial HOST:PORT

Keeps the browser's data: adds a source through the settings, makes it the update source for the test, then gives the
role back and removes it.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from server import ReportServer  # noqa: E402
from update_source import open_updates, signed  # noqa: E402

PORT = 8772
NAME = "Prosrochen"
BAR = "Обновления не приходят"


def index(expires_in):
    return {"format": 1, "serial": int(time.time()), "expires": int(time.time()) + expires_in, "engines": []}


def choose_update_source(d, name):
    d.scroll_to("Источник обновлений")
    d.tap("Источник обновлений", exact=True)
    d.tap(name, exact=True)
    if d.find("Сменить", exact=True) is not None:
        d.tap("Сменить", exact=True)
    time.sleep(1)
    d.shell("input keyevent BACK")
    time.sleep(1)


def update_source_name(d):
    d.scroll_to("Источник обновлений")
    texts = [n.get("text") for n in d.nodes() if n.get("text")]
    return texts[texts.index("Источник обновлений") + 1]


def to_browser(d):
    for _ in range(3):
        d.shell("input keyevent BACK")
        time.sleep(1)
    d.wait("Меню", exact=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    key, pages = signed(a.out, index(-3 * 86400))
    server = ReportServer(PORT, pages)
    server.attach(d)

    open_updates(d)
    original = update_source_name(d)
    print(f"update source before the test: {original}")

    print("1. a source with an index that ran out three days ago becomes the update source")
    d.scroll_to("Источники")
    d.tap("Источники", exact=True)
    d.tap("Добавить источник", exact=True)
    d.tap("Название", exact=True)
    d.type(NAME)
    d.tap("Адрес индекса", exact=False)
    d.type(f"http://localhost:{PORT}/engines.json")
    d.tap("Открытый ключ издателя", exact=True)
    d.type(key)
    d.tap("Далее", exact=True)
    d.tap("Добавить", exact=True)
    d.wait(NAME, exact=True)
    d.shell("input keyevent BACK")
    time.sleep(1)
    try:
        choose_update_source(d, NAME)
        d.scroll_to("Проверить сейчас")
        d.tap("Проверить сейчас", exact=True)
        time.sleep(5)

        print("2. the browser screen warns after coming back to it")
        to_browser(d)
        d.shell("input keyevent HOME")
        time.sleep(1)
        d.launch()
        d.wait(BAR, timeout=15)

        print("3. still dead: the bar stays after a check")
        d.tap("Проверить", exact=True)
        time.sleep(5)
        if d.find(BAR) is None:
            d.fail("the bar went away while the index is still out of date")

        print("4. the source publishes a live index: the bar goes away after a check")
        time.sleep(1)
        pages.update(signed(a.out, index(30 * 86400))[1])
        d.tap("Проверить", exact=True)
        time.sleep(5)
        if d.find(BAR) is not None:
            d.fail("the bar stayed although the index is alive again")
    finally:
        print(f"cleanup: update source back to {original}, the test source removed")
        open_updates(d)
        choose_update_source(d, original)
        d.scroll_to("Источники")
        d.tap("Источники", exact=True)
        if d.find(NAME, exact=True) is not None:
            d.tap(NAME, exact=True)
            d.scroll_to("Удалить источник")
            d.tap("Удалить источник", exact=True)
            d.tap("Удалить", exact=True)
            time.sleep(1)
        d.shell("input keyevent BACK")
        time.sleep(1)
        d.scroll_to("Проверить сейчас")
        d.tap("Проверить сейчас", exact=True)
        time.sleep(8)
        server.close()
    print("OK")


if __name__ == "__main__":
    main()
