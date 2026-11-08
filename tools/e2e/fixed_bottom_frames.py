"""Frame by frame while scrolling hides and shows the toolbar and the navigation bar: a page's fixed bottom bar must
sit right on the navigation bar (or the screen bottom) on every frame, not float above it while the bar moves.

Usage: python3 -I tools/e2e/fixed_bottom_frames.py --serial HOST:PORT [--max-gap PX]

Prints, per frame, the gap in screen pixels between the fixed bar and what is under it. Records raw frames like
keyboard_frames.py.
"""
import argparse
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from keyboard_frames import frames  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8778
SCALE = 4
PAGE = b"""<!doctype html><meta name="viewport" content="width=device-width">
<style>body{margin:0;background:repeating-linear-gradient(#fff 0 40px,#ddd 40px 80px)}
#bar{position:fixed;left:0;right:0;bottom:0;height:50px;background:#f00}</style>
<div style="height:8000px"></div><div id="bar"></div>"""


def is_page(c):
    return min(c) > 200 or (c[0] > 200 and c[1] < 60 and c[2] < 60)


def is_red(c):
    return c[0] > 200 and c[1] < 60 and c[2] < 60


def measure(img):
    px = img.load()
    w, h = img.size
    x = w // 2
    red = [y for y in range(h) if is_red(px[x, y])]
    if not red:
        return None
    bottom = max(red)
    below = h - 1 - bottom
    under = sum(1 for y in range(bottom + 1, h) if not is_page(px[x, y]))
    return bottom, below, below - under


def record(d, path, actions):
    with open(path, "wb") as raw:
        rec = subprocess.Popen(["adb", "-s", d.serial, "exec-out",
                                f"screenrecord --output-format=frames --size {1080 // SCALE}x{2400 // SCALE} -"],
                               stdout=raw)
        time.sleep(1)
        actions()
        d.shell("pkill -INT screenrecord", check=False)
        rec.wait(timeout=20)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    ap.add_argument("--max-gap", type=int, default=None)
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    server = ReportServer(PORT, {"/fixed": PAGE})
    server.attach(d)
    d.stop()
    d.launch(f"http://localhost:{PORT}/fixed")
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(3)

    def scroll(y1, y2):
        def go():
            for _ in range(2):
                d.shell(f"input swipe 540 {y1} 540 {y2} 300")
                time.sleep(1)
            time.sleep(1.5)
        return go

    worst = 0
    for name, act in (("hide", scroll(1700, 900)), ("show", scroll(900, 1700))):
        path = os.path.join(a.out, f"fixed_bottom_{name}.bin")
        record(d, path, act)
        rows = [measure(img) for img in frames(path)]
        gaps = [r[2] * SCALE if r else None for r in rows]
        bad = [(i, g) for i, g in enumerate(gaps) if g is None or g > SCALE]
        print(f"{name}: {len(rows)} frames, gap px per frame: {gaps}")
        print(f"   frames with the bar off its place: {len(bad)}")
        worst = max([worst] + [g for _, g in bad if g is not None])
    server.close()
    print(f"worst gap {worst} px")
    if a.max_gap is not None and worst > a.max_gap:
        d.fail(f"fixed bar floats up to {worst} px above what is under it")
    print("OK")


if __name__ == "__main__":
    main()
