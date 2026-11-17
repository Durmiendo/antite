"""Secure storage: a downloaded picture moves in (the public file and its downloads row go away), shows in the
storage, comes back to downloads on restore, and is deleted for good on delete.

Usage: python3 -I tools/e2e/media_vault.py --serial HOST:PORT

Waydroid has no screen lock, so the storage opens without an unlock prompt.
"""
import argparse
import io
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from nav import to_browser  # noqa: E402
from vault import media_size, open_downloads, open_vault, row_actions, wait_media, file_action  # noqa: E402

PORT = 8786


def png():
    b = io.BytesIO()
    Image.new("RGB", (64, 64), (200, 90, 40)).save(b, "PNG")
    return b.getvalue()


def serve(name, image):
    page = f"""<!doctype html><meta name=viewport content="width=device-width">
<body style="margin:0"><img src="/{name}" style="display:block;width:100%;height:70vh"></body>""".encode()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            body, kind = (image, "image/png") if self.path.split("?")[0] == f"/{name}" else (page, "text/html")
            self.send_response(200)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    http = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=http.serve_forever, daemon=True).start()
    return http


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    name = f"vault-{int(time.time())}.png"
    image = png()
    http = serve(name, image)
    d.adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    try:
        d.stop()
        d.launch(f"http://localhost:{PORT}/page")
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(3)
        d.shell("input swipe 540 900 540 900 900")
        d.tap("Скачать изображение", exact=True)
        wait_media(d, name, len(image), "the image was not downloaded")
        print("1. the picture is downloaded")

        open_downloads(d)
        row_actions(d, name, "В защищённое хранилище")
        wait_media(d, name, None, "the public file stayed after the move into the storage")
        time.sleep(1)
        if d.find(name, exact=True) is not None:
            d.fail("the downloads row stayed after the move into the storage")
        print("2. moved in: the public file and the downloads row are gone")

        open_vault(d)
        d.wait(name, exact=True)
        print("3. the storage shows the picture")

        file_action(d, name, "Вернуть в загрузки")
        wait_media(d, name, len(image), "the file did not come back to downloads")
        time.sleep(1)
        if d.find(name, exact=True) is not None:
            d.fail("the restored file is still shown in the storage")
        open_downloads(d)
        d.wait(name, exact=True)
        print("4. restored: the file and the downloads row are back, the storage no longer has it")

        row_actions(d, name, "В защищённое хранилище")
        wait_media(d, name, None, "the public file stayed after the second move")
        open_vault(d)
        file_action(d, name, "Удалить")
        d.tap("Удалить", exact=True)
        time.sleep(1)
        if d.find(name, exact=True) is not None or media_size(d, name) is not None:
            d.fail("the deleted file is still somewhere")
        print("5. deleted for good")
    finally:
        d.delete_media(name)
        http.shutdown()
    to_browser(d)
    print("OK")


if __name__ == "__main__":
    main()
