"""Frame times from the screen recording and from dumpsys gfxinfo."""
import os
import re
import statistics
import struct
import subprocess
import time

from PIL import Image


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


def menu_frames(d, serial, out, name):
    menu = d.center(d.wait("Меню", exact=True))
    raw_path = os.path.join(out, f"menu_frames_{name}.bin")
    with open(raw_path, "wb") as raw:
        rec = subprocess.Popen(["adb", "-s", serial, "exec-out",
                                "screenrecord --output-format=frames --size 270x600 -"], stdout=raw)
        time.sleep(1.5)
        d.shell(f"input tap {menu[0]} {menu[1]}")
        time.sleep(2)
        d.shell("pkill -INT screenrecord", check=False)
        rec.wait(timeout=20)
    d.shell("input keyevent BACK")
    time.sleep(1)
    # Frames on which the menu area (upper right quarter) changed; other parts of the screen may redraw on their own.
    changed, prev = 0, None
    for img in frames(raw_path):
        w, h = img.size
        area = img.crop((w // 2, h // 10, w, h // 2)).tobytes()
        if prev is not None and area != prev:
            changed += 1
        prev = area
    return changed


def set_choice(d, row, label):
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    d.tap("Настройки", exact=True)
    d.wait("Поиск по настройкам", exact=True)
    time.sleep(1)
    d.tap("Внешний вид", exact=True)
    time.sleep(1)
    d.tap(row, exact=True)
    d.tap(label, exact=True)
    for _ in range(3):
        d.shell("input keyevent BACK")
        time.sleep(1)
    d.wait("Меню", exact=True)
    time.sleep(1)


def set_speed(d, label):
    set_choice(d, "Скорость анимации", label)


def describe(xs):
    if not xs:
        return "no frames"
    xs = sorted(xs)
    p = lambda q: xs[min(len(xs) - 1, int(q * len(xs)))]
    return f"n={len(xs)} median={statistics.median(xs):.1f} p90={p(0.9):.1f} max={xs[-1]:.1f} ms, >16.7ms: {sum(x > 16.7 for x in xs)}"


def ms(rows):
    return [(r["FrameCompleted"] - r["IntendedVsync"]) / 1e6 for r in rows
            if r["IntendedVsync"] > 0 and 0 < r["FrameCompleted"] - r["IntendedVsync"] < 5e9]


def windows(dump):
    """Splits `gfxinfo framestats` into (window name, [frame rows as dicts])."""
    out = []
    for block in re.split(r"\n(?=Window: )", dump):
        m = re.match(r"Window: (.+)", block)
        if not m:
            continue
        stats = re.search(r"---PROFILEDATA---\n(.+?)\n(.*?)---PROFILEDATA---", block, re.S)
        rows = []
        if stats:
            head = stats.group(1).strip().rstrip(",").split(",")
            for line in stats.group(2).strip().splitlines():
                vals = line.strip().rstrip(",").split(",")
                if len(vals) == len(head):
                    rows.append(dict(zip(head, map(int, vals))))
        out.append((m.group(1).strip(), rows))
    return out
