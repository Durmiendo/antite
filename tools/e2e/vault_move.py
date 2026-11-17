"""Secure storage: a file moved from the top into a folder through its long-press menu leaves the top at once.
Reported 18.11.2026: now and then the moved file stayed on the top, could not be opened there, and went away only
after leaving the screen.

Usage: python3 -I tools/e2e/vault_move.py --serial HOST:PORT --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk [--rounds 5]

Four files, all on one screen, go into the folder and back, round after round: the fault came now and then. Each
picture has a colour of its own (ShareSource), and every tile on the top must show the colour of the name under it:
the stuck file was a picture left over a tile that now held another file.

Leaves no file or folder behind.
"""
import argparse
import os
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from vault import open_vault, row_actions  # noqa: E402

FOLDER = "Moved"


def colour(name):
    """The colour ShareSource paints a picture of this name with: Java's String.hashCode, lowest 24 bits."""
    h = 0
    for ch in name:
        h = (31 * h + ord(ch)) & 0xffffffff
    return (h >> 16) & 0xff, (h >> 8) & 0xff, h & 0xff


def screen(d):
    raw = d.adb("exec-out", "screencap", binary=True)
    w, h = struct.unpack_from("<II", raw, 0)
    head = len(raw) - w * h * 4
    return w, raw[head:]


def wrong_pictures(d, names):
    """Names on screen whose tile above them shows a picture of another file."""
    w, px = screen(d)
    wrong = []
    for node in d.nodes():
        n = node.get("text")
        if n not in names:
            continue
        x1, y1, x2, y2 = d.bounds(node)
        x, y = x1 + 60, y1 - 120
        r, g, b = px[(y * w + x) * 4:(y * w + x) * 4 + 3]
        if max(abs(r - e) for r, e in zip((r, g, b), colour(n))) > 8:
            wrong.append(n)
    return wrong


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    ap.add_argument("--test-apk", required=True)
    ap.add_argument("--rounds", type=int, default=5)
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    stamp = int(time.time()) % 100000
    names = [f"vm-{stamp}-{i}.png" for i in range(4)]
    d.install(a.test_apk)
    stuck = []
    try:
        d.stop()
        d.launch()
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(2)

        print(f"1. {len(names)} pictures in the storage and a folder")
        for n in names:
            d.shell(f"am start -a android.intent.action.SEND -t image/png "
                    f"--eu android.intent.extra.STREAM content://{PACKAGE}.test.share/{n} -n {PACKAGE}/.ShareToVault")
            time.sleep(1.5)
        time.sleep(2)
        open_vault(d)
        d.tap("Новая папка", exact=True)
        d.type(FOLDER)
        d.tap("Сохранить", exact=True)
        time.sleep(1)
        d.shell("input keyevent 4")
        time.sleep(1)
        for n in names:
            d.wait(n, timeout=20, exact=True)

        print(f"2. {a.rounds} rounds: each file moved into the folder leaves the top, and back")
        # Newest first, two to a row: moving the second of the first row each time makes the next file slide into
        # its place, which is when the old picture stayed.
        order = [names[2], names[1], names[0], names[3]]
        for r in range(a.rounds):
            for n in order:
                row_actions(d, n, "В папку…")
                d.tap(FOLDER, exact=True)
                time.sleep(1.5)
                if d.find(n, exact=True) is not None:
                    stuck.append(f"round {r}: {n}")
                    print(f"   still on the top: round {r} {n}")
                for w in wrong_pictures(d, names):
                    stuck.append(f"round {r}: picture of another file over {w}")
                    print(f"   picture of another file over {w}: round {r} after moving {n}")
            d.tap(FOLDER, exact=True)
            time.sleep(1)
            for n in order:
                row_actions(d, n, "В папку…")
                d.tap("В корень хранилища", exact=True)
                time.sleep(1.5)
                if d.find(n, exact=True) is not None:
                    stuck.append(f"round {r} back: {n}")
                    print(f"   still in the folder: round {r} {n}")
            d.shell("input keyevent 4")
            time.sleep(1)
            for n in names:
                d.wait(n, timeout=10, exact=True)
        if stuck:
            d.fail(f"{len(stuck)} of {a.rounds * len(names) * 2} moves left the file on screen: {stuck}")
    finally:
        d.stop()
        d.launch()
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(2)
        open_vault(d)
        if d.find(FOLDER, exact=True) is not None:
            d.tap(FOLDER, exact=True)
            time.sleep(1)
            for n in names:
                if d.find(n, exact=True) is not None:
                    row_actions(d, n, "Удалить")
                    d.tap("Удалить", exact=True)
                    time.sleep(1)
            d.shell("input keyevent 4")
            time.sleep(1)
            row_actions(d, FOLDER, "Удалить папку")
            if d.find("Удалить", exact=True) is not None:
                d.tap("Удалить", exact=True)
    print("OK")


if __name__ == "__main__":
    main()
