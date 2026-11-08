"""Links from other apps: only http and https are opened. An explicit intent can carry any scheme past the
intent filter, so javascript: must not run in the open page and file: must not be loaded.

Usage: python3 -I tools/e2e/external_intent.py --serial HOST:PORT

Switches "Ссылки из других приложений" to the current tab for the test (the dangerous case) and back.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8772
PAGE = b"<!doctype html><title>victim</title><body><h1>victim page</h1>"


def set_external(d, label):
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    d.tap("Настройки", exact=True)
    d.scroll_to("Вкладки")
    d.tap("Вкладки", exact=True)
    d.tap("Ссылки из других приложений", exact=True)
    d.tap(label, exact=True)
    for _ in range(3):
        d.shell("input keyevent BACK")
        time.sleep(0.7)


def send(d, url):
    d.shell(f"am start -n {PACKAGE}/.MainActivity -a android.intent.action.VIEW -d '{url}'")
    time.sleep(6)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    server = ReportServer(PORT, {"/page": PAGE})
    server.attach(d)
    d.stop()
    d.launch(f"http://localhost:{PORT}/page")
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(3)
    set_external(d, "В текущей вкладке")
    try:
        print("1. javascript: from another app does not run in the open page")
        while server.report(timeout=0.1):
            pass
        send(d, 'javascript:fetch("/report?"+document.domain)')
        got = server.report(timeout=4)
        if got is not None:
            d.fail(f"javascript: from an intent ran in the page, origin {got!r}")

        print("2. file: from another app is not loaded")
        send(d, f"file:///data/data/{PACKAGE}/shared_prefs/antite.xml")
        if d.find("file://") is not None or d.find("antite.xml") is not None:
            d.fail("file: from an intent was loaded")

        print("3. https from another app still opens")
        send(d, f"http://localhost:{PORT}/page?again")
        d.wait("localhost", timeout=10)
    finally:
        d.stop()
        d.launch()
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(1)
        set_external(d, "В новой вкладке")
        server.close()
    print("OK")


if __name__ == "__main__":
    main()
