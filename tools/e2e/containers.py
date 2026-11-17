"""Containers: a cookie set in the common storage is not seen in a container, a cookie set in a container is seen by
the next tab of the same container and not by another container, and a tab stays in its container after a restart.

Usage: python3 -I tools/e2e/containers.py --serial HOST:PORT

Needs an engine with engineApi 4. Latin names only: adb input text cannot type Cyrillic. Leaves no container behind.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from nav import open_tabs_settings, to_browser  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8775
NAMES = ("Alpha", "Beta")


def page(cookie):
    """Reports whether its cookie was already there, then sets it."""
    return (f"<!doctype html><meta name=viewport content='width=device-width'><h1>cookie probe</h1><script>"
            f"fetch('/report?seen=' + (document.cookie.includes('{cookie}=') ? 1 : 0) + '&t=' + Date.now());"
            f"document.cookie = '{cookie}=1; max-age=600; path=/';</script>").encode()


def open_containers(d):
    open_tabs_settings(d)
    d.scroll_to("Контейнеры")
    d.tap("Контейнеры", exact=True)
    time.sleep(1)


def delete_containers(d):
    open_containers(d)
    for name in NAMES:
        while d.find(name, exact=True) is not None:
            d.tap(name, exact=True)
            time.sleep(1)
            d.tap("Удалить контейнер", exact=True)
            d.tap("Удалить", exact=True)
            time.sleep(1.5)
    to_browser(d)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    cookie = f"k{int(time.time())}"
    server = ReportServer(PORT, {"/probe": page(cookie)})
    server.attach(d)
    url = f"http://localhost:{PORT}/probe"

    def seen(what):
        r = server.report(timeout=30)
        if r is None:
            d.fail(f"{what}: the page did not report")
        return r.split("&")[0]

    def open_in(name, what):
        d.wait("Меню", timeout=30, exact=True)
        d.tap("Меню", exact=True)
        d.tap("Открыть в контейнере…", exact=True)
        d.tap(name, exact=True)
        return seen(what)

    d.stop()
    d.launch(url)
    d.wait("Меню", timeout=30, exact=True)
    first = seen("first load")
    time.sleep(2)
    delete_containers(d)
    try:
        print("1. the common storage keeps the cookie between tabs")
        d.launch(url)
        if first != "seen=0" or seen("second load") != "seen=1":
            d.fail("control: the cookie did not behave in the common storage")

        print("2. two containers")
        open_containers(d)
        for name in NAMES:
            d.tap("Добавить контейнер", exact=True)
            d.type(name)
            d.tap("Сохранить", exact=True)
            d.wait("Удалить контейнер", exact=True)
            d.shell("input keyevent BACK")
            time.sleep(1)
        to_browser(d)

        print("3. a container does not see the cookie of the common storage, and keeps its own")
        if open_in(NAMES[0], "first tab of the container") != "seen=0":
            d.fail("the container sees a cookie from the common storage")
        if open_in(NAMES[0], "second tab of the container") != "seen=1":
            d.fail("a second tab of the container does not see the cookie set in it")

        print("4. another container does not see it")
        if open_in(NAMES[1], "tab of the other container") != "seen=0":
            d.fail("a container sees the cookie of another one")
        mark = f"Контейнер «{NAMES[1]}»"
        d.wait(mark, exact=True)

        print("5. after a restart the tab is still in its container")
        d.stop()
        d.launch()
        d.wait("Меню", timeout=30, exact=True)
        d.wait(mark, exact=True)
        if seen("restored tab") != "seen=1":
            d.fail("the restored tab does not see its container's cookie")
    finally:
        delete_containers(d)
        server.close()
    print("OK")


if __name__ == "__main__":
    main()
