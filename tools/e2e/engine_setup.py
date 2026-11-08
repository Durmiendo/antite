"""First start of the shell: no engine, download it from a local signed index, open a page.

Usage: ./gradlew :engine-gecko:adapterPackage :app:assembleDebug
       python3 -I tools/e2e/engine_setup.py --serial SERIAL --apk app/build/outputs/apk/debug/app-debug.apk

Signs the local index with the publisher key from ~/.antite-keys. Reinstalls the browser, so its data is lost.
--abi installs the shell for that ABI (on emulators with arm translation) and checks the engine follows it.
"""
import argparse
import glob
import hashlib
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8769
KEYS = os.path.expanduser("~/.antite-keys")
PAGE = b"""<!doctype html><body><script>
fetch('/report?' + encodeURIComponent(navigator.userAgent))
</script>"""


def prop(name):
    for line in open("gradle.properties"):
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    raise KeyError(name)


def artifact(port, path, data):
    return {"url": [f"http://localhost:{port}{path}"], "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def engine_pages(port):
    """Files of a local engine source: a signed index, the adapter package and the GeckoView AAR."""
    version = prop("geckoviewVersion")
    adapter = open(f"engine-gecko/build/outputs/engine/gecko-{version}-adapter.zip", "rb").read()
    aar_path = glob.glob(os.path.expanduser(f"~/.gradle/caches/modules-2/files-2.1/org.mozilla.geckoview/geckoview/{version}/*/geckoview-{version}.aar"))[0]
    aar = open(aar_path, "rb").read()
    index = json.dumps({
        "format": 1,
        "serial": int(time.time()),
        "engines": [{
            "id": "gecko-release", "type": "gecko", "api": int(prop("engineApi")), "channel": "release",
            "version": version, "minShell": 1,
            "adapter": artifact(port, "/adapter.zip", adapter),
            "upstream": {abi: artifact(port, "/engine.aar", aar) for abi in ("x86_64", "arm64-v8a", "armeabi-v7a")},
        }],
    }).encode()
    sig = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", f"{KEYS}/publisher.key.pem", "-passin", f"file:{KEYS}/password"],
        input=index, capture_output=True, check=True).stdout
    return {"/engines.json": index, "/engines.json.sig": sig, "/adapter.zip": adapter, "/engine.aar": aar}


def download_engine(d, port):
    """From a browser without an engine: open the setup screen, download, wait for the browser."""
    d.stop()
    d.shell(f"am start -n {PACKAGE}/.MainActivity --es indexUrl http://localhost:{port}/engines.json")
    d.wait("Gecko " + prop("geckoviewVersion"), timeout=30)
    d.tap("Скачать", exact=True)
    d.wait("Закреплённые", timeout=300, exact=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", default="build/e2e")
    ap.add_argument("--abi", default=None)
    a = ap.parse_args()

    d = Device(a.serial, a.out)
    server = ReportServer(PORT, {**engine_pages(PORT), "/page": PAGE})
    server.attach(d)

    print("1. clean install of the shell")
    d.adb("uninstall", PACKAGE, check=False)
    d.install(a.apk, a.abi)
    d.shell("logcat -c")

    print("2. download the engine from the setup screen")
    download_engine(d, PORT)

    print("3. open a page")
    d.launch(f"http://localhost:{PORT}/page")
    ua = server.report(timeout=60)
    if not ua or "Firefox" not in ua:
        d.fail(f"page did not run: {ua!r}")
    print("page ran:", ua)
    if a.abi:
        primary = d.shell(f"dumpsys package {PACKAGE} | grep primaryCpuAbi", check=False).strip()
        engine = [l for l in d.shell("logcat -d -s Antite", check=False).splitlines() if " engine gecko-" in l]
        if a.abi not in primary or not engine or not engine[-1].rstrip().endswith("-" + a.abi):
            d.fail(f"engine does not follow the process ABI: {primary!r} {engine[-1:]!r}")
        print("engine for", a.abi, ":", engine[-1].split("engine ")[-1])
    server.close()
    print("OK")


if __name__ == "__main__":
    main()
