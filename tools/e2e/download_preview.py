"""Download previews: an image in the downloads list shows its thumbnail; "Don't show" shows none; "Blurred" shows it
blurred until tapped. A checkerboard image tells them apart by contrast. The block layout shows the newest image as a
card with its site and size. The settings go back to "Show" and the list layout at the end.

Usage: python3 -I tools/e2e/download_preview.py --serial SERIAL
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
from device import PACKAGE, Device  # noqa: E402
from downloads import media_size  # noqa: E402

PORT = 8783


def checker():
    img = Image.new("RGB", (256, 256))
    px = img.load()
    for y in range(256):
        for x in range(256):
            px[x, y] = (255, 255, 255) if (x // 16 + y // 16) % 2 else (0, 0, 0)
    b = io.BytesIO()
    img.save(b, "PNG")
    return b.getvalue()


def serve(name, body):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Disposition", f'attachment; filename="{name}"')
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    http = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=http.serve_forever, daemon=True).start()
    return http


def thumb_contrast(d, name):
    """Spread of brightness in the thumbnail left of the file name in the downloads list."""
    node = d.wait(name, exact=True)
    x1, y1, x2, y2 = d.bounds(node)
    img = Image.open(io.BytesIO(d.adb("exec-out", "screencap", "-p", binary=True))).convert("L")
    density = int(d.shell("wm density").split()[-1]) / 160
    # The middle of the 40 dp thumbnail only: blur pulls the dark background in at the edges.
    cx, cy = x1 - 36 * density, (y1 + y2) / 2
    box = img.crop((int(cx - 8 * density), int(cy - 8 * density), int(cx + 8 * density), int(cy + 8 * density)))
    values = sorted(box.get_flattened_data())
    return values[int(len(values) * 0.95)] - values[int(len(values) * 0.05)]


def set_preview(d, label):
    set_option(d, "Превью картинок и видео", label)


def set_option(d, row, label):
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    # A tap while the menu still flings only stops the fling.
    time.sleep(1)
    d.tap("Настройки", exact=True)
    d.wait("Поиск по настройкам", exact=True)
    time.sleep(1)
    d.scroll_to("Скачанные файлы")
    d.tap("Скачанные файлы", exact=True)
    # Pages slide in for 366 ms; a tap during the slide misses.
    time.sleep(1)
    d.tap(row, exact=True)
    time.sleep(1)
    d.tap(label, exact=True)
    for _ in range(3):
        d.shell("input keyevent BACK")
        time.sleep(1)
    d.wait("Меню", exact=True)


def open_downloads(d):
    d.tap("Меню", exact=True)
    d.scroll_to("Скачанные файлы")
    time.sleep(1)
    d.tap("Скачанные файлы", exact=True)
    time.sleep(2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    name = f"checker-{int(time.time())}.png"
    http = serve(name, checker())
    d.adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    d.shell(f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS", check=False)
    d.stop()
    d.launch(f"http://localhost:{PORT}/{name}")
    end = time.time() + 30
    while media_size(d, name) is None:
        if time.time() > end:
            d.fail("the image was not downloaded")
        time.sleep(1)
    d.stop()
    d.launch()
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(1)

    set_option(d, "Вид списка загрузок", "Списком")
    result = {}
    for mode, label in (("show", "Показывать"), ("none", "Не показывать"), ("blur", "Под цензурой (нажмите, чтобы открыть)")):
        set_preview(d, label)
        open_downloads(d)
        result[mode] = thumb_contrast(d, name)
        if mode == "blur":
            node = d.wait(name, exact=True)
            x1, y1, x2, y2 = d.bounds(node)
            density = int(d.shell("wm density").split()[-1]) / 160
            d.shell(f"input tap {int(x1 - 36 * density)} {(y1 + y2) // 2}")
            time.sleep(1)
            result["revealed"] = thumb_contrast(d, name)
        d.shell("input keyevent BACK")
        time.sleep(1)
    set_preview(d, "Показывать")

    set_option(d, "Вид списка загрузок", "Блоками")
    open_downloads(d)
    d.wait(name, exact=True)
    card = [n.get("text") for n in d.nodes() if "localhost" in (n.get("text") or "")]
    d.shell("input keyevent BACK")
    time.sleep(1)
    set_option(d, "Вид списка загрузок", "Списком")
    print("card details:", card)
    if not card or "•" not in card[0]:
        d.fail("the block layout does not show the site and size of the new download")
    http.shutdown()
    print(result)
    if not (result["show"] > 150 and result["revealed"] > 150 and result["blur"] < 120 and result["none"] < 150):
        d.fail(f"thumbnails do not follow the setting: {result}")
    print("OK")


if __name__ == "__main__":
    main()
