"""Shell self-update: the index offers a newer shell, the browser asks, the system installer updates it, data stays.

Usage: ./gradlew :engine-gecko:adapterPackage :app:assembleDebug
       python3 -I tools/e2e/shell_update.py --serial SERIAL --apk app/build/outputs/apk/debug/app-x86_64-debug.apk

Builds the same debug shell with versionCode 2 as the update, then rebuilds versionCode 1.
Goes through the real "install unknown apps" permission screen. Reinstalls the browser, so its data is lost.
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from engine_setup import KEYS, download_engine, engine_pages  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8774


def version_code(d):
    out = d.shell(f"dumpsys package {PACKAGE} | grep versionCode=", check=False)
    return int(out.split("versionCode=")[1].split()[0]) if "versionCode=" in out else 0


def sign(body):
    return subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", f"{KEYS}/publisher.key.pem", "-passin", f"file:{KEYS}/password"],
        input=body, capture_output=True, check=True).stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    os.makedirs(a.out, exist_ok=True)
    v1 = os.path.join(a.out, "antite-v1.apk")
    v2 = os.path.join(a.out, "antite-v2.apk")
    shutil.copy(a.apk, v1)
    print("0. build the update (versionCode 2)")
    subprocess.run(["./gradlew", "-q", ":app:assembleDebug", "-PshellVersionCode=2"], check=True)
    shutil.copy(a.apk, v2)
    subprocess.run(["./gradlew", "-q", ":app:assembleDebug"], check=True)

    pages = engine_pages(PORT)
    pages["/page"] = b"<!doctype html><body><script>fetch('/report?' + navigator.userAgent)</script>"
    server = ReportServer(PORT, pages)
    server.attach(d)

    print("1. clean install of versionCode 1 with an engine")
    d.adb("uninstall", PACKAGE, check=False)
    d.install(v1)
    download_engine(d, PORT)
    d.launch(f"http://localhost:{PORT}/page")
    if not server.report(timeout=60):
        d.fail("page did not run")
    if version_code(d) != 1:
        d.fail(f"expected versionCode 1, got {version_code(d)}")

    print("2. the index offers shell 2")
    apk = open(v2, "rb").read()
    index = json.loads(pages["/engines.json"])
    index["serial"] += 1
    index["shell"] = {"version": 2, "apk": {"x86_64": {
        "url": [f"http://localhost:{PORT}/antite-v2.apk"], "size": len(apk), "sha256": hashlib.sha256(apk).hexdigest()}}}
    body = json.dumps(index).encode()
    pages.update({"/engines.json": body, "/engines.json.sig": sign(body), "/antite-v2.apk": apk})
    d.shell(f"cmd jobscheduler run -f {PACKAGE} 1")
    end = time.time() + 120
    while d.find("Доступна новая версия Antite", exact=True) is None:
        if time.time() > end:
            d.fail("the browser did not offer the shell update")
        time.sleep(3)
        d.shell("input keyevent HOME")
        d.launch()
        time.sleep(2)
    d.tap("Обновить", exact=True)

    print("3. the system installer: allow this source once, confirm the update")
    end = time.time() + 120
    allowed = False
    while version_code(d) != 2:
        if time.time() > end:
            d.fail("the shell was not updated")
        if not allowed and "ExternalSources" in d.top_activity():
            d.tap("Разрешить установку")
            allowed = True
            time.sleep(5)
            if "ExternalSources" in d.top_activity() and d.find("Обновить", exact=True) is None:
                d.shell("input keyevent BACK")
        elif not allowed and d.find("Настройки", exact=True) is not None and d.find("неизвестн") is not None:
            d.tap("Настройки", exact=True)
        elif d.find("Обновить", exact=True) is not None:
            d.tap("Обновить", exact=True)
        time.sleep(2)
    print("   versionCode 2 installed")

    print("4. the updated browser keeps its engine and data")
    d.stop()
    while server.report(timeout=0.1):
        pass
    d.launch(f"http://localhost:{PORT}/page")
    ua = server.report(timeout=60)
    if not ua or "Firefox" not in ua:
        d.fail(f"page did not run after the update: {ua!r}")
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    d.tap("Настройки", exact=True)
    d.scroll_to("Обновления")
    d.tap("Обновления", exact=True)
    d.wait("Сборка 2", exact=True)
    if d.find("Обновить Antite", exact=True) is not None:
        d.fail("the update is still offered")
    server.close()
    print("OK")


if __name__ == "__main__":
    main()
