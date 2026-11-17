"""Going between settings pages, the page under the settings never shows: a red page is open behind them, and no
recorded frame of a forward and a back transition has red in it.

Usage: python3 -I tools/e2e/settings_ground.py --serial HOST:PORT

Needs the "Как в Chrome" animation style (the default): without movement there is no transition to look at.
Records raw frames with screenrecord --output-format=frames.
"""
import argparse
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from frames import frames  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8791
RED = b"<!doctype html><meta name=viewport content='width=device-width'><body style='margin:0;background:#f00;height:100vh'>"


def red_share(img):
    px = img.getdata()
    red = sum(1 for r, g, b in px if r > 200 and g < 80 and b < 80)
    return red / len(px)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    os.makedirs(a.out, exist_ok=True)
    server = ReportServer(PORT, {"/red": RED})
    server.attach(d)
    try:
        d.stop()
        d.launch(f"http://localhost:{PORT}/red")
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(2)
        d.tap("Меню", exact=True)
        d.scroll_to("Настройки")
        d.tap("Настройки", exact=True)
        d.wait("Поиск по настройкам", exact=True)
        time.sleep(1.5)
        row = d.center(d.wait("Внешний вид", exact=True))

        print("1. forward to a page and back, recorded")
        raw_path = os.path.join(a.out, "settings_ground.bin")
        with open(raw_path, "wb") as raw:
            rec = subprocess.Popen(["adb", "-s", a.serial, "exec-out",
                                    "screenrecord --output-format=frames --size 270x600 -"], stdout=raw)
            time.sleep(1.5)
            d.shell(f"input tap {row[0]} {row[1]}")
            time.sleep(1.5)
            d.shell("input keyevent BACK")
            time.sleep(1.5)
            d.shell("pkill -INT screenrecord", check=False)
            rec.wait(timeout=20)
        shares = [red_share(img) for img in frames(raw_path)]
        worst = max(shares, default=0)
        print(f"   {len(shares)} frames, most red: {worst:.1%}")
        if len(shares) < 5:
            d.fail(f"too few frames recorded: {len(shares)}")
        if worst > 0.01:
            d.fail(f"the red page shows through the settings: {worst:.1%} of a frame")
        d.shell("input keyevent BACK")
        time.sleep(1)
    finally:
        server.close()
    print("OK")


if __name__ == "__main__":
    main()
