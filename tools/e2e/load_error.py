"""Opening an unreachable address must show an error page, not the new tab page.

Usage: python3 -I tools/e2e/load_error.py --serial HOST:PORT
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402

URL = "http://localhost:8799/unreachable"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    d.stop()
    d.launch(URL)
    d.wait("localhost:8799", timeout=20)
    time.sleep(2)
    if d.find("Закреплённые") is not None:
        d.fail("new tab page is shown instead of an error page")
    d.wait("Не удалось открыть страницу", timeout=10)
    d.wait("отклонил подключение", timeout=5)
    print("OK")


if __name__ == "__main__":
    main()
