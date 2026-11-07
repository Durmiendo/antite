"""End-to-end check of data export, reinstall and import on a device.

Needs ./gradlew :engine-gecko:adapterPackage first: each fresh install downloads the engine from a local source.

Usage: python3 -I tools/e2e/transfer.py --serial HOST:PORT --apk app-x86_64-debug.apk [--pin 1234]

WARNING: reinstalls the browser, so all of its data on the device is replaced.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from server import ReportServer  # noqa: E402
from engine_setup import download_engine, engine_pages  # noqa: E402

PORT = 8767
FILE = f"e2e-{int(time.time())}.antite"
PASSWORD = "e2e-pass-1"
MARK_PAGE = b"""<!doctype html><meta name=viewport content="width=device-width"><body>
<script>
if (location.hash == '#set') { document.cookie = 'mark=c-42; max-age=31536000; path=/'; localStorage.setItem('mark', 'ls-42') }
const r = 'cookie=' + document.cookie + ';ls=' + localStorage.getItem('mark')
document.body.textContent = r
fetch('/report?' + encodeURIComponent(r))
</script>"""

def open_transfer_page(d):
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    d.tap("Настройки", exact=True)
    d.scroll_to("Перенос данных")
    d.tap("Перенос данных", exact=True)


def finish_transfer(d, done_text):
    d.wait(done_text, timeout=180)
    d.tap("Открыть браузер")
    time.sleep(3)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--pin", help="device PIN, needed if the device has a screen lock")
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)

    server = ReportServer(PORT, {**engine_pages(PORT), "/mark": MARK_PAGE})
    server.attach(d)

    print("1. clean install with an engine, set marker cookie and localStorage")
    d.adb("uninstall", PACKAGE, check=False)
    d.install(a.apk)
    download_engine(d, PORT)
    d.launch(f"http://localhost:{PORT}/mark#set")
    r = server.report()
    if r != "cookie=mark=c-42;ls=ls-42":
        d.fail(f"marker not set: {r!r}")

    print("2. export")
    open_transfer_page(d)
    d.tap("Экспортировать данные")
    if d.find("Сохранить") is None:
        time.sleep(2)
        if d.find("Сохранить") is None and a.pin:
            d.type(a.pin)
            d.shell("input keyevent 66")
    d.wait("Сохранить")
    field = d.find_class("android.widget.EditText")
    if field is None:
        d.fail("no file name field")
    x, y = d.center(field)
    d.shell(f"input tap {x} {y}")
    d.shell("input keyevent 123 " + " ".join(["67"] * 40))
    d.type(FILE)
    d.tap("Сохранить")
    d.tap("Пароль", exact=True)
    d.type(PASSWORD)
    d.tap("Повторите пароль", exact=True)
    d.type(PASSWORD)
    d.tap("Экспортировать", exact=True)
    finish_transfer(d, "Все данные сохранены")

    print("3. reinstall (wipes all browser data)")
    d.adb("uninstall", PACKAGE)
    d.install(a.apk)
    download_engine(d, PORT)
    d.launch(f"http://localhost:{PORT}/mark")
    r = server.report()
    if r != "cookie=;ls=null":
        d.fail(f"fresh install still has data: {r!r}")

    print("4. import")
    open_transfer_page(d)
    d.tap("Импортировать данные")
    d.tap(FILE)
    d.tap("Пароль", exact=True)
    d.type(PASSWORD)
    d.tap("Далее", exact=True)
    d.tap("Импортировать", exact=True)
    finish_transfer(d, "Данные восстановлены")

    print("5. check marker after import")
    d.launch(f"http://localhost:{PORT}/mark")
    r = server.report()
    if r != "cookie=mark=c-42;ls=ls-42":
        d.fail(f"marker lost after import: {r!r}")
    d.delete_media(FILE)
    print("OK")
    server.close()


if __name__ == "__main__":
    main()
