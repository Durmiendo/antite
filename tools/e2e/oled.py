"""Black background: with the switch on, the dark theme paints the settings page and the toolbar pure black; with it
off they are dark grey again. The setting goes back to off at the end.

Usage: python3 -I tools/e2e/oled.py --serial SERIAL
"""
import argparse
import io
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image  # noqa: E402
from device import Device  # noqa: E402
from nav import back_to_page, open_settings_page  # noqa: E402

SWITCH = "Чёрный фон"


def shot(d):
    return Image.open(io.BytesIO(d.adb("exec-out", "screencap", "-p", binary=True))).convert("RGB")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    d.stop()
    d.launch("about:blank")
    d.wait("Меню", timeout=30, exact=True)
    menu = d.center(d.wait("Меню", exact=True))

    seen = {}
    for state in ("on", "off"):
        open_settings_page(d, "Внешний вид")
        d.tap(SWITCH, exact=True)
        time.sleep(0.8)
        # The left edge of the list, below the last row: nothing but the page background is drawn there.
        page = shot(d).getpixel((6, 2000))
        back_to_page(d, 2)
        time.sleep(0.8)
        # Above the menu button: the top edge of the toolbar or the status bar, both in the toolbar color.
        toolbar = shot(d).getpixel((menu[0], menu[1] - 60))
        seen[state] = (page, toolbar)
        print(f"  {state}: settings page {page}, toolbar {toolbar}")

    if seen["on"] != ((0, 0, 0), (0, 0, 0)):
        d.fail(f"black background on, but the colors are {seen['on']}")
    if (0, 0, 0) in seen["off"]:
        d.fail(f"black background off, but something is still black: {seen['off']}")
    print("OK")


if __name__ == "__main__":
    main()
