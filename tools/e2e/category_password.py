"""A password of its own as a tab category's lock (decision 0011): the category's key moves from its key file into a
ring of its own, after leaving the browser a wrong password is refused and the right one opens the tabs, and taking
the lock off puts the key back in a file and destroys the ring.

Usage: python3 -I tools/e2e/category_password.py --serial HOST:PORT  (with the androidTest APK installed)

Needs no screen lock. Latin password: adb input text cannot type Cyrillic. Leaves no category behind.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from nav import open_category_settings, open_switcher, to_browser  # noqa: E402
from prefs import no_tabs  # noqa: E402
from server import ReportServer  # noqa: E402
from vault import password_dialog, type_into  # noqa: E402

PORT = 8775
NAME = "Secret"
LOCKED = f"Категория «{NAME}» заблокирована"
PASSWORD = "kat3gory"
TITLE = f"Parol{int(time.time()) % 100000}"
PAGE = f"<!doctype html><title>{TITLE}</title><meta name=viewport content='width=device-width'><h1>page</h1>".encode()
KEYS = "history/keys"


def keys(d):
    """Names in the key folder; the app's process is restarted by the instrumentation."""
    out = d.shell(f"am instrument -w -r -e class {PACKAGE}.e2etools.FilesTool -e dir {KEYS} "
                  f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner")
    line = next((l for l in out.splitlines() if l.startswith("INSTRUMENTATION_STATUS: files=")), None)
    if line is None:
        d.fail(f"files not listed: {out[-300:]}")
    return {n for n in line.split("=", 1)[1].strip().split("/") if n}


def delete_category(d):
    """Deleting a locked category asks for its lock first: here the password."""
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
            password_dialog(d, PASSWORD)
        d.wait("Удалить", timeout=30, exact=True)
        d.tap("Удалить", exact=True)
        time.sleep(1.5)
    to_browser(d)


def choose_lock(d, label):
    open_category_settings(d)
    d.tap(NAME, exact=True)
    time.sleep(1)
    d.scroll_to(label)
    d.tap(label, exact=True)
    time.sleep(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    server = ReportServer(PORT, {"/secret": PAGE, "/quiet": b"<!doctype html><h1>quiet</h1>"})
    server.attach(d)
    try:
        no_tabs(d)
        d.launch(f"http://localhost:{PORT}/quiet")
        d.wait("Меню", timeout=60, exact=True)
        time.sleep(3)
        delete_category(d)

        print("1. a category with one visited page")
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
        d.shell("input tap %d %d" % d.center(plus[0]))
        time.sleep(2)
        d.type(f"http://localhost:{PORT}/secret")
        d.shell("input keyevent 66")
        time.sleep(4)
        before = keys(d)
        open_files = {k for k in before if k.startswith("category-") and k.endswith(".key")}
        print(f"  key files: {sorted(before)}")
        # The instrumentation restarts the app's process.
        d.shell(f"am start -n {PACKAGE}/.MainActivity")
        d.wait("Меню", timeout=60, exact=True)

        print("2. the category's password as its lock: its key file goes, a ring of its own comes")
        choose_lock(d, "Пароль категории")
        password_dialog(d, PASSWORD, twice=True)
        d.wait("✓ Пароль категории", timeout=30, exact=True)
        after = keys(d)
        rings = {k for k in after - before if k.startswith("locked-")}
        gone = open_files - after
        print(f"  key files: {sorted(after)}")
        if len(rings) != 1 or len(gone) != 1 or gone.pop().removeprefix("category-").removesuffix(".key") != rings.pop().removeprefix("locked-"):
            d.fail(f"the key did not move into a ring: {sorted(before)} -> {sorted(after)}")

        print("3. after leaving the browser a wrong password is refused, the right one opens the tabs")
        d.shell("input keyevent HOME")
        time.sleep(2)
        d.shell(f"am start -n {PACKAGE}/.MainActivity")
        time.sleep(2)
        d.wait(LOCKED, exact=True)
        d.tap("Разблокировать", exact=True)
        password_dialog(d, "wrong" + PASSWORD)
        d.wait("Неверный пароль", timeout=30, exact=True)
        type_into(d, "Пароль", PASSWORD)
        d.shell("input keyevent 4")
        time.sleep(0.8)
        d.tap("OK", exact=True)
        d.wait("page", timeout=30, exact=True)
        if d.find(LOCKED, exact=True) is not None:
            d.fail("still locked after the password")

        print("4. the lock taken off: the key back in a file, the ring destroyed")
        to_browser(d)
        choose_lock(d, "Без замка")
        d.wait("✓ Без замка", timeout=30, exact=True)
        final = keys(d)
        print(f"  key files: {sorted(final)}")
        if any(k.startswith("locked-") for k in final - before) or not open_files <= final:
            d.fail(f"the ring is still there or the key file is missing: {sorted(final)}")
    finally:
        try:
            d.stop()
            d.launch(f"http://localhost:{PORT}/quiet")
            d.wait("Меню", timeout=60, exact=True)
            time.sleep(2)
            delete_category(d)
        finally:
            server.close()
    print("OK")


if __name__ == "__main__":
    main()
