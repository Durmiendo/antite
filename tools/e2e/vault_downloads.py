"""Downloads straight into the secure storage: with "Where to save files" set to the storage a file never shows in
downloads or MediaStore and appears in the storage; "Ask every time" offers both and "To downloads" saves as usual;
"Protect downloaded files" for incognito sends a file from an incognito tab to the storage without asking.
Settings are set back at the end.

Usage: python3 -I tools/e2e/vault_downloads.py --serial HOST:PORT

Waydroid has no screen lock, so the storage opens without an unlock prompt.
"""
import argparse
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from downloads import media_size  # noqa: E402
from media_vault import open_downloads, open_vault, row_actions  # noqa: E402
from settings_search import open_settings  # noqa: E402
from tab_categories import open_tabs_settings, to_browser  # noqa: E402

PORT = 8789
BODY = b"vault download test\n" * 200


def serve():
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            path = self.path.split("?")[0]
            if path.endswith(".txt"):
                data, kind = BODY, "text/plain"
            else:
                name = path.strip("/") or "x"
                data = f"""<!doctype html><meta name=viewport content="width=device-width">
<body style="margin:0"><a href="/{name}.txt" download style="display:block;height:70vh;font-size:40px">file</a>
</body>""".encode()
                kind = "text/html"
            self.send_response(200)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *args):
            pass

    http = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=http.serve_forever, daemon=True).start()
    return http


def set_target(d, label):
    to_browser(d)
    open_settings(d)
    d.scroll_to("Скачанные файлы")
    d.tap("Скачанные файлы", exact=True)
    d.tap("Куда сохранять файлы", exact=True)
    d.tap(label, exact=True)
    time.sleep(0.5)
    to_browser(d)


def switch_state(d, title):
    """The checked state of the switch row that holds this title."""
    x, y = d.center(d.wait(title, exact=True))
    for n in d.nodes():
        if n.get("checkable") == "true":
            x1, y1, x2, y2 = d.bounds(n)
            if x1 <= x <= x2 and y1 <= y <= y2:
                return n.get("checked") == "true"
    d.fail(f"no switch state for {title!r}")


def set_incognito_protect(d, on):
    open_tabs_settings(d)
    d.scroll_to("Инкогнито")
    d.tap("Инкогнито", exact=True)
    time.sleep(1)
    d.scroll_to("Защищать скачанные файлы")
    if switch_state(d, "Защищать скачанные файлы") != on:
        d.tap("Защищать скачанные файлы", exact=True)
    to_browser(d)


def download(d, name, incognito=False):
    """Opens the page with the file link and taps it (by position: Gecko's a11y bounds ignore the toolbar)."""
    if incognito:
        to_browser(d)
        d.tap("Меню", exact=True)
        d.tap("Новая вкладка инкогнито", exact=True)
        time.sleep(1.5)
        # A tab from the menu opens without focusing the address bar.
        d.tap("Введите запрос или URL", exact=True)
        time.sleep(0.5)
        d.type(f"http://localhost:{PORT}/{name}")
        d.shell("input keyevent 66")
    else:
        d.launch(f"http://localhost:{PORT}/{name}")
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(3)
    d.shell("input tap 540 900")
    time.sleep(1.5)


def in_vault(d, name):
    open_vault(d)
    ok = d.find(f"{name}.txt", exact=True) is not None
    if ok:
        row_actions(d, f"{name}.txt", "Удалить")
        d.tap("Удалить", exact=True)
        time.sleep(1)
    to_browser(d)
    return ok


def in_downloads(d, name):
    open_downloads(d)
    ok = d.find(f"{name}.txt", exact=True) is not None
    to_browser(d)
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    stamp = int(time.time())
    http = serve()
    d.adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    try:
        d.stop()
        d.launch()
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(2)

        set_target(d, "В защищённое хранилище")
        name = f"vd-a-{stamp}"
        download(d, name)
        time.sleep(4)
        if media_size(d, f"{name}.txt") is not None or in_downloads(d, name):
            d.fail("a file for the storage showed up in downloads")
        if not in_vault(d, name):
            d.fail("the file is not in the storage")
        print("1. 'to the storage': the file is only in the storage")

        set_target(d, "Спрашивать каждый раз")
        name = f"vd-b-{stamp}"
        download(d, name)
        d.tap("В загрузки", exact=True)
        end = time.time() + 20
        while media_size(d, f"{name}.txt") != len(BODY):
            if time.time() > end:
                d.fail("'to downloads' from the question did not save the file")
            time.sleep(1)
        print("2. 'ask every time': the question offers both, 'to downloads' saves as usual")

        set_target(d, "В «Загрузки»")
        set_incognito_protect(d, True)
        name = f"vd-c-{stamp}"
        download(d, name, incognito=True)
        time.sleep(4)
        if d.find("Куда сохранить", exact=False) is not None:
            d.fail("a protected tab asked where to save")
        if media_size(d, f"{name}.txt") is not None:
            d.fail("a file from a protected incognito tab went to downloads")
        if not in_vault(d, name):
            d.fail("the file from the protected incognito tab is not in the storage")
        print("3. protected incognito: straight to the storage, no question")
    finally:
        http.shutdown()
        to_browser(d)
        set_incognito_protect(d, False)
        set_target(d, "В «Загрузки»")
    d.delete_media(f"vd-b-{stamp}.txt")
    print("OK")


if __name__ == "__main__":
    main()
