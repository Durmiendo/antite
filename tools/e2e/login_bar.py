"""Login suggestions sit in a bar above the keyboard, not in a dialog: one can type a password of one's own,
or take the suggested strong password from the bar; hiding the keyboard drops the bar.

Usage: python3 -I tools/e2e/login_bar.py --serial HOST:PORT
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8775
SUGGEST = "Предложить надёжный пароль"
PAGE = b"""<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<body style="margin:0"><form style="padding:16px" onsubmit="return false">
<input name=user autocomplete=username value=tester style="display:block;width:90%;height:60px;font-size:20px">
<input id=p name=pass type=password autocomplete=new-password
 style="position:absolute;top:200px;left:5%;width:90%;height:200px;font-size:20px"></form>
<script>p.addEventListener('input', () => fetch('/report?len,' + p.value.length));</script>"""


def last_len(server, seconds):
    end = time.time() + seconds
    n = None
    while time.time() < end:
        r = server.report(timeout=max(0.05, end - time.time()))
        if r and r.startswith("len,"):
            n = int(r.split(",")[1])
    return n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    server = ReportServer(PORT, {"/signup": PAGE})
    server.attach(d)
    d.stop()
    d.launch(f"http://localhost:{PORT}/signup")
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(3)

    print("1. the strong password is offered in a bar, not in a dialog")
    d.shell("input tap 540 1000")
    d.wait(SUGGEST, timeout=10)
    if d.find("Войти как") is not None:
        d.fail("a dialog is shown instead of the bar")

    print("2. a password of one's own can be typed")
    d.type("mine123")
    if last_len(server, 3) != 7:
        d.fail("own password was not typed into the field")

    print("3. the bar fills the strong password")
    for _ in range(7):
        d.shell("input keyevent DEL")
    last_len(server, 1)
    if d.find(SUGGEST) is None:
        d.shell("input tap 540 1000")
        d.wait(SUGGEST, timeout=10)
    d.tap(SUGGEST)
    n = last_len(server, 3)
    if not n or n < 12:
        d.fail(f"strong password not filled, length {n}")

    print("4. hiding the keyboard drops the bar")
    d.shell("input keyevent BACK")
    time.sleep(1.5)
    if d.find(SUGGEST) is not None:
        d.fail("bar stayed without the keyboard")
    server.close()
    print("OK")


if __name__ == "__main__":
    main()
