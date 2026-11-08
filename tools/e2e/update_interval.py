"""Update check interval: with "once a minute" the open browser asks the update source every minute, and the
background job never runs more often than JobScheduler allows (15 minutes).

Usage: python3 -I tools/e2e/update_interval.py --serial HOST:PORT

Adds a local source with an empty signed index, makes it the update source for the test, and puts back the
previous update source, the interval and the source list at the end. Keeps the browser's data.
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from server import ReportServer  # noqa: E402
from update_source import open_updates  # noqa: E402

PORT = 8779
NAME = "Interval"
MINUTE = "Раз в минуту, пока браузер открыт"
DAY = "Раз в день"


def empty_source(out_dir):
    key = os.path.join(out_dir, "interval.key.pem")
    if not os.path.exists(key):
        subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", key], check=True)
    der = subprocess.run(["openssl", "ec", "-in", key, "-pubout", "-outform", "DER"],
                         capture_output=True, check=True).stdout
    now = int(time.time())
    index = json.dumps({"format": 1, "serial": now, "expires": now + 86400, "engines": []}).encode()
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key], input=index, capture_output=True, check=True).stdout
    return base64.b64encode(der).decode(), {"/engines.json": index, "/engines.json.sig": sig}


def after(d, label):
    """Text of the row below the one titled `label` (its summary)."""
    texts = [n.get("text") for n in d.nodes() if n.get("text")]
    return texts[texts.index(label) + 1]


def pick(d, row, option):
    d.scroll_to(row)
    d.tap(row, exact=True)
    d.tap(option, exact=True)
    time.sleep(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    key, pages = empty_source(a.out)
    server = ReportServer(PORT, pages)
    server.attach(d)

    open_updates(d)
    d.scroll_to("Источник обновлений")
    previous = after(d, "Источник обновлений")
    interval = after(d, "Как часто")
    print(f"update source before: {previous}, interval: {interval}")
    try:
        print("1. a local source becomes the update source")
        d.scroll_to("Источники")
        d.tap("Источники", exact=True)
        d.tap("Добавить источник", exact=True)
        d.tap("Название", exact=True)
        d.type(NAME)
        d.tap("Адрес индекса", exact=False)
        d.type(f"http://localhost:{PORT}/engines.json")
        d.tap("Открытый ключ издателя", exact=True)
        d.type(key)
        d.tap("Далее", exact=True)
        d.tap("Добавить", exact=True)
        d.wait(NAME, exact=True)
        d.shell("input keyevent BACK")
        time.sleep(1)
        pick(d, "Источник обновлений", NAME)
        d.tap("Сменить", exact=True)
        time.sleep(1)
        d.shell("input keyevent BACK")
        time.sleep(1)

        print("2. once a minute: the open browser asks the update source every minute")
        pick(d, "Как часто", MINUTE)
        d.shell("input keyevent BACK")
        time.sleep(1)
        for _ in range(4):
            if d.find("Меню", exact=True) is not None:
                break
            d.shell("input keyevent BACK")
            time.sleep(0.7)
        server.requests.clear()
        time.sleep(135)
        asked = [p for p in server.requests if p == "/engines.json"]
        print(f"   index requests in 135 s: {len(asked)}")
        if len(asked) < 2:
            d.fail(f"update source asked {len(asked)} times in 135 s")

        print("3. the background job stays at 15 minutes")
        jobs = d.shell(f"dumpsys jobscheduler {PACKAGE}")
        m = re.search(r"PERIODIC: interval=\+(\S+)", jobs)
        print(f"   job period: {m.group(1) if m else None}")
        if not m or not m.group(1).startswith("15m"):
            d.fail(f"background job period is {m.group(1) if m else 'missing'}")
    finally:
        print("cleanup")
        open_updates(d)
        pick(d, "Как часто", interval)
        d.shell("input keyevent BACK")
        time.sleep(1)
        if after(d, "Источник обновлений") != previous:
            pick(d, "Источник обновлений", previous)
            d.tap("Сменить", exact=True)
            time.sleep(1)
            d.shell("input keyevent BACK")
            time.sleep(1)
        d.scroll_to("Источники")
        d.tap("Источники", exact=True)
        if d.find(NAME, exact=True) is not None:
            d.tap(NAME, exact=True)
            d.scroll_to("Удалить источник")
            d.tap("Удалить источник", exact=True)
            d.tap("Удалить", exact=True)
            time.sleep(1)
        server.close()
    print("OK")


if __name__ == "__main__":
    main()
