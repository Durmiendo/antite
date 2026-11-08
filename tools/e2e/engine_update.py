"""Engine update: the daily job finds a newer engine in the source, downloads it, the restart button switches to it.

Usage: ./gradlew :engine-gecko:adapterPackage :app:assembleDebug
       python3 -I tools/e2e/engine_update.py --serial SERIAL --apk app/build/outputs/apk/debug/app-x86_64-debug.apk

The newer engine is the same GeckoView with a bumped version in the adapter's engine.json.
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


def bumped(version):
    head, last = version.rsplit(".", 1)
    return f"{head}.{int(last) + 1}"


def newer_pages(pages, version):
    """Rewrite the local source so that it offers `version`."""
    src = zipfile.ZipFile(io.BytesIO(pages["/adapter.zip"]))
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for item in src.infolist():
            data = src.read(item.filename)
            if item.filename == "engine.json":
                manifest = json.loads(data)
                manifest["version"] = version
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
    pages.update({"/engines.json": body, "/engines.json.sig": sig, "/adapter.zip": adapter})


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
    d.scroll_to("Обновления")
    d.tap("Обновления", exact=True)


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

    print("2. the source offers a newer engine, run the daily job")
    new = bumped(version)
    newer_pages(pages, new)
    d.shell(f"cmd jobscheduler run -f {PACKAGE} 1")
    open_updates(d)
    end = time.time() + 300
    while d.find("Перезапустить браузер", exact=True) is None:
        if time.time() > end:
            d.fail("the job did not download the new engine")
        d.shell("input keyevent BACK")
        d.tap("Обновления", exact=True)
        time.sleep(3)
    d.wait(f"Скачан Gecko {new}")
    d.wait(f"gecko {version}")

    print("3. restart into the new engine")
    d.tap("Перезапустить браузер", exact=True)
    d.wait("Меню", timeout=60, exact=True)
    open_updates(d)
    d.wait(f"gecko {new}")
    if d.find("Перезапустить браузер", exact=True) is not None:
        d.fail("restart row is still shown")

    print("4. a page works on the new engine")
    open_page(d, server)

    print("5. the source has nothing newer")
    d.stop()
    d.launch()
    open_updates(d)
    d.tap("Проверить сейчас", exact=True)
    d.wait("обновлений нет", timeout=30)
    server.close()
    print("OK")


if __name__ == "__main__":
    main()
