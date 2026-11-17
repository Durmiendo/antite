"""The engine index and the engine download, as e2e scripts need them."""
import base64
import glob
import hashlib
import json
import os
import subprocess
import time

from device import PACKAGE
from nav import open_updates


def prop(name):
    for line in open("gradle.properties"):
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    raise KeyError(name)


KEYS = os.path.expanduser("~/.antite-keys")


def artifact(port, path, data):
    return {"url": [f"http://localhost:{port}{path}"], "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def download_engine(d, port):
    """From a browser without an engine: open the setup screen, download, wait for the browser."""
    d.stop()
    d.shell(f"am start -n {PACKAGE}/.MainActivity --es indexUrl http://localhost:{port}/engines.json")
    # The download starts by itself; the steps of the first start are gone through meanwhile.
    for _ in range(40):
        if d.find("Разрешить", exact=True) is not None:
            d.tap("Разрешить", exact=True)
            break
        if d.find("Скачиваем и проверяем…") is not None or d.find("Движок готов") is not None:
            break
        time.sleep(0.5)
    # The progress notification lives only while the engine downloads, which starts with the first step.
    def notified():
        return "Движок Gecko " + prop("geckoviewVersion") in d.shell("dumpsys notification --noredact", check=False)
    seen = notified()
    for _ in range(3):
        d.tap("Далее", exact=True)
        seen = seen or notified()
    d.tap("Начать", exact=True)
    end = time.time() + 300
    while d.find("Закреплённые", exact=True) is None:
        if time.time() > end:
            d.fail("browser did not start with the new engine")
        seen = seen or "Движок Gecko " + prop("geckoviewVersion") in d.shell("dumpsys notification --noredact", check=False)
    if not seen:
        d.fail("no notification for the engine download")


def engine_pages(port):
    """Files of a local engine source: a signed index, the adapter package and the GeckoView AAR."""
    gecko = prop("geckoviewVersion")
    revision = int(prop("adapterRevision"))
    version = f"{gecko}.{revision}" if revision else gecko
    adapter = open(f"engine-gecko/build/outputs/engine/gecko-{version}-adapter.zip", "rb").read()
    aar_path = glob.glob(os.path.expanduser(f"~/.gradle/caches/modules-2/files-2.1/org.mozilla.geckoview/geckoview/{gecko}/*/geckoview-{gecko}.aar"))[0]
    aar = open(aar_path, "rb").read()
    index = json.dumps({
        "format": 1,
        "serial": int(time.time()),
        "expires": int(time.time()) + 30 * 86400,
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


def signed(out_dir, index):
    """A key of our own and the index signed with it: the key for the source form and the pages to serve."""
    key = os.path.join(out_dir, "foreign.key.pem")
    if not os.path.exists(key):
        subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", key], check=True)
    der = subprocess.run(["openssl", "ec", "-in", key, "-pubout", "-outform", "DER"],
                         capture_output=True, check=True).stdout
    body = json.dumps(index).encode()
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key], input=body, capture_output=True, check=True).stdout
    return base64.b64encode(der).decode(), {"/engines.json": body, "/engines.json.sig": sig}


def enable_debug(d, pin):
    """Turns debug mode on from the browser's main screen: warning, then the device unlock with pin."""
    open_updates(d)
    d.scroll_to("Режим отладки")
    # The row can end up under the navigation bar, where a tap presses Home.
    d.shell("input swipe 540 1900 540 1000 300")
    time.sleep(2)
    # A tap right after the list settles is sometimes taken as stopping the scroll; tap again then.
    for _ in range(3):
        d.tap("Режим отладки", exact=True)
        end = time.time() + 5
        while d.find("Включить", exact=True) is None and time.time() < end:
            time.sleep(0.4)
        if d.find("Включить", exact=True) is not None:
            break
    d.tap("Включить", exact=True)
    time.sleep(2)
    d.type(pin)
    d.shell("input keyevent 66")
    d.wait("Установить с адреса", timeout=10, exact=True)
    d.shell("input swipe 540 1900 540 1000 300")
    time.sleep(2)
