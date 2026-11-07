"""The proto-loader shell must run GeckoView loaded from engine files, not from its APK.

Usage: tools/proto/make_engine.sh SERIAL
       python3 -I tools/e2e/proto.py --serial SERIAL --apk proto-loader/build/outputs/apk/debug/proto-loader-debug.apk
"""
import argparse
import os
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--apk", required=True)
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
    d.adb("logcat", "-c")
    d.shell(f"am start -a android.intent.action.VIEW -d 'http://localhost:{PORT}/proto' -n {PACKAGE}/.MainActivity")
    ua = server.report(timeout=90)
    log = d.adb("logcat", "-d", "-s", "AntiteProto")
    print(log.strip())
    if ua is None:
        d.fail("page did not report: the engine did not run")
    if "dalvik.system.DexClassLoader" not in log:
        d.fail("GeckoRuntime was not loaded by the engine class loader")
    print("page ran, user agent:", ua)
    print("OK")
    server.close()


if __name__ == "__main__":
    main()
