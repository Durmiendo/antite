"""An index made by tools/publish/publish_engine.py installs: real per-ABI AAR from maven.mozilla.org.

Usage: ./gradlew :app:assembleDebug
       python3 -I tools/e2e/published_index.py --serial SERIAL --apk app/build/outputs/apk/debug/app-x86_64-debug.apk

Only the adapter is served locally; the engine (about 95 MB) is downloaded from Mozilla.
Reinstalls the browser, so its data is lost.
"""
import argparse
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from engine_setup import download_engine  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8772
OUT = "build/publish/e2e"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)

    print("1. publish into", OUT)
    for f in ("engines.json", "engines.json.sig"):
        if os.path.exists(os.path.join(OUT, f)):
            os.remove(os.path.join(OUT, f))
    subprocess.run([sys.executable, "-I", "tools/publish/publish_engine.py", "--out", OUT,
                    "--adapter-base", f"http://localhost:{PORT}"], check=True)
    index = json.load(open(os.path.join(OUT, "engines.json")))
    adapter_url = index["engines"][0]["adapter"]["url"][0]
    adapter_name = adapter_url.rsplit("/", 1)[1]
    pages = {
        "/engines.json": open(os.path.join(OUT, "engines.json"), "rb").read(),
        "/engines.json.sig": open(os.path.join(OUT, "engines.json.sig"), "rb").read(),
        "/" + adapter_name: open("engine-gecko/build/outputs/engine/" + adapter_name, "rb").read(),
        "/page": b"<!doctype html><body><script>fetch('/report?' + navigator.userAgent)</script>",
    }
    server = ReportServer(PORT, pages)
    server.attach(d)

    print("2. clean install, engine from the published index")
    d.adb("uninstall", PACKAGE, check=False)
    d.install(a.apk)
    download_engine(d, PORT)

    print("3. open a page")
    d.launch(f"http://localhost:{PORT}/page")
    ua = server.report(timeout=60)
    if not ua or "Firefox" not in ua:
        d.fail(f"page did not run: {ua!r}")
    print("page ran:", ua)
    server.close()
    print("OK")


if __name__ == "__main__":
    main()
