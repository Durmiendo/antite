"""Frame by frame while the keyboard comes and goes: the page must not show through the navigation bar on any
frame (it did for ~9 frames at the end of hiding), and the top of the page must not move.

Usage: python3 -I tools/e2e/keyboard_frames.py --serial HOST:PORT

Records raw frames with screenrecord --output-format=frames (Waydroid has no video encoder); a frame comes
only when the screen changes, so every recorded frame is one shown frame.
"""
import argparse
import os
import struct
import subprocess
import sys
import time

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8777
PAGE = b"""<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<body style="margin:0;background:#fff">
<div style="position:absolute;top:20px;left:0;width:40px;height:10px;background:#00f"></div>
<input style="position:absolute;top:100px;left:15%;width:80%;height:60px;font-size:20px">
<div style="height:3000px"></div>
<div style="position:fixed;left:0;right:0;bottom:0;height:30px;background:#f00"></div>"""


def near(c, ref):
    return all(abs(a - b) < 60 for a, b in zip(c, ref))


def frames(path):
    data = open(path, "rb").read()
    pos = 0
    while pos + 20 <= len(data):
        _, w, h, stride, _ = struct.unpack("<5I", data[pos:pos + 20])
        body = data[pos + 20:pos + 20 + stride * h]
        pos += 20 + stride * h
        if len(body) < stride * h:
            return
        yield Image.frombytes("RGB", (w, h), body, "raw", "RGB", stride)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    server = ReportServer(PORT, {"/kb": PAGE})
    server.attach(d)
    d.stop()
    d.launch(f"http://localhost:{PORT}/kb")
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(3)
    raw_path = os.path.join(a.out, "keyboard_frames.bin")
    with open(raw_path, "wb") as raw:
        rec = subprocess.Popen(["adb", "-s", a.serial, "exec-out",
                                "screenrecord --output-format=frames --size 270x600 -"], stdout=raw)
        time.sleep(1)
        d.shell("input tap 540 550")
        time.sleep(2.5)
        d.shell("input keyevent BACK")
        time.sleep(2.5)
        # screenrecord does not stop by --time-limit in this mode.
        d.shell("pkill -INT screenrecord", check=False)
        rec.wait(timeout=20)
    server.close()

    tops, see_through, n = set(), [], 0
    for i, img in enumerate(frames(raw_path)):
        px = img.load()
        w, h = img.size
        tops.add(next((y for y in range(h // 2) if near(px[2, y], (0, 0, 255))), None))
        if sum(1 for y in range(h - 30, h - 2) if sum(px[6, y]) > 600) > 3:
            see_through.append(i)
        n = i + 1
    print(f"{n} frames, page top positions {sorted(t for t in tops if t is not None)}, see-through frames {see_through}")
    if n < 20:
        d.fail("too few frames recorded, the keyboard probably did not show")
    if see_through:
        d.fail(f"page shows through the navigation bar on frames {see_through}")
    if len(tops - {None}) > 1:
        d.fail(f"page top moved: {sorted(t for t in tops if t is not None)}")
    print("OK")


if __name__ == "__main__":
    main()
