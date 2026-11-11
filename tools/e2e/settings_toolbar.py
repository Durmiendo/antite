"""Settings for what was hard-coded: with "Hide on scroll" off the toolbar stays after a long scroll; with frequent sites
set to "Don't show" the new tab page has no frequent section. Both go back to their defaults at the end.

Usage: python3 -I tools/e2e/settings_toolbar.py --serial SERIAL
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from toolbar_snap import PORT, serve, toolbar_bottom  # noqa: E402


def open_settings_page(d, page):
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    d.tap("Настройки", exact=True)
    d.wait("Поиск по настройкам", exact=True)
    time.sleep(1)
    d.tap(page, exact=True)
    time.sleep(1)


def back_to_page(d, times):
    for _ in range(times):
        d.shell("input keyevent BACK")
        time.sleep(1)
    d.wait("Меню", exact=True)


def long_scroll_keeps_toolbar(d, w, h):
    d.stop()
    d.launch(f"http://localhost:{PORT}/s")
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(3)
    shown = toolbar_bottom(d)
    d.shell(f"input swipe {w // 2} {h * 3 // 4} {w // 2} {h // 4} 300")
    time.sleep(1.5)
    return shown, toolbar_bottom(d)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    http = serve()
    d.adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    w, h = (int(x) for x in d.shell("wm size").split()[-1].split("x"))

    print("1. hide on scroll off: the toolbar stays")
    d.stop()
    d.launch(f"http://localhost:{PORT}/s")
    d.wait("Меню", timeout=30, exact=True)
    open_settings_page(d, "Внешний вид")
    d.scroll_to("Скрывать при прокрутке")
    d.tap("Скрывать при прокрутке", exact=True)
    back_to_page(d, 2)
    shown, after = long_scroll_keeps_toolbar(d, w, h)
    print(f"  toolbar bottom {shown} -> {after}")
    open_settings_page(d, "Внешний вид")
    d.scroll_to("Скрывать при прокрутке")
    d.tap("Скрывать при прокрутке", exact=True)
    back_to_page(d, 2)
    if after != shown:
        d.fail("the toolbar moved with hiding turned off")
    shown, after = long_scroll_keeps_toolbar(d, w, h)
    print(f"  back on: toolbar bottom {shown} -> {after}")
    if after >= shown:
        d.fail("the toolbar did not hide with hiding turned back on")

    print("2. no frequent sites on the new tab page")
    d.stop()
    d.launch(f"http://localhost:{PORT}/s")
    d.wait("Меню", timeout=30, exact=True)
    open_settings_page(d, "Поиск и главная")
    d.tap("Часто посещаемые на новой вкладке", exact=True)
    d.tap("Не показывать", exact=True)
    back_to_page(d, 3)
    d.tap("Меню", exact=True)
    d.tap("Новая вкладка", exact=True)
    d.wait("Закреплённые", exact=True)
    time.sleep(1)
    missing = d.find("Часто посещаемые", exact=True) is None
    d.shell("input keyevent BACK")
    time.sleep(1)
    open_settings_page(d, "Поиск и главная")
    d.tap("Часто посещаемые на новой вкладке", exact=True)
    d.tap("12", exact=True)
    back_to_page(d, 3)
    if not missing:
        d.fail("the frequent section is still on the new tab page")
    http.shutdown()
    print("OK")


if __name__ == "__main__":
    main()
