"""The R8-shrunk release shell still loads the engine and its screens work.

Usage: ./gradlew :engine-gecko:adapterPackage :app:assembleRelease
       python3 -I tools/e2e/release_check.py --serial SERIAL --apk app/build/outputs/apk/release/app-release.apk

Release builds take no test source from an intent, so the local source is added on the first-start screen.
Reinstalls the browser, so its data is lost.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from engine import engine_pages, prop  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8773


def add_local_source(d, key):
    print("2. add the local source on the first-start screen")
    d.tap("Другой источник", timeout=60, exact=True)
    d.tap("Адрес индекса", exact=False)
    d.type(f"http://localhost:{PORT}/engines.json")
    d.tap("Открытый ключ издателя", exact=True)
    d.type(key)
    d.tap("Добавить", exact=True)
    d.wait("Ключ SHA-256", timeout=30)
    d.wait("Gecko " + prop("geckoviewVersion"))
    d.tap("Скачать", exact=True)
    d.wait("Закреплённые", timeout=300, exact=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    pages = engine_pages(PORT)
    pages["/page"] = b"<!doctype html><body><script>fetch('/report?' + navigator.userAgent)</script>"
    server = ReportServer(PORT, pages)
    server.attach(d)
    key = "".join(l for l in open("keys/publisher.pub.pem").read().splitlines() if not l.startswith("-----"))

    print("1. clean install of the release shell")
    d.adb("uninstall", PACKAGE, check=False)
    d.install(a.apk)
    # The system's notification prompt would cover the first-start screen.
    d.shell(f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS", check=False)
    d.launch()
    published = d.find("Другой источник", exact=True) is None and d.wait("Далее", timeout=60, exact=True) is not None
    if published:
        print("2. the published source gave the engine; the first-start steps")
        for _ in range(6):
            if d.find("Закреплённые", exact=True) is not None:
                break
            d.wait("Движок готов", timeout=300)
            d.tap(next(t for t in ("Начать", "Далее") if d.find(t, exact=True) is not None), exact=True)
            time.sleep(1)
        d.wait("Закреплённые", timeout=60, exact=True)
    else:
        add_local_source(d, key)

    print("3. open a page")
    d.launch(f"http://localhost:{PORT}/page")
    ua = server.report(timeout=60)
    if not ua or "Firefox" not in ua:
        d.fail(f"page did not run: {ua!r}")
    print("page ran:", ua)

    print("4. settings screens")
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    d.tap("Настройки", exact=True)
    d.scroll_to("О программе")
    d.tap("О программе", exact=True)
    d.tap("Обновления", exact=True)
    d.wait("Работает")
    d.scroll_to("Источники")
    d.tap("Источники", exact=True)
    if not published:
        d.wait("localhost", exact=True)
    d.wait("Antite", exact=True)
    d.shell("input keyevent BACK")
    d.shell("input keyevent BACK")
    d.shell("input keyevent BACK")
    d.scroll_to("Конфиденциальность")
    d.tap("Конфиденциальность", exact=True)
    d.scroll_to("Что отправляет браузер")
    d.tap("Что отправляет браузер", exact=True)
    d.wait("Проверка подключения", exact=True)
    if PACKAGE not in d.top_activity():
        d.fail("browser left the screen")
    server.close()
    print("OK")


if __name__ == "__main__":
    main()
