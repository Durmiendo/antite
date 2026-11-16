"""A page whose process dies while the browser is in the background is not loaded again there: it runs again only
after the user returns, and then exactly once.

Usage: python3 -I tools/e2e/background_kill.py --serial HOST:PORT
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device, PACKAGE  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8773
TEXT = "Stranica zhiva"
# The page may come back from the HTTP cache without a request, so each run of its script reports itself.
PAGE = (f"<!doctype html><meta name=viewport content='width=device-width'><h1>{TEXT}</h1>"
        "<script>fetch('/report?load' + Date.now())</script>").encode()


def tab_pids(d):
    out = d.shell(f"ps -A -o PID,NAME | grep '{PACKAGE}:.*tab'", check=False)
    return [line.split()[0] for line in out.splitlines() if line.strip()]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    server = ReportServer(PORT, {"/page": PAGE})
    server.attach(d)

    def loads():
        return sum(1 for r in server.requests if r.startswith("/report?load"))

    d.stop()
    d.launch(f"http://localhost:{PORT}/page")
    d.wait("Меню", timeout=30, exact=True)
    d.wait(TEXT, timeout=30)
    time.sleep(3)
    before = loads()

    print("1. the tab processes die while the browser is in the background")
    d.shell("input keyevent HOME")
    time.sleep(3)
    pids = tab_pids(d)
    if not pids:
        d.fail("no tab process found")
    for pid in pids:
        d.shell(f"am crash {pid}", check=False)
    time.sleep(8)
    if set(tab_pids(d)) & set(pids):
        d.fail("the tab processes are still alive")
    if loads() != before:
        d.fail(f"the page was loaded in the background: {loads() - before} request(s)")

    print("2. back in the browser the page loads again, once")
    d.launch()
    d.wait(TEXT, timeout=30)
    time.sleep(3)
    if loads() != before + 1:
        d.fail(f"expected one load after returning, got {loads() - before}")
    server.close()
    print("OK")


if __name__ == "__main__":
    main()
