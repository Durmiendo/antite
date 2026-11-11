"""Always use secure connections, like Chrome: a site without HTTPS shows "Connection is not secure"; "Continue to site"
opens it over HTTP and the site stays allowed for the session. Needs internet: uses http://httpforever.com, which has no HTTPS.
Turns the setting back off at the end.

Usage: python3 -I tools/e2e/https_only.py --serial SERIAL
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402

SITE = "http://httpforever.com/"


def toggle_https_only(d):
    time.sleep(2)
    d.tap("Меню", exact=True)
    time.sleep(1)
    d.scroll_to("Настройки")
    d.tap("Настройки", exact=True)
    d.wait("Поиск по настройкам", exact=True)
    time.sleep(1)
    d.tap("Конфиденциальность и безопасность", exact=True)
    time.sleep(1)
    d.tap("Всегда использовать безопасные подключения", exact=True)
    time.sleep(1)
    for _ in range(2):
        d.shell("input keyevent BACK")
        time.sleep(1)
    d.wait("Меню", exact=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    d.stop()
    d.launch()
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(1)
    toggle_https_only(d)
    try:
        d.launch(SITE)
        d.wait("Подключение не защищено", timeout=40, exact=True)
        print("1. warning shown")
        # Gecko reports the upgraded https address; the bar must show the http the site really has.
        if d.find("http://httpforever.com/", exact=True) is None:
            d.fail("the address bar does not show http:// on the warning")
        print("1a. address bar shows http://")
        with open(os.path.join(a.out, "https-only-warning.png"), "wb") as f:
            f.write(d.adb("exec-out", "screencap", "-p", binary=True))
        d.tap("Перейти на сайт", exact=True)
        end = time.time() + 40
        while d.find("Подключение не защищено", exact=True) is not None or d.find("HTTP FOREVER") is None:
            if time.time() > end:
                d.fail("the site did not open over HTTP")
            time.sleep(1)
        print("2. opened over HTTP")
        # Gecko keeps the exception for the session, as Firefox does: opening the site again goes straight to HTTP.
        d.launch(SITE)
        time.sleep(8)
        if d.find("Подключение не защищено", exact=True) is not None or d.find("HTTP FOREVER") is None:
            d.fail("the site was not remembered in this session")
        print("3. remembered for the session")
    finally:
        d.stop()
        d.launch()
        d.wait("Меню", timeout=30, exact=True)
        toggle_https_only(d)
    print("OK")


if __name__ == "__main__":
    main()
