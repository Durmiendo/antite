"""Page viewport while the navigation bar and the keyboard come and go: every resize event the page sees is
reported. Ideally each transition resizes the page once; a resize per animation frame makes the page jerk.

Counts both window resizes (layout viewport) and visualViewport resizes: with the keyboard Gecko usually resizes
only the visual viewport.

Usage: python3 -I tools/e2e/viewport_jitter.py --serial HOST:PORT [--max-resizes N]

Without --max-resizes it only prints the counts.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8773
PAGE = b"""<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<body style="margin:0">
<input id=i style="position:absolute;top:300px;left:5%;width:90%;font-size:20px;height:40px">
<div style="height:8000px;background:linear-gradient(#335,#a53)"></div>
<script>
addEventListener('resize', () => fetch('/report?resize,' + innerHeight + ',' + scrollY));
visualViewport.addEventListener('resize', () => fetch('/report?visual,' + visualViewport.height + ',' + scrollY));
fetch('/report?ready,' + innerHeight);
</script>"""


def collect(server, seconds):
    end = time.time() + seconds
    out = []
    while time.time() < end:
        r = server.report(timeout=max(0.05, end - time.time()))
        if r and (r.startswith("resize") or r.startswith("visual")):
            kind, h, y = r.split(",")
            out.append((kind, round(float(h)), round(float(y))))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    ap.add_argument("--max-resizes", type=int)
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    server = ReportServer(PORT, {"/page": PAGE})
    server.attach(d)
    d.stop()
    d.launch(f"http://localhost:{PORT}/page")
    d.wait("Меню", timeout=30, exact=True)
    r = server.report(timeout=30)
    if not r or not r.startswith("ready"):
        d.fail(f"page did not load: {r!r}")
    time.sleep(2)
    collect(server, 0.5)

    results = {}
    d.shell("input swipe 540 1700 540 900 400")
    results["navigation bar hides"] = collect(server, 3)
    d.shell("input swipe 540 900 540 1700 400")
    results["navigation bar shows"] = collect(server, 3)
    for _ in range(3):
        d.shell("input swipe 540 600 540 2000 150")
    time.sleep(2)
    collect(server, 0.5)
    d.shell("input tap 540 1000")
    results["keyboard shows"] = collect(server, 3)
    d.shell("input keyevent BACK")
    results["keyboard hides"] = collect(server, 3)

    worst = 0
    for name, events in results.items():
        for kind in ("resize", "visual"):
            heights = [h for k, h, _ in events if k == kind]
            print(f"{name}: {kind} {len(heights)}: {heights}")
            worst = max(worst, len(heights))
    if a.max_resizes is not None:
        if worst > a.max_resizes:
            d.fail(f"a transition resized the page {worst} times, allowed {a.max_resizes}")
        if not any(k == "visual" for k, _, _ in results["keyboard shows"]):
            d.fail("keyboard did not resize the page at all")
    server.close()
    print("OK")


if __name__ == "__main__":
    main()
