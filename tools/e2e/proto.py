"""Runs the proto-loader shell with GeckoView loaded from engine files and reports what happened.

Usage: tools/proto/make_engine.sh SERIAL [ABI]
       python3 -I tools/e2e/proto.py --serial SERIAL --apk proto-loader-debug.apk [--run-as] [--isolated] [--zygote]

--run-as copies the engine into the app directory with run-as before the start; needed on a real
phone, where SELinux does not let apps read /data/local/tmp.
"""
import argparse
import os
import re
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402
from server import ReportServer  # noqa: E402

PACKAGE = "dev.antite.proto"
PORT = 8768
PAGE = b"""<!doctype html><body><script>
fetch('/report?' + encodeURIComponent(navigator.userAgent))
</script>"""
PRELOAD = ("rm -rf files/engine && mkdir -p files/engine && cp -r /data/local/tmp/antite-engine/. files/engine/ "
           "&& touch files/engine/ready && find files/engine -type f -exec chmod 444 {} +")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--run-as", action="store_true")
    ap.add_argument("--isolated", action="store_true")
    ap.add_argument("--zygote", action="store_true")
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()

    names = zipfile.ZipFile(a.apk).namelist()
    bundled = [n for n in names if "libxul" in n or "omni.ja" in n or n.startswith("org/mozilla")]
    if bundled:
        sys.exit(f"FAIL: engine files inside the shell APK: {bundled[:5]}")

    d = Device(a.serial, a.out)
    server = ReportServer(PORT, {"/proto": PAGE})
    server.attach(d)
    d.adb("uninstall", PACKAGE, check=False)
    d.install(a.apk)
    if a.run_as:
        d.shell(f"run-as {PACKAGE} sh -c '{PRELOAD}'")
    d.adb("logcat", "-c")
    extras = f"--ez isolated {str(a.isolated).lower()} --ez zygote {str(a.zygote).lower()}"
    d.shell(f"am start -a android.intent.action.VIEW -d 'http://localhost:{PORT}/proto' {extras} "
            f"-n {PACKAGE}/.MainActivity")
    ua = server.report(timeout=60)

    log = d.adb("logcat", "-d")
    procs = [line.split()[-1] for line in d.shell("ps -A").splitlines() if PACKAGE in line]
    print("processes:", *procs, sep="\n  ")
    for line in log.splitlines():
        if "AntiteProto" in line or ("avc:" in line and "denied" in line and re.search(r"app_zygote|isolated_app|proto", line)):
            print(" ", line[line.find(" I ") if " I " in line else 0:][:400])
    crashes = [line for line in log.splitlines() if "FATAL EXCEPTION" in line or ("AndroidRuntime" in line and "Process: " + PACKAGE in line)]
    for line in log.splitlines():
        if "AndroidRuntime" in line and ("Exception" in line or "Error" in line):
            crashes.append(line)
    if crashes:
        print("crashes:", *dict.fromkeys(crashes), sep="\n  ")
    print("page ran:", ua or "NO")
    server.close()
    # The APK has no GeckoView, so a page that ran proves the engine came from the files.
    print("OK" if ua else "FAIL")
    sys.exit(0 if ua else 1)


if __name__ == "__main__":
    main()
