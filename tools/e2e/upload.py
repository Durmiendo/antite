"""A file picked for <input type=file> reaches the site: the page posts it and the server compares the bytes. First
from the device through the system picker, then from the secure storage.

Usage: python3 -I tools/e2e/upload.py --serial HOST:PORT --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"""
import argparse
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from nav import to_browser  # noqa: E402
from vault import open_vault, reset, file_action  # noqa: E402

PORT = 8781

PAGE = b"""<!doctype html><meta name=viewport content="width=device-width">
<body style="margin:0">
<input id=f type=file style="display:block;width:100vw;height:60vh;font-size:40px">
<p id=r style="font-size:30px">waiting</p>
<script>
f.onchange = async () => {
  const file = f.files[0];
  if (!file) { r.textContent = 'no file'; return; }
  try {
    const res = await fetch('/upload', {method: 'POST', body: await file.arrayBuffer()});
    r.textContent = 'sent ' + file.size + ' ' + res.status;
  } catch (e) { r.textContent = 'error ' + e; }
};
</script></body>"""


def serve(received):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(PAGE)))
            self.end_headers()
            self.wfile.write(PAGE)

        def do_POST(self):
            received.append(self.rfile.read(int(self.headers.get("Content-Length") or 0)))
            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def log_message(self, *args):
            pass

    http = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=http.serve_forever, daemon=True).start()
    return http


def pick(d, name):
    """Picks the file in the system picker: by name if listed, else through the Downloads root."""
    for _ in range(2):
        if d.find(name) is not None:
            d.tap(name)
            return
        root = d.find("Downloads") or d.find("Загрузки")
        if root is None:
            d.tap("Show roots")
            root = d.wait("Downloads")
        d.shell("input tap %d %d" % d.center(root))
        time.sleep(1.5)
    d.tap(name)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    ap.add_argument("--test-apk", required=True)
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    name = f"up-{int(time.time())}.txt"
    content = os.urandom(3000).hex().encode()
    local = os.path.join(a.out, name)
    with open(local, "wb") as f:
        f.write(content)
    d.adb("push", local, f"/sdcard/Download/{name}")
    d.shell(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/{name}", check=False)
    received = []
    http = serve(received)
    d.adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    try:
        d.stop()
        d.launch()
        d.wait("Меню", timeout=30, exact=True)
        reset(d)
        d.launch(f"http://localhost:{PORT}/page")
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(3)
        print("1. tap the file input, pick the file in the system picker")
        # Gecko's accessibility bounds ignore the toolbar offset, so the input is tapped by position.
        d.shell("input tap 540 700")
        time.sleep(1.5)
        d.tap("Файлы устройства", exact=True)
        time.sleep(2.5)
        if "documentsui" not in d.top_activity():
            d.fail("system file picker did not open")
        pick(d, name)
        end = time.time() + 15
        while not received and time.time() < end:
            time.sleep(0.5)
        if not received:
            d.fail("nothing was uploaded")
        if received[0] != content:
            d.fail(f"uploaded {len(received[0])} bytes, expected {len(content)} bytes of the file")
        print(f"   {len(content)} bytes uploaded unchanged")

        print("2. the same field takes a file from the secure storage")
        secret = f"uv-{int(time.time()) % 100000}.txt"
        d.install(a.test_apk)
        # The test APK's provider serves the name itself as the file's content.
        d.shell(f"am start -a android.intent.action.SEND -t text/plain "
                f"--eu android.intent.extra.STREAM content://{PACKAGE}.test.share/{secret} -n {PACKAGE}/.ShareToVault")
        time.sleep(4)
        received.clear()
        d.launch(f"http://localhost:{PORT}/page")
        time.sleep(3)
        d.shell("input tap 540 700")
        time.sleep(1.5)
        d.tap("Защищённое хранилище", exact=True)
        d.tap(secret, exact=True, timeout=20)
        end = time.time() + 15
        while not received and time.time() < end:
            time.sleep(0.5)
        if received[:1] != [secret.encode()]:
            d.fail(f"the storage file did not reach the site: {received[:1]!r}")
        open_vault(d)
        file_action(d, secret, "Удалить")
        d.tap("Удалить", exact=True)
        to_browser(d)
        print("OK")
    finally:
        http.shutdown()
        d.shell(f"rm -f /sdcard/Download/{name}", check=False)
        d.delete_media(name)


if __name__ == "__main__":
    main()
