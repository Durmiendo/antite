"""Downloads started the way sites start them: tapping a link to an attachment, a link with the download attribute and
no Content-Disposition, a blob: link made by a script, and an attachment link with target=_blank.

Usage: ./gradlew :engine-gecko:adapterPackage :app:assembleDebug
       python3 -I tools/e2e/download_links.py --serial SERIAL [--apk app/build/outputs/apk/debug/app-debug.apk]

With --apk the browser is reinstalled (its data is lost) and the engine is installed from a local source.
"""
import argparse
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from downloads import media_size  # noqa: E402
from engine_setup import download_engine, engine_pages  # noqa: E402

PORT = 8781
BODY = b"antite download test\n" * 1000

CASES = [
    ("attachment", lambda s: f'<a href="/{s}-attachment.bin">attachment</a>'),
    ("download-attr", lambda s: f'<a href="/{s}-download-attr.txt" download>download attribute</a>'),
    ("blob", lambda s: f'<a id=blob download="{s}-blob.txt" href="#">blob</a>'
                       '<script>blob.href = URL.createObjectURL(new Blob(["antite blob test"], {type: "text/plain"}))</script>'),
    ("new-tab", lambda s: f'<a href="/{s}-new-tab.bin" target=_blank>new tab</a>'),
]


def page(stamp, link):
    return f"""<!doctype html><meta name=viewport content="width=device-width">
<style>body{{margin:0}} a{{display:block;height:100vh;font-size:40px}}</style>
<body>{link(stamp)}</body>""".encode()


def serve(stamp, extra):
    pages = dict(extra)
    for name, link in CASES:
        pages[f"/{name}"] = page(stamp, link)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            path = self.path.split("?")[0]
            headers = {}
            if path.endswith(".bin"):
                body = BODY
                headers["Content-Type"] = "application/octet-stream"
                headers["Content-Disposition"] = f'attachment; filename="{path[1:]}"'
            elif path.endswith(".txt"):
                body = BODY
                headers["Content-Type"] = "text/plain"
            else:
                body = pages.get(path, b"")
                headers["Content-Type"] = "text/html"
            self.send_response(200)
            for k, v in headers.items():
                self.send_header(k, v)
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
    ap.add_argument("--apk")
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    stamp = f"dl{int(time.time())}"
    http = serve(stamp, engine_pages(PORT) if a.apk else {})
    d.adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    if a.apk:
        d.adb("uninstall", PACKAGE, check=False)
        d.install(a.apk)
        download_engine(d, PORT)
    # The notification permission request would cover the page and take the tap.
    d.shell(f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS", check=False)
    w, h = (int(x) for x in d.shell("wm size").split()[-1].split("x"))
    failed = []
    for name, _ in CASES:
        print(f"- {name}")
        d.stop()
        d.launch(f"http://localhost:{PORT}/{name}")
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(3)
        # The link fills the page; Gecko's accessibility bounds ignore the toolbar, so it is tapped by position.
        d.shell(f"input tap {w // 2} {h // 2}")
        file = f"{stamp}-{name}." + ("bin" if name in ("attachment", "new-tab") else "txt")
        end = time.time() + 20
        while time.time() < end and media_size(d, file) is None:
            time.sleep(1)
        size = media_size(d, file)
        alive = bool(d.shell(f"pidof {PACKAGE}", check=False).strip())
        print(f"  file={size} alive={alive}")
        if size is None or not alive:
            failed.append(name)
            path = os.path.join(a.out, f"download-{name}.png")
            with open(path, "wb") as f:
                f.write(d.adb("exec-out", "screencap", "-p", binary=True))
            print(f"  screenshot: {path}")
    http.shutdown()
    if failed:
        print("FAIL: " + ", ".join(failed))
        sys.exit(1)
    print("OK")


if __name__ == "__main__":
    main()
