"""Secure storage stays open after the device unlock that opened it ("lock on exit" used to lock it again when the
unlock dialog returned to the app), and is locked again after leaving the app.

Usage: python3 -I tools/e2e/vault_lock.py --serial HOST:PORT

Sets a temporary screen lock PIN for the unlock prompt and removes it at the end.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from nav import to_browser  # noqa: E402

PIN = "1590"


def open_vault(d):
    to_browser(d)
    d.tap("Меню", exact=True)
    d.tap("Защищённое хранилище", exact=True)
    time.sleep(1.5)


def unlock(d):
    if d.find("Разблокировать", exact=True) is not None:
        d.tap("Разблокировать", exact=True)
    time.sleep(2)
    d.type(PIN)
    d.shell("input keyevent 66")
    time.sleep(2.5)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    d.shell(f"locksettings set-pin {PIN}")
    try:
        d.stop()
        d.launch()
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(2)
        print("1. open the storage, enter the PIN")
        open_vault(d)
        unlock(d)
        if d.find("Хранилище заблокировано") is not None:
            d.fail("storage locked again right after unlocking")
        d.wait("Защищённое хранилище")
        print("2. leave the app and come back: locked")
        d.shell("input keyevent HOME")
        time.sleep(2)
        d.shell(f"am start -n {PACKAGE}/.MainActivity")
        time.sleep(2)
        if "keyguard" in d.top_activity().lower() or d.find("PIN") is not None:
            d.type(PIN)
            d.shell("input keyevent 66")
            time.sleep(2)
        if d.find("Хранилище заблокировано") is None:
            open_vault(d)
            d.wait("Хранилище заблокировано")
        print("OK: stays open after unlock, locks after leaving")
    finally:
        d.shell(f"locksettings clear --old {PIN}", check=False)


if __name__ == "__main__":
    main()
