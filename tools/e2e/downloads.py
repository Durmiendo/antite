"""Downloads: progress notification, keeps going in the background, "done" notification, cancel from the notification.

Usage: ./gradlew :engine-gecko:adapterPackage :app:assembleDebug
       python3 -I tools/e2e/downloads.py --serial SERIAL --apk app/build/outputs/apk/debug/app-debug.apk

A local server sends files slowly so progress can be seen. Reinstalls the browser, so its data is lost.
"""
import argparse
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from engine_setup import download_engine, engine_pages  # noqa: E402

PORT = 8775
SIZE = 12 * 1024 * 1024
RATE = 512 * 1024
ENGINE_RATE = 16 * 1024 * 1024


def serve(pages):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            path = self.path.split("?")[0]
            if path.endswith(".bin"):
                name = path.rsplit("/", 1)[1]
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Disposition", f'attachment; filename="{name}"')
                self.send_header("Content-Length", str(SIZE))
                self.end_headers()
                chunk = bytes(64 * 1024)
                try:
                    for _ in range(SIZE // len(chunk)):
                        self.wfile.write(chunk)
                        time.sleep(len(chunk) / (RATE // 8 if name.startswith("slow") else RATE))
                except (BrokenPipeError, ConnectionResetError):
                    pass
                return
            body = pages.get(path, b"")
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            # The engine comes slowly enough for its progress notification to be seen.
            step = 1024 * 1024
            for i in range(0, len(body), step):
                self.wfile.write(body[i:i + step])
                if len(body) > step:
                    time.sleep(step / ENGINE_RATE)

        def log_message(self, *args):
            pass

    http = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=http.serve_forever, daemon=True).start()
    return http


def notifications(d):
    return d.shell("dumpsys notification --noredact", check=False)


def media_size(d, name):
    out = d.shell(f"content query --uri content://media/external/downloads --projection _display_name:_size "
                  f"--where \"_display_name='{name}'\"", check=False)
    return int(out.split("_size=")[1].split()[0].rstrip(",")) if "_size=" in out else None


def wait(d, what, check, timeout):
    end = time.time() + timeout
    while not check():
        if time.time() > end:
            d.fail(what)
        time.sleep(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    http = serve(engine_pages(PORT))
    d.adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    stamp = int(time.time())
    first, second = f"first-{stamp}.bin", f"slow-{stamp}.bin"

    print("1. clean install with an engine")
    d.shell("cmd statusbar collapse")
    d.adb("uninstall", PACKAGE, check=False)
    d.install(a.apk)
    download_engine(d, PORT)

    print("2. a download shows progress (notifications were allowed on the engine setup screen)")
    d.launch(f"http://localhost:{PORT}/{first}")
    wait(d, "no progress notification", lambda: first in notifications(d) and "из" in notifications(d), 30)
    wait(d, "no speed, time left or percent in the notification",
         lambda: all(x in notifications(d) for x in ("/с", "осталось", "%")), 30)

    print("3. it goes on in the background and ends with a 'done' notification")
    d.shell("input keyevent HOME")
    wait(d, "download did not finish", lambda: media_size(d, first) == SIZE, 120)
    wait(d, "no 'done' notification", lambda: "Скачано" in notifications(d), 20)

    print("4. cancel from the notification")
    d.shell("cmd statusbar expand-notifications")
    d.tap("Очистить все", exact=True)
    d.shell("cmd statusbar collapse")
    d.launch(f"http://localhost:{PORT}/{second}")
    wait(d, "second download did not start", lambda: second in notifications(d), 30)
    time.sleep(2)
    d.shell("cmd statusbar expand-notifications")
    time.sleep(2)
    with open(os.path.join(a.out, "download-notification.png"), "wb") as f:
        f.write(d.adb("exec-out", "screencap", "-p", binary=True))
    # The progress notification is redrawn twice a second, so the shade is searched until the tap lands.
    for _ in range(20):
        if d.find("Отмена", exact=True) is not None:
            d.tap("Отмена", exact=True)
            break
        time.sleep(1)
    else:
        d.fail("no cancel button in the notification")
    d.shell("cmd statusbar collapse")
    wait(d, "cancelled download is still in the notifications", lambda: second not in notifications(d), 20)
    if media_size(d, second) is not None:
        d.fail("cancelled file was left in Downloads")

    print("5. the downloads list shows both")
    d.launch()
    d.tap("Меню", exact=True)
    d.scroll_to("Скачанные файлы")
    d.tap("Скачанные файлы", exact=True)
    d.wait("Отменено")
    d.wait("Скачано")
    if not d.shell(f"pidof {PACKAGE}", check=False).strip():
        d.fail("browser died")
    http.shutdown()
    print("OK")


if __name__ == "__main__":
    main()
