"""Secure storage viewer: a video plays in the viewer, stops when the app is left, and closing the viewer returns to
the grid with the search kept.

Usage: python3 -I tools/e2e/vault_viewer.py --serial HOST:PORT

Needs ffmpeg on the host (the test video with sound is made on the fly). Waydroid has no screen lock, so the storage
opens without an unlock prompt.
"""
import argparse
import os
import re
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from nav import open_settings, to_browser  # noqa: E402
from vault import open_downloads, open_vault, row_actions, wait_media  # noqa: E402

PORT = 8787


def video(path):
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=15",
                    "-f", "lavfi", "-i", "sine=frequency=440", "-t", "30", "-pix_fmt", "yuv420p",
                    "-c:v", "libx264", "-c:a", "aac", path], check=True)
    with open(path, "rb") as f:
        return f.read()


def serve(name, body):
    page = f"""<!doctype html><meta name=viewport content="width=device-width">
<body style="margin:0"><a href="/{name}" download style="display:block;height:70vh;font-size:40px">video</a></body>""".encode()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            data, kind = (body, "video/mp4") if self.path.split("?")[0] == f"/{name}" else (page, "text/html")
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


def set_vault_lock(d, label):
    """Settings > Security > Secure storage > lock: the video must keep the storage open after the app is left."""
    to_browser(d)
    open_settings(d)
    d.tap("Безопасность", exact=True)
    d.scroll_to("Защищённое хранилище")
    d.tap("Защищённое хранилище", exact=True)
    d.tap("Блокировать хранилище", exact=True)
    d.tap(label, exact=True)
    time.sleep(0.5)
    to_browser(d)


def playing(d, uid):
    """Whether the app has an audio player in the started state (ExoPlayer's AudioTrack)."""
    out = d.shell("dumpsys audio", check=False)
    return any(f"u/pid:{uid}/" in line and "state:started" in line for line in out.splitlines())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    name = f"vv-{int(time.time())}.mp4"
    body = video(os.path.join(a.out, name))
    uid = re.search(r"userId=(\d+)", d.shell(f"dumpsys package {PACKAGE}")).group(1)
    http = serve(name, body)
    d.adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    try:
        d.stop()
        d.launch(f"http://localhost:{PORT}/page")
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(3)
        # Gecko's accessibility bounds ignore the toolbar offset, so the link is tapped by position.
        d.shell("input tap 540 900")
        wait_media(d, name, len(body), "the video was not downloaded", timeout=40)
        open_downloads(d)
        row_actions(d, name, "В защищённое хранилище")
        wait_media(d, name, None, "the public file stayed after the move into the storage")
        set_vault_lock(d, "Не блокировать")
        print("1. the video is in the storage, the storage is set to stay unlocked")

        open_vault(d)
        field = d.find_class("android.widget.EditText")
        if field is None:
            d.fail("no search field in the storage")
        d.shell("input tap %d %d" % d.center(field))
        d.type("vv-")
        d.shell("input keyevent 111")
        time.sleep(1)
        d.tap(name, exact=True)
        time.sleep(3)
        if not playing(d, uid):
            d.fail("the video does not play in the viewer")
        print("2. the video plays in the viewer")

        d.shell("input keyevent HOME")
        time.sleep(2)
        if playing(d, uid):
            d.fail("the video goes on playing after the app was left")
        print("3. leaving the app pauses it")

        d.shell(f"am start -n {PACKAGE}/.MainActivity")
        time.sleep(2)
        d.shell("input keyevent BACK")
        time.sleep(1.5)
        field = d.find_class("android.widget.EditText")
        if field is None or field.get("text") != "vv-":
            d.fail(f"the search was not kept after closing the viewer: {field.get('text') if field is not None else None!r}")
        d.wait(name, exact=True)
        print("4. back to the grid with the search kept")

        row_actions(d, name, "Удалить")
        d.tap("Удалить", exact=True)
        time.sleep(1)
    finally:
        d.delete_media(name)
        http.shutdown()
        d.shell("input keyevent BACK")
        set_vault_lock(d, "При выходе из браузера")
    print("OK")


if __name__ == "__main__":
    main()
