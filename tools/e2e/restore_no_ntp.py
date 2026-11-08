"""A cold start restores the last tab without flashing the new tab page first (Gecko opens about:blank before
restoring the session; Firefox ignores that initial load too).

Usage: python3 -I tools/e2e/restore_no_ntp.py --serial HOST:PORT
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8777
PAGE = b"<!doctype html><title>Restored page</title><body style='background:#00f'><h1>restored</h1>"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    server = ReportServer(PORT, {"/page": PAGE})
    server.attach(d)
    d.stop()
    d.launch(f"http://localhost:{PORT}/page")
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(3)
    d.shell("input keyevent HOME")
    time.sleep(2)
    d.stop()
    time.sleep(1)
    d.shell(f"am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n {PACKAGE}/.MainActivity")
    end = time.time() + 8
    seen_page = False
    while time.time() < end:
        texts = {n.get("text") for n in d.nodes()}
        if "Закреплённые" in texts:
            d.fail("new tab page flashed while the last tab was being restored")
        if any(t and "localhost" in t for t in texts):
            seen_page = True
    if not seen_page:
        d.fail("the last tab was not restored")
    server.close()
    print("OK")


if __name__ == "__main__":
    main()
