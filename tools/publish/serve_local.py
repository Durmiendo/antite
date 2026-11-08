"""Serves the current build for the browser's debug mode (Settings > Updates > Debug > Install from address).

Usage: python3 -I tools/publish/serve_local.py [--port 8790] [--serial SERIAL] [--shell] [--no-build]

Builds the engine adapter (and with --shell the release shell), and serves an unsigned engines.json next to them
on all interfaces. The engine version gets a time suffix, so every run is newer than the installed one.
--serial also forwards the port to that device over adb, so http://localhost:PORT works there without Wi-Fi.
Runs until Ctrl+C.
"""
import argparse
import hashlib
import io
import json
import os
import socket
import subprocess
import sys
import time
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from publish_engine import ABIS, CACHE, MAVEN, SHELL_APK, fetch, prop, shell_version_code  # noqa: E402


def lan_address():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("192.0.2.1", 9))
            return s.getsockname()[0]
        except OSError:
            return None


def entry(port, path, data):
    return {"url": [f"http://{{host}}:{port}{path}"], "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8790)
    ap.add_argument("--serial", help="adb device to forward the port to")
    ap.add_argument("--shell", action="store_true", help="also serve the release shell")
    ap.add_argument("--no-build", action="store_true", help="serve what is already built")
    a = ap.parse_args()

    gecko = prop("geckoviewVersion")
    revision = int(prop("adapterRevision"))
    base = gecko if revision == 0 else f"{gecko}.{revision}"
    if not a.no_build:
        tasks = [":engine-gecko:adapterPackage"] + ([":app:assembleRelease"] if a.shell else [])
        subprocess.run(["./gradlew", "-q", *tasks], check=True)
    version = f"{base}.{int(time.time())}"
    src = zipfile.ZipFile(f"engine-gecko/build/outputs/engine/gecko-{base}-adapter.zip")
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for item in src.infolist():
            data = src.read(item.filename)
            if item.filename == "engine.json":
                manifest = json.loads(data)
                manifest["version"] = version
                data = json.dumps(manifest).encode()
            z.writestr(item, data)
    files = {"/adapter.zip": out.getvalue()}
    for abi in ABIS:
        url = f"{MAVEN}/geckoview-{abi}/{gecko}/geckoview-{abi}-{gecko}.aar"
        files[f"/{abi}.aar"] = open(fetch(url, os.path.join(CACHE, f"geckoview-{abi}-{gecko}.aar")), "rb").read()
    index = {
        "format": 1, "serial": int(time.time()), "expires": int(time.time()) + 86400,
        "engines": [{
            "id": "gecko-release", "type": "gecko", "api": int(prop("engineApi")), "channel": "release",
            "version": version, "minShell": 1, "adapter": entry(a.port, "/adapter.zip", files["/adapter.zip"]),
            "upstream": {abi: entry(a.port, f"/{abi}.aar", files[f"/{abi}.aar"]) for abi in ABIS},
        }],
    }
    if a.shell:
        files["/antite.apk"] = open(SHELL_APK, "rb").read()
        apk = entry(a.port, "/antite.apk", files["/antite.apk"])
        index["shell"] = {"version": shell_version_code(), "apk": {abi: apk for abi in ABIS}}
    template = json.dumps(index)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            path = self.path.split("?")[0]
            if path == "/engines.json":
                # Download addresses use the host the phone reached us by: localhost over adb or the LAN address.
                host = (self.headers.get("Host") or "localhost").split(":")[0]
                body = template.replace("{host}", host).encode()
            else:
                body = files.get(path)
            if body is None:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            print(f"  {self.client_address[0]} {path}", flush=True)

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("0.0.0.0", a.port), Handler)
    print(f"engine {version}" + (f", shell {shell_version_code()}" if a.shell else ""), flush=True)
    if a.serial:
        subprocess.run(["adb", "-s", a.serial, "reverse", f"tcp:{a.port}", f"tcp:{a.port}"], check=True)
        print(f"  on {a.serial}: http://localhost:{a.port}/engines.json", flush=True)
    lan = lan_address()
    if lan:
        print(f"  in the local network: http://{lan}:{a.port}/engines.json (the firewall must let the port in)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
