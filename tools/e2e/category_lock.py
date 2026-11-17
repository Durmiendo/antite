"""Unlock to enter for a tab category: after leaving the browser its page is covered, its tabs are neither on its
switcher page nor in "Все", no count tells how many there are, and the device unlock brings them back.

Usage: python3 -I tools/e2e/category_lock.py --serial HOST:PORT

Sets a temporary screen lock PIN for the unlock prompts and removes it at the end. Latin names only: adb input text
cannot type Cyrillic. Leaves no category behind and the per-category count off.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from nav import chip_count, open_switcher, open_tabs_settings, set_each_count, to_browser  # noqa: E402
from server import ReportServer  # noqa: E402

PIN = "1590"
PORT = 8774
NAME = "Secret"
# New on every run: tabs left by a run that failed must not pass for this one's.
TITLE = f"Sekret{int(time.time()) % 100000}"
PAGE = f"<!doctype html><title>{TITLE}</title><meta name=viewport content='width=device-width'><h1>page</h1>".encode()
LOCKED = f"Категория «{NAME}» заблокирована"


def toolbar_count(d):
    n = [n for n in d.nodes() if (n.get("text") or "").isdigit() and d.bounds(n)[1] < 230]
    return int(n[0].get("text")) if n else None


def total_count(d):
    """The total right of the switcher strip: the rightmost number in it."""
    n = [n for n in d.nodes() if (n.get("text") or "").isdigit() and d.bounds(n)[1] < 260]
    return int(max(n, key=lambda n: d.bounds(n)[0]).get("text")) if n else None


def enter_pin(d):
    time.sleep(2)
    d.type(PIN)
    d.shell("input keyevent 66")
    time.sleep(2.5)


def refuse_prompt(d):
    """Back until the unlock prompt is gone: the first press may only close its keyboard."""
    for _ in range(4):
        if "BiometricPrompt" not in d.shell("dumpsys window | grep mCurrentFocus"):
            return
        d.shell("input keyevent BACK")
        time.sleep(1.5)
    d.fail("the unlock prompt does not close")


def open_category_settings(d):
    open_tabs_settings(d)
    d.scroll_to("Мои категории")
    d.tap("Мои категории", exact=True)
    time.sleep(1)


def last_cards(d):
    """Titles on the open switcher page after scrolling to its end."""
    seen = None
    for _ in range(25):
        d.shell("input swipe 540 1900 540 500 80")
        time.sleep(1.2)
        now = [n.get("text") for n in d.nodes() if n.get("text")]
        if now == seen:
            break
        seen = now
    return seen or []


def delete_category(d):
    open_category_settings(d)
    if d.find(NAME, exact=True) is not None:
        d.tap(NAME, exact=True)
        time.sleep(1)
        d.scroll_to("Удалить категорию")
        d.shell("input swipe 540 1600 540 900 250")
        time.sleep(0.6)
        d.tap("Удалить категорию", exact=True)
        time.sleep(1.5)
        if d.find("Удалить", exact=True) is None:
            enter_pin(d)
        d.tap("Удалить", exact=True)
        time.sleep(1.5)
    to_browser(d)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    server = ReportServer(PORT, {"/secret": PAGE, "/quiet": b"<!doctype html><h1>quiet</h1>"})
    server.attach(d)
    d.shell(f"locksettings set-pin {PIN}")
    try:
        d.stop()
        # A plain page of our own: over a heavy site the screen dump comes without the browser's menu.
        d.launch(f"http://localhost:{PORT}/quiet")
        d.wait("Меню", timeout=60, exact=True)
        time.sleep(3)
        delete_category(d)
        set_each_count(d, True)

        print("1. a category with one tab")
        open_category_settings(d)
        d.tap("Добавить категорию", exact=True)
        d.type(NAME)
        d.tap("Сохранить", exact=True)
        d.wait("Цвет", exact=True)
        to_browser(d)
        open_switcher(d)
        d.tap(NAME, exact=True)
        time.sleep(1)
        plus = [n for n in d.nodes() if n.get("content-desc") == "Новая вкладка"]
        x, y = d.center(plus[0])
        d.shell(f"input tap {x} {y}")
        time.sleep(2)
        d.type(f"http://localhost:{PORT}/secret")
        d.shell("input keyevent 66")
        time.sleep(4)
        open_switcher(d)
        d.wait(TITLE)
        d.tap("Все", exact=True)
        time.sleep(1)
        if not any(TITLE in t for t in last_cards(d)):
            d.fail("control: the tab is not at the end of \"Все\" while the category is open")
        d.shell("input swipe 540 600 540 1900 80")
        time.sleep(1)
        all_open, total_open = chip_count(d, "Все"), total_count(d)
        if chip_count(d, NAME) != 1 or all_open is None or total_open is None:
            d.fail(f"control: counts while open: {NAME} {chip_count(d, NAME)}, all {all_open}, total {total_open}")
        d.shell("input keyevent BACK")
        time.sleep(1)
        bar_open = toolbar_count(d)
        print(f"  open: {NAME} 1, all {all_open}, total {total_open}, toolbar {bar_open}")

        print("2. unlock to enter turned on, with the PIN")
        open_category_settings(d)
        d.tap(NAME, exact=True)
        time.sleep(1)
        d.scroll_to("Вход по разблокировке")
        d.tap("Вход по разблокировке", exact=True)
        enter_pin(d)
        to_browser(d)

        print("3. after leaving the browser the page is covered")
        d.shell("input keyevent HOME")
        time.sleep(2)
        d.shell(f"am start -n {PACKAGE}/.MainActivity")
        time.sleep(2)
        if "keyguard" in d.top_activity().lower():
            enter_pin(d)
        d.wait(LOCKED, exact=True)
        bar = toolbar_count(d)
        print(f"  toolbar count {bar_open} -> {bar}")
        if bar_open is None or bar != bar_open - 1:
            d.fail(f"the toolbar count includes the locked tab: {bar_open} -> {bar}")

        print("4. the switcher shows the lock on its page and no tab of it in \"Все\"")
        d.tap("Открыть список вкладок", exact=True)
        time.sleep(3)
        # The category's page asks for the unlock by itself; refused here.
        refuse_prompt(d)
        d.wait(LOCKED, exact=True)
        if d.find(TITLE) is not None:
            d.fail("the tab is listed on the locked category's page")
        d.tap("Все", exact=True)
        time.sleep(1)
        if any(TITLE in t for t in last_cards(d)):
            d.fail("the locked category's tab is listed in \"Все\"")
        d.shell("input swipe 540 600 540 1900 80")
        time.sleep(1)
        counts = chip_count(d, NAME), chip_count(d, "Все"), total_count(d)
        print(f"  locked: {NAME} {counts[0]}, all {counts[1]}, total {counts[2]}")
        if counts != (None, all_open - 1, total_open - 1):
            d.fail(f"counts tell about the locked tab: {counts}")

        print("5. the PIN opens the category")
        d.tap(NAME, exact=True)
        enter_pin(d)
        d.wait(TITLE)
        if d.find(LOCKED, exact=True) is not None:
            d.fail("still locked after the PIN")
        d.tap(TITLE)
        time.sleep(1.5)
    finally:
        try:
            # A new tab outside the category: its locked page would cover the toolbar.
            d.stop()
            d.launch(f"http://localhost:{PORT}/quiet")
            d.wait("Меню", timeout=60, exact=True)
            time.sleep(2)
            delete_category(d)
            set_each_count(d, False)
        finally:
            d.shell(f"locksettings clear --old {PIN}", check=False)
            server.close()
    print("OK")


if __name__ == "__main__":
    main()
