"""Update source: an added source that signs an index with engine and shell version 9999 must not take over
updates. Only the chosen update source is checked; the other one is checked by hand from its own page.

Usage: python3 -I tools/e2e/update_source.py --serial HOST:PORT

Keeps the browser's data: adds the foreign source through the settings and removes it at the end.
Needs network for the built-in source (GitHub).
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from engine_setup import prop  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8771
NAME = "Chuzhoj"


def foreign_source(out_dir):
    """A key of our own and an index signed with it, offering engine 9999.0 and shell 9999."""
    key = os.path.join(out_dir, "foreign.key.pem")
    if not os.path.exists(key):
        subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", key], check=True)
    der = subprocess.run(["openssl", "ec", "-in", key, "-pubout", "-outform", "DER"],
                         capture_output=True, check=True).stdout
    fake = {"url": [f"http://localhost:{PORT}/never"], "size": 1, "sha256": "00" * 32}
    abis = ["arm64-v8a", "armeabi-v7a", "x86_64"]
    index = json.dumps({
        "format": 1,
        "serial": int(time.time()),
        "expires": int(time.time()) + 30 * 86400,
        "engines": [{
            "id": "gecko", "type": "gecko", "api": int(prop("engineApi")), "channel": "release",
            "version": "9999.0", "minShell": 1, "adapter": fake, "upstream": {a: fake for a in abis},
        }],
        "shell": {"version": 9999, "apk": {a: fake for a in abis}},
    }).encode()
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key], input=index, capture_output=True, check=True).stdout
    return base64.b64encode(der).decode(), {"/engines.json": index, "/engines.json.sig": sig}


def open_updates(d):
    d.stop()
    d.launch()
    # A cold start can be slow; Back before the page is up would send the browser to the background.
    end = time.time() + 20
    while time.time() < end and d.find("Меню", exact=True) is None:
        time.sleep(1)
    for _ in range(4):
        if d.find("Меню", exact=True) is not None:
            break
        time.sleep(3)
        # An update offer may cover the toolbar; Back dismisses it without postponing.
        d.shell("input keyevent BACK")
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(1)
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    d.tap("Настройки", exact=True)
    d.scroll_to("О программе")
    d.tap("О программе", exact=True)
    d.tap("Обновления", exact=True)


def cleanup(d):
    """Removes the foreign source and checks again, so no offer from it stays in the browser's state."""
    print("cleanup: remove the foreign source")
    open_updates(d)
    d.scroll_to("Источники")
    d.tap("Источники", exact=True)
    if d.find(NAME, exact=True) is not None:
        d.tap(NAME, exact=True)
        d.scroll_to("Удалить источник")
        d.tap("Удалить источник", exact=True)
        d.tap("Удалить", exact=True)
        time.sleep(1)
    d.shell("input keyevent BACK")
    time.sleep(1)
    d.tap("Проверить сейчас", exact=True)
    time.sleep(8)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    ap.add_argument("--cleanup", action="store_true", help="only remove the foreign source left by a failed run")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    if a.cleanup:
        cleanup(d)
        return
    key, pages = foreign_source(a.out)
    server = ReportServer(PORT, pages)
    server.attach(d)

    def shown(text, exact=False):
        return d.find(text, exact) is not None

    open_updates(d)

    print("1. add a foreign source that offers 9999")
    d.scroll_to("Источники")
    d.tap("Источники", exact=True)
    d.tap("Добавить источник", exact=True)
    d.tap("Название", exact=True)
    d.type(NAME)
    d.tap("Адрес индекса", exact=False)
    d.type(f"http://localhost:{PORT}/engines.json")
    d.tap("Открытый ключ издателя", exact=True)
    d.type(key)
    d.tap("Далее", exact=True)
    d.tap("Добавить", exact=True)
    d.wait(NAME, exact=True)
    d.shell("input keyevent BACK")
    time.sleep(1)

    try:
        print("2. checking updates does not ask the foreign source and offers nothing from it")
        server.requests.clear()
        d.tap("Проверить сейчас", exact=True)
        time.sleep(8)
        if any("9999" in (n.get("text") or "") for n in d.nodes()):
            d.fail("an update from the foreign source is offered")
        if server.requests:
            d.fail(f"the foreign source was asked: {server.requests}")
        if shown("Отмена", exact=True):
            d.shell("input keyevent BACK")
            time.sleep(1)

        print("3. the foreign source is checked from its page, by hand")
        d.scroll_to("Источники")
        d.tap("Источники", exact=True)
        d.tap(NAME, exact=True)
        d.tap("Проверить этот источник", exact=True)
        d.wait("9999", timeout=15)
        d.tap("Отмена", exact=True)
        time.sleep(1)

        print("4. reopening the browser shows no update offer")
        d.shell("input keyevent HOME")
        time.sleep(1)
        d.launch()
        time.sleep(4)
        if shown("9999") or shown("Доступен новый движок") or shown("Доступна новая версия Antite"):
            d.fail("an update offer appeared after the manual check")
    finally:
        cleanup(d)
        server.close()
    print("OK")


if __name__ == "__main__":
    main()
