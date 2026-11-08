"""A page whose icon is a data: URL must not crash the browser.

Usage: python3 -I tools/e2e/data_icon.py --serial HOST:PORT
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8771
PAGE = b"""<!doctype html><head><link rel="icon" href="data:,"></head><body>
<script>fetch('/report?loaded')</script>"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    server = ReportServer(PORT, {"/icon": PAGE})
    server.attach(d)
    d.stop()
    d.launch(f"http://localhost:{PORT}/icon?{int(time.time())}")
    if server.report(timeout=60) != "loaded":
        d.fail("page did not load")
    time.sleep(5)
    if not d.shell(f"pidof {PACKAGE}", check=False).strip():
        d.fail("browser process died")
    if PACKAGE not in d.top_activity():
        d.fail("browser is not on screen")
    server.close()
    print("OK")


if __name__ == "__main__":
    main()
