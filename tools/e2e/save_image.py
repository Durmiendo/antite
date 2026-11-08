"""Context menu "Скачать изображение" saves the image as a download, sent with the page as referrer (hotlink-protected
images answer 403 without it), and the tab stays on the page.

Usage: python3 -I tools/e2e/save_image.py --serial HOST:PORT
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
from downloads import media_size  # noqa: E402

PORT = 8780


def png():
    b = io.BytesIO()
    Image.new("RGB", (64, 64), (30, 160, 90)).save(b, "PNG")
    return b.getvalue()


def serve(name, image, seen):
    page = f"""<!doctype html><meta name=viewport content="width=device-width">
<body style="margin:0"><img src="/{name}" style="display:block;width:100%;height:70vh"></body>""".encode()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            path = self.path.split("?")[0]
            if path == f"/{name}":
                ref = self.headers.get("Referer") or ""
                seen.append(ref)
                # Like hotlink protection on real sites: the referrer's origin must be the site's.
                if not ref.startswith(f"http://localhost:{PORT}/"):
                    self.send_response(403)
                    self.send_header("Content-Length", "9")
                    self.end_headers()
                    self.wfile.write(b"forbidden")
                    return
                body, kind = image, "image/png"
            else:
                body, kind = page, "text/html"
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
    name = f"pic-{int(time.time())}.png"
    image = png()
    seen = []
    http = serve(name, image, seen)
    d.adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    try:
        d.stop()
        d.launch(f"http://localhost:{PORT}/page")
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(3)
        print("1. long press on the image -> Скачать изображение")
        # Gecko's accessibility bounds ignore the toolbar offset, so the image is pressed by position.
        d.shell("input swipe 540 900 540 900 900")
        d.tap("Скачать изображение", exact=True)
        end = time.time() + 20
        while media_size(d, name) != len(image):
            if time.time() > end:
                d.fail(f"image not saved; referrers the server saw: {seen}")
            time.sleep(1)
        print(f"   saved {len(image)} bytes, referrers seen: {seen}")
        print("2. the tab stays on the page")
        if d.find("forbidden") is not None or d.find(name) is not None:
            d.fail("the tab navigated to the image")
    finally:
        d.delete_media(name)
        http.shutdown()
    print("OK")


if __name__ == "__main__":
    main()
