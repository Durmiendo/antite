"""Always use secure connections, like Chrome: a site without HTTPS shows "Connection is not secure"; "Continue to site"
opens it over HTTP and the site stays allowed for the session. The site is a local http server (adb reverse) under the
reserved name antite-test.example: Gecko never upgrades localhost, so for the test the pref network.dns.forceResolve
sends every name to 127.0.0.1, set through the androidTest GeckoPrefTool (install the androidTest APK first). The
setting and the pref are reset at the end.

Usage: python3 -I tools/e2e/https_only.py --serial SERIAL
"""
import argparse
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import Device  # noqa: E402

PORT = 8788
SITE = f"http://antite-test.example:{PORT}/"
TEXT = "PLAIN HTTP ONLY"
PAGE = f"""<!doctype html><meta name=viewport content="width=device-width"><h1>{TEXT}</h1>""".encode()
RUNNER = "dev.antite.browser.test/androidx.test.runner.AndroidJUnitRunner"


def serve():
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(PAGE)))
            self.end_headers()
            self.wfile.write(PAGE)

        def log_message(self, *args):
            pass

    # A TLS hello on a plain http port fails the upgraded https request, as on a site without HTTPS.
    http = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=http.serve_forever, daemon=True).start()
    return http


def force_resolve(d, value):
    out = d.shell(f"am instrument -w -e class dev.antite.browser.GeckoPrefTool "
                  f"-e pref network.dns.forceResolve -e value {value} -e type string {RUNNER}")
    if "OK (1 test)" not in out:
        d.fail(f"could not set the Gecko pref: {out.strip()[-300:]}")


def toggle_https_only(d):
    time.sleep(2)
    d.tap("Меню", exact=True)
    time.sleep(1)
    d.scroll_to("Настройки")
    d.tap("Настройки", exact=True)
    d.wait("Поиск по настройкам", exact=True)
    time.sleep(1)
    d.tap("Конфиденциальность и безопасность", exact=True)
    time.sleep(1)
    d.tap("Всегда использовать безопасные подключения", exact=True)
    time.sleep(1)
    for _ in range(2):
        d.shell("input keyevent BACK")
        time.sleep(1)
    d.wait("Меню", exact=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    http = serve()
    d.adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    force_resolve(d, "127.0.0.1")
    d.stop()
    d.launch()
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(1)
    toggle_https_only(d)
    try:
        d.launch(SITE)
        d.wait("Подключение не защищено", timeout=40, exact=True)
        print("1. warning shown")
        # Gecko reports the upgraded https address; the bar must show the http the site really has.
        if d.find(SITE, exact=True) is None:
            d.fail("the address bar does not show http:// on the warning")
        print("1a. address bar shows http://")
        with open(os.path.join(a.out, "https-only-warning.png"), "wb") as f:
            f.write(d.adb("exec-out", "screencap", "-p", binary=True))
        d.tap("Перейти на сайт", exact=True)
        end = time.time() + 40
        while d.find("Подключение не защищено", exact=True) is not None or d.find(TEXT) is None:
            if time.time() > end:
                d.fail("the site did not open over HTTP")
            time.sleep(1)
        print("2. opened over HTTP")
        # Gecko keeps the exception for the session, as Firefox does: opening the site again goes straight to HTTP.
        d.launch(SITE)
        time.sleep(8)
        if d.find("Подключение не защищено", exact=True) is not None or d.find(TEXT) is None:
            d.fail("the site was not remembered in this session")
        print("3. remembered for the session")
    finally:
        http.shutdown()
        d.stop()
        d.launch()
        d.wait("Меню", timeout=30, exact=True)
        toggle_https_only(d)
        force_resolve(d, "clear")
    print("OK")


if __name__ == "__main__":
    main()
