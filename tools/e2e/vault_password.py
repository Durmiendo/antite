"""Key ring with a password: switching the key to a password, the storage locked after leaving the
app opens only with the password (a wrong one is refused), a file opens after the cipher is changed to the cascade,
and everything goes back to the device key and AES at the end.

Usage: python3 -I tools/e2e/vault_password.py --serial HOST:PORT --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

Latin password: adb input text cannot type Cyrillic. Leaves the device key, AES and no test file behind.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from nav import to_browser  # noqa: E402
from vault import PASSWORD, open_keys, open_vault, password_dialog, pick, reset, type_into, file_action  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    ap.add_argument("--test-apk", required=True)
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    picture = f"kp-{int(time.time()) % 100000}.png"
    d.install(a.test_apk)
    d.stop()
    d.launch()
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(2)
    reset(d)
    try:
        print("0. a picture in the storage")
        d.shell(f"am start -a android.intent.action.SEND -t image/png "
                f"--eu android.intent.extra.STREAM content://{PACKAGE}.test.share/{picture} -n {PACKAGE}/.ShareToVault")
        time.sleep(4)

        print("1. the key goes to a password")
        open_keys(d)
        pick(d, "Ключ", "Пароль")
        password_dialog(d, PASSWORD, twice=True)
        d.wait("без гарантии стирания", timeout=30)

        print("2. after leaving the app the storage opens only with the password")
        d.shell("input keyevent HOME")
        time.sleep(2)
        d.shell(f"am start -n {PACKAGE}/.MainActivity")
        time.sleep(2)
        to_browser(d)
        d.tap("Меню", exact=True)
        d.tap("Защищённое хранилище", exact=True)
        d.wait("Хранилище заблокировано", exact=True)
        d.tap("Разблокировать", exact=True)
        password_dialog(d, "wrong" + PASSWORD)
        d.wait("Неверный пароль", timeout=30, exact=True)
        type_into(d, "Пароль", PASSWORD)
        d.shell("input keyevent 4")
        time.sleep(0.8)
        d.tap("OK", exact=True)
        d.wait(picture, timeout=30, exact=True)

        print("3. the cascade: the file still opens")
        open_keys(d)
        pick(d, "Шифр", "Каскад XChaCha20 + AES")
        d.wait("Каскад XChaCha20 + AES", exact=True)
        time.sleep(3)
        to_browser(d)
        open_vault(d)
        d.tap(picture, exact=True)
        d.wait("1 / ", timeout=15)
        d.shell("input keyevent BACK")
        time.sleep(1)
    finally:
        print("4. back to the device key and AES")
        reset(d)
        to_browser(d)
        open_vault(d)
        if d.find(picture, exact=True) is not None:
            file_action(d, picture, "Удалить")
            d.tap("Удалить", exact=True)
            time.sleep(1)
        to_browser(d)
    print("OK")


if __name__ == "__main__":
    main()
