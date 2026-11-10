"""Debug mode, "Install from address": a bare computer address is enough, and when the check fails the reason stays on
screen in a dialog instead of a short message.

Usage: python3 -I tools/e2e/debug_address.py --serial HOST:PORT

Sets a temporary device PIN for the unlock (Waydroid has no screen lock) and clears it at the end; turns the debug
mode off again. Installs nothing.
"""
import argparse
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from update_source import open_updates  # noqa: E402

PIN = "4826"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def enable_debug(d, pin):
    """Turns debug mode on from the browser's main screen: warning, then the device unlock with pin."""
    open_updates(d)
    d.scroll_to("Режим отладки")
    # The row can end up under the navigation bar, where a tap presses Home.
    d.shell("input swipe 540 1900 540 1000 300")
    time.sleep(2)
    # A tap right after the list settles is sometimes taken as stopping the scroll; tap again then.
    for _ in range(3):
        d.tap("Режим отладки", exact=True)
        end = time.time() + 5
        while d.find("Включить", exact=True) is None and time.time() < end:
            time.sleep(0.4)
        if d.find("Включить", exact=True) is not None:
            break
    d.tap("Включить", exact=True)
    time.sleep(2)
    d.type(pin)
    d.shell("input keyevent 66")
    d.wait("Установить с адреса", timeout=10, exact=True)
    d.shell("input swipe 540 1900 540 1000 300")
    time.sleep(2)


def check(d, address):
    d.tap("Установить с адреса", exact=True)
    field = d.find_class("android.widget.EditText")
    if field is None:
        d.fail("no address field")
    d.shell("input tap %d %d" % Device.center(field))
    d.shell("input keyevent 123 " + " ".join(["67"] * 80))
    d.type(address)
    d.tap("Проверить", exact=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    # The default port of serve_local.py, so a bare "localhost" must reach it.
    server = subprocess.Popen([sys.executable, "-I", "tools/publish/serve_local.py", "--no-build", "--shell",
                               "--serial", a.serial], cwd=ROOT, stdout=subprocess.PIPE, text=True)
    for line in server.stdout:
        if "localhost" in line:
            break
    d.shell(f"locksettings set-pin {PIN}")
    try:
        d.stop()
        d.launch()
        d.wait("Меню", timeout=30, exact=True)
        enable_debug(d, PIN)
        print("1. a bare address finds engines.json on the default port")
        check(d, "localhost")
        d.wait("Установить", timeout=20, exact=True)
        if d.find("Движок Gecko") is None:
            d.fail("no engine offered for a bare address")
        d.shell("input keyevent BACK")
        time.sleep(1)

        print("2. nothing listening on the port: the reason stays on screen")
        check(d, "localhost:8799")
        d.wait("Не удалось проверить", timeout=20, exact=True)
        time.sleep(4)
        if d.find("не запущен сервер") is None:
            d.fail("no lasting reason for a refused connection")
        d.tap("Понятно", exact=True)

        print("3. a host that does not answer: the reason names the firewall")
        check(d, "10.255.255.1")
        d.wait("Не удалось проверить", timeout=30, exact=True)
        time.sleep(4)
        if d.find("брандмауэр") is None:
            d.fail("no lasting reason for a host that does not answer")
        with open(os.path.join(a.out, "debug-address-timeout.png"), "wb") as f:
            f.write(d.adb("exec-out", "screencap", "-p", binary=True))
        d.tap("Понятно", exact=True)
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
