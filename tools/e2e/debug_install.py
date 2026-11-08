"""Debug mode: after a warning and device unlock, an engine from tools/publish/serve_local.py is installed from
an address over plain http without a signature, and the browser runs it after a restart.

Usage: python3 -I tools/e2e/debug_install.py --serial HOST:PORT

Sets a temporary device PIN for the unlock (Waydroid has no screen lock) and clears it at the end; turns the
debug mode off again. The installed engine stays.
"""
import argparse
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from update_source import open_updates  # noqa: E402

PORT = 8791
PIN = "4826"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    server = subprocess.Popen(
        [sys.executable, "-I", "tools/publish/serve_local.py", "--port", str(PORT), "--serial", a.serial],
        cwd=ROOT, stdout=subprocess.PIPE, text=True)
    version = None
    for line in server.stdout:
        m = re.match(r"engine (\S+)", line)
        if m:
            version = m.group(1)
        if "localhost" in line:
            break
    print("serving engine", version)
    d.shell(f"locksettings set-pin {PIN}")
    try:
        print("1. debug mode needs a warning and the device unlock")
        open_updates(d)
        d.scroll_to("Режим отладки")
        d.tap("Режим отладки", exact=True)
        d.tap("Включить", exact=True)
        time.sleep(2)
        d.type(PIN)
        d.shell("input keyevent 66")
        d.wait("Установить с адреса", timeout=10, exact=True)

        print("2. an engine from a plain http address, without a signature")
        d.tap("Установить с адреса", exact=True)
        d.tap("Адрес engines.json", exact=True)
        d.shell("input keyevent 123 " + " ".join(["67"] * 80))
        d.type(f"http://localhost:{PORT}/engines.json")
        d.tap("Проверить", exact=True)
        d.tap("Движок Gecko " + version, timeout=30)
        d.wait("Перезапустить", timeout=300, exact=True)
        d.tap("Перезапустить", exact=True)
        time.sleep(10)
        d.stop()
        d.launch()
        d.wait("Меню", timeout=40, exact=True)
        open_updates(d)
        d.scroll_to("Установлено")
        if d.find("gecko " + version, exact=True) is None:
            d.fail(f"engine {version} is not running")
        print("   running", version)
    finally:
        print("cleanup")
        d.shell(f"locksettings clear --old {PIN}", check=False)
        open_updates(d)
        d.scroll_to("Режим отладки")
        if d.find("Установить с адреса", exact=True) is not None:
            d.tap("Режим отладки", exact=True)
        server.terminate()
    print("OK")


if __name__ == "__main__":
    main()
