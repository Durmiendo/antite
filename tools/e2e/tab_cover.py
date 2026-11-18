"""Switching tabs never shows the page of the tab left behind. Reported 18.11.2026: for a moment the previous
site stayed on screen before the new one, a site of a category under a password too.

Usage: python3 -I tools/e2e/tab_cover.py --serial HOST:PORT

Two tabs with full-screen pages, blue and red; while the blue one is shown the screen is recorded frame by frame, the
switcher opens and the red tab is picked. From the first frame that is not the blue page (the switcher) on, no frame
may be the blue page again.
"""
import argparse
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from frames import frames  # noqa: E402
from nav import open_switcher, to_browser  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8781
BLUE = (0x10, 0x30, 0xe0)
RED = (0xe0, 0x20, 0x20)


def page(title, rgb):
    colour = "#%02x%02x%02x" % rgb
    return (f"<!doctype html><meta name=viewport content='width=device-width'><title>{title}</title>"
            f"<body style='margin:0;background:{colour};height:300vh'>").encode()


def share(img, rgb):
    """The part of a grid of points over the page area that shows this colour."""
    w, h = img.size
    pts = [(int(w * x / 10), int(h * y / 10)) for x in range(1, 10) for y in range(3, 9)]
    hit = sum(1 for p in pts if max(abs(a - b) for a, b in zip(img.getpixel(p), rgb)) < 24)
    return hit / len(pts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    server = ReportServer(PORT, {"/blue": page("BluePage", BLUE), "/red": page("RedPage", RED)})
    server.attach(d)
    try:
        print("1. a red tab and a blue tab")
        d.launch(f"http://localhost:{PORT}/red")
        time.sleep(3)
        d.launch(f"http://localhost:{PORT}/blue")
        time.sleep(3)
        to_browser(d)

        print("2. from the blue tab to the red one, recorded")
        raw_path = os.path.join(a.out, "tab_cover_frames.bin")
        with open(raw_path, "wb") as raw:
            rec = subprocess.Popen(["adb", "-s", a.serial, "exec-out",
                                    "screenrecord --output-format=frames --size 270x600 -"], stdout=raw)
            time.sleep(1.5)
            open_switcher(d)
            d.tap("RedPage")
            time.sleep(3)
            rec.terminate()
            rec.wait()
        seen = [(share(f, BLUE), share(f, RED)) for f in frames(raw_path)]
        first_other = next((i for i, (b, _) in enumerate(seen) if b < 0.9), None)
        if first_other is None:
            d.fail("the switcher never showed in the recording")
        if not any(r > 0.9 for _, r in seen[first_other:]):
            d.fail("the red tab never showed in the recording")
        back = [i for i, (b, _) in enumerate(seen) if i > first_other and b > 0.9]
        print(f"   {len(seen)} frames, the switcher from frame {first_other}")
        if back:
            d.fail(f"the blue page showed again after the switch: frames {back}")
    finally:
        server.close()
    print("OK")


if __name__ == "__main__":
    main()
