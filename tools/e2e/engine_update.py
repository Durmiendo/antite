"""Engine updates: the browser asks before installing (or installs by itself in auto mode), restart switches,
and an engine that keeps crashing at start is rolled back.

Usage: ./gradlew :engine-gecko:adapterPackage :app:assembleDebug
       python3 -I tools/e2e/engine_update.py --serial SERIAL --apk app/build/outputs/apk/debug/app-debug.apk

Newer engines are the same GeckoView with a bumped version in the adapter's engine.json;
the broken one names a factory class that is not an engine.
Reinstalls the browser, so its data is lost.
"""
import argparse
import hashlib
import io
import json
import os
import subprocess
import sys
import time
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from engine_setup import KEYS, download_engine, engine_pages, prop  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8770


def bumped(version, step=1):
    head, last = version.rsplit(".", 1)
    return f"{head}.{int(last) + step}"


def newer_pages(pages, version, factory=None):
    """Rewrite the local source so that it offers `version`; `factory` breaks the engine on purpose."""
    src = zipfile.ZipFile(io.BytesIO(pages["/adapter.zip"]))
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for item in src.infolist():
            data = src.read(item.filename)
            if item.filename == "engine.json":
                manifest = json.loads(data)
                manifest["version"] = version
                if factory:
                    manifest["factory"] = factory
                data = json.dumps(manifest).encode()
            z.writestr(item, data)
    adapter = out.getvalue()
    index = json.loads(pages["/engines.json"])
    index["serial"] += 1
    entry = index["engines"][0]
    entry["version"] = version
    entry["adapter"]["size"] = len(adapter)
    entry["adapter"]["sha256"] = hashlib.sha256(adapter).hexdigest()
    body = json.dumps(index).encode()
    sig = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", f"{KEYS}/publisher.key.pem", "-passin", f"file:{KEYS}/password"],
        input=body, capture_output=True, check=True).stdout
    pages.update({"/engines.json": body, "/engines.json.sig": sig})
    pages["/adapter.zip"] = adapter


def open_page(d, server):
    while server.report(timeout=0.1):
        pass
    d.launch(f"http://localhost:{PORT}/page")
    ua = server.report(timeout=60)
    if not ua or "Firefox" not in ua:
        d.fail(f"page did not run: {ua!r}")
    print("page ran:", ua)


def open_updates(d):
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    d.tap("Настройки", exact=True)
    d.scroll_to("О программе")
    d.tap("О программе", exact=True)
    d.tap("Обновления", exact=True)


def run_job(d):
    d.shell(f"cmd jobscheduler run -f {PACKAGE} 1")


def crashes(d):
    return d.shell("logcat -d -b crash", check=False).count("FATAL EXCEPTION")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()

    d = Device(a.serial, a.out)
    version = prop("geckoviewVersion")
    pages = engine_pages(PORT)
    pages["/page"] = b"<!doctype html><body><script>fetch('/report?' + navigator.userAgent)</script>"
    server = ReportServer(PORT, pages)
    server.attach(d)

    print("1. clean install, engine", version)
    d.adb("uninstall", PACKAGE, check=False)
    d.install(a.apk)
    download_engine(d, PORT)
    open_page(d, server)

    print("2. ask mode: the job finds a newer engine, the browser asks")
    v2 = bumped(version)
    newer_pages(pages, v2)
    run_job(d)
    end = time.time() + 120
    while d.find("Доступен новый движок", exact=True) is None:
        if time.time() > end:
            d.fail("the browser did not offer the update")
        time.sleep(3)
        d.shell("input keyevent HOME")
        d.launch()
        time.sleep(2)
    d.wait(f"Gecko {v2}")
    d.tap("Обновить", exact=True)
    d.wait("Движок скачан", timeout=300, exact=True)
    d.tap("Перезапустить", exact=True)
    d.wait("Меню", timeout=60, exact=True)
    open_updates(d)
    d.wait(f"gecko {v2}")
    d.wait("Работает")
    open_page(d, server)

    print("3. auto mode installs a broken engine without asking")
    v3 = bumped(version, 2)
    newer_pages(pages, v3, factory="java.lang.Object")
    open_updates(d)
    d.tap("Установка", exact=True)
    d.tap("Автоматически", exact=True)
    d.shell("input keyevent BACK")
    run_job(d)
    end = time.time() + 300
    while d.find("Перезапустить браузер", exact=True) is None:
        if time.time() > end:
            d.fail("the job did not download the engine")
        d.shell("input keyevent BACK")
        d.scroll_to("Обновления")
        d.tap("Обновления", exact=True)
        time.sleep(3)
    d.shell("logcat -b crash -c")
    d.tap("Перезапустить браузер", exact=True)

    print("4. it crashes at start; after 3 failed starts the previous engine comes back")
    for i in range(3):
        if i > 0:
            d.stop()
            d.launch()
        end = time.time() + 30
        while crashes(d) < i + 1:
            if time.time() > end:
                d.fail(f"broken engine did not crash on start {i + 1}")
            time.sleep(1)
        time.sleep(2)
        if d.find("Закрыть приложение", exact=True) is not None:
            d.tap("Закрыть приложение", exact=True)
    d.stop()
    d.launch()
    d.wait("Новый движок не запустился", timeout=60, exact=True)
    d.wait(f"Gecko {v3} трижды")
    d.tap("Понятно", exact=True)
    open_updates(d)
    d.wait(f"gecko {v2}")
    d.tap("Проверить сейчас", exact=True)
    d.wait("обновлений нет", timeout=30)
    open_page(d, server)
    server.close()
    print("OK")


if __name__ == "__main__":
    main()
