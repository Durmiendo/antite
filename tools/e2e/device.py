import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "dev.antite.browser"


class Device:
    def __init__(self, serial, out_dir):
        self.serial = serial
        self.out_dir = out_dir
        os.makedirs(out_dir, exist_ok=True)

    def adb(self, *args, check=True, binary=False):
        r = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True)
        if check and r.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.decode(errors='replace')}")
        return r.stdout if binary else r.stdout.decode(errors="replace")

    def shell(self, cmd, check=True):
        return self.adb("shell", cmd, check=check)

    def nodes(self):
        for _ in range(3):
            out = self.shell("uiautomator dump /data/local/tmp/e2e-ui.xml >/dev/null && cat /data/local/tmp/e2e-ui.xml", check=False)
            start = out.find("<?xml")
            if start >= 0:
                try:
                    return list(ET.fromstring(out[start:]).iter("node"))
                except ET.ParseError:
                    pass
            time.sleep(0.5)
        return []

    @staticmethod
    def _matches(node, text, exact, attr):
        v = node.get(attr) or ""
        return (v == text) if exact else (text.lower() in v.lower())

    def find_class(self, cls):
        for n in self.nodes():
            if n.get("class") == cls:
                return n
        return None

    def find(self, text, exact=False):
        nodes = self.nodes()
        for attr in ("text", "content-desc"):
            for n in nodes:
                if self._matches(n, text, exact, attr):
                    return n
        return None

    @staticmethod
    def center(node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        return (x1 + x2) // 2, (y1 + y2) // 2

    def wait(self, text, timeout=15, exact=False):
        end = time.time() + timeout
        while time.time() < end:
            n = self.find(text, exact)
            if n is not None:
                return n
            time.sleep(0.4)
        self.fail(f"not found on screen: {text!r}")

    @staticmethod
    def bounds(node):
        return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))

    def tap(self, text, timeout=15, exact=False):
        node = self.wait(text, timeout, exact)
        x, y = self.center(node)
        x1, y1, x2, y2 = self.bounds(node)
        containers = [n for n in self.nodes() if n.get("clickable") == "true"
                      and (b := self.bounds(n))[0] <= x1 and b[1] <= y1 and b[2] >= x2 and b[3] >= y2]
        if containers:
            b = min((self.bounds(n) for n in containers), key=lambda b: (b[2] - b[0]) * (b[3] - b[1]))
            x, y = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
        self.shell(f"input tap {x} {y}")
        time.sleep(0.4)

    def scroll_to(self, text, swipes=8):
        for _ in range(swipes):
            n = self.find(text)
            if n is not None:
                return n
            self.shell("input swipe 540 1900 540 700 250")
            time.sleep(0.6)
        self.fail(f"not found after scrolling: {text!r}")

    def type(self, value):
        self.shell("input text " + value.replace(" ", "%s"))

    def top_activity(self):
        out = self.shell("dumpsys activity activities | grep topResumedActivity", check=False)
        return out.strip()

    def fail(self, message):
        path = os.path.join(self.out_dir, f"fail-{int(time.time())}.png")
        with open(path, "wb") as f:
            f.write(self.adb("exec-out", "screencap", "-p", binary=True))
        print(f"FAIL: {message}\n  top: {self.top_activity()}\n  screenshot: {path}", file=sys.stderr)
        sys.exit(1)

    def install(self, apk, abi=None):
        self.adb("push", apk, "/data/local/tmp/e2e.apk")
        force = f"--abi {abi} " if abi else ""
        self.shell("rm -f /data/local/tmp/e2e-install.log; "
                   f"nohup pm install -r -t {force}/data/local/tmp/e2e.apk >/data/local/tmp/e2e-install.log 2>&1 &")
        for _ in range(240):
            log = self.shell("cat /data/local/tmp/e2e-install.log", check=False).strip()
            if log:
                if "Success" not in log:
                    raise RuntimeError("install failed: " + log)
                return
            time.sleep(2.5)
        raise RuntimeError("install timed out")

    def delete_media(self, name):
        self.shell(f"content delete --uri content://media/external/file --where \"_display_name='{name}'\"", check=False)

    def stop(self):
        self.shell(f"am force-stop {PACKAGE}")

    def launch(self, url=None):
        cmd = f"am start -n {PACKAGE}/.MainActivity"
        if url:
            cmd = f"am start -a android.intent.action.VIEW -d '{url}' -n {PACKAGE}/.MainActivity"
        self.shell(cmd)
