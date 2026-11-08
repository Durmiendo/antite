"""Shell self-update: the index offers a newer shell, the browser asks, the system installer updates it, data stays.

Usage: ./gradlew :engine-gecko:adapterPackage :app:assembleDebug
       python3 -I tools/e2e/shell_update.py --serial SERIAL --apk app/build/outputs/apk/debug/app-debug.apk

Builds the same debug shell with the next versionCode as the update, then rebuilds the current one.
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
from engine_setup import KEYS, download_engine, engine_pages, prop  # noqa: E402
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
    cur = int(prop("shellVersionCode"))
    new = cur + 1
    print(f"0. build the update (versionCode {new})")
    subprocess.run(["./gradlew", "-q", ":app:assembleDebug", f"-PshellVersionCode={new}"], check=True)
    shutil.copy(a.apk, v2)
    subprocess.run(["./gradlew", "-q", ":app:assembleDebug"], check=True)

    pages = engine_pages(PORT)
    pages["/page"] = b"<!doctype html><body><script>fetch('/report?' + navigator.userAgent)</script>"
    # The update APK is sent slowly so its download progress can be checked.
    server = ReportServer(PORT, pages, rates={"/antite-v2.apk": 4 * 1024 * 1024})
    server.attach(d)

    print(f"1. clean install of versionCode {cur} with an engine")
    d.adb("uninstall", PACKAGE, check=False)
    d.install(v1)
    download_engine(d, PORT)
    d.launch(f"http://localhost:{PORT}/page")
    if not server.report(timeout=60):
        d.fail("page did not run")
    if version_code(d) != cur:
        d.fail(f"expected versionCode {cur}, got {version_code(d)}")

    print("2. the index offers the next shell")
    apk = open(v2, "rb").read()
    index = json.loads(pages["/engines.json"])
    index["serial"] += 1
    index["shell"] = {"version": new, "apk": {"x86_64": {
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
    d.tap("Позже", exact=True)

    print("   start it from Settings > Updates: progress only in that row")
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    d.tap("Настройки", exact=True)
    d.scroll_to("Обновления")
    d.tap("Обновления", exact=True)
    d.tap("Обновить Antite", exact=True)
    rows = [n for n in d.nodes() if "Скачиваем" in (n.get("text") or "")]
    end = time.time() + 10
    while not rows and time.time() < end:
        rows = [n for n in d.nodes() if "Скачиваем" in (n.get("text") or "")]
    if len(rows) != 1:
        d.fail(f"download progress shown in {len(rows)} rows")

    print("3. the system installer: allow this source once, confirm the update")
    end = time.time() + 120
    allowed = False
    while version_code(d) != new:
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
    print(f"   versionCode {new} installed")

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
    d.wait(f"Сборка {new}", exact=True)
    if d.find("Обновить Antite", exact=True) is not None:
        d.fail("the update is still offered")
    server.close()
    print("OK")


if __name__ == "__main__":
    main()
