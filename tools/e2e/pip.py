"""Picture-in-picture: a playing video in full screen goes to a PiP window with its own aspect ratio when the user
leaves the app; a paused video or a full-screen page without video does not.

Usage: python3 -I tools/e2e/pip.py --serial HOST:PORT

Needs ffmpeg on the host (the test video is made on the fly) and an engine with engine API 2.
"""
import argparse
import base64
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from server import ReportServer  # noqa: E402

PORT = 8773
W, H = 480, 204


def video(out_dir):
    path = os.path.join(out_dir, "pip.webm")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", f"testsrc=size={W}x{H}:rate=15",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "30",
                    "-c:v", "libvpx", "-b:v", "150k", "-c:a", "libopus", "-b:a", "32k", path], check=True)
    with open(path, "rb") as f:
        return base64.b64encode(f.read()).decode()


def page(data):
    return f"""<!doctype html><meta name=viewport content="width=device-width">
<style>body{{margin:0}} button{{display:block;font-size:28px;width:100%;height:22vh;margin:0}} video{{width:100%}}</style>
<button id=v onclick="go(true)">video</button><button id=p onclick="go(false)">paused</button>
<button id=d onclick="document.getElementById('box').requestFullscreen().then(() => r('full'))">div</button>
<div id=box style="background:#468;height:10vh"></div>
<video id=vid loop src="data:video/webm;base64,{data}"></video>
<script>
const vid = document.getElementById('vid');
function r(s) {{ fetch('/report?' + s); }}
async function go(play) {{
  await vid.play();
  // Gecko reports a full-screen video only once its media session is active, i.e. after it became audible.
  await new Promise(ok => setTimeout(ok, 1500));
  if (!play) vid.pause();
  await vid.requestFullscreen();
  r('full');
}}
addEventListener('resize', () => r('size=' + innerWidth + 'x' + innerHeight));
</script>""".encode()


def pinned(d):
    out = d.shell("dumpsys activity activities", check=False)
    return any(f":{PACKAGE} " in line and "mode=pinned" in line for line in out.splitlines() if "* Task{" in line)


def wait_pinned(d, want, timeout=8):
    end = time.time() + timeout
    while time.time() < end:
        if pinned(d) == want:
            return True
        time.sleep(0.5)
    return False


def last_size(server, timeout=4):
    size = None
    while (s := server.report(timeout=timeout)) is not None:
        timeout = 1
        if s.startswith("size="):
            size = tuple(map(int, s[5:].split("x")))
    return size


# Gecko's accessibility bounds ignore the toolbar offset, so the page buttons are tapped by position.
BUTTONS = {"video": 450, "paused": 890, "div": 1330}


def start(d, server, button):
    d.stop()
    d.launch(f"http://localhost:{PORT}/page")
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(3)
    while server.report(timeout=0.1):
        pass
    d.shell(f"input tap 540 {BUTTONS[button]}")
    if server.report(timeout=10) != "full":
        d.fail(f"{button}: page did not enter full screen")
    time.sleep(1.5)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    server = ReportServer(PORT, {"/page": page(video(a.out))})
    server.attach(d)
    try:
        print("1. playing full-screen video + Home -> PiP with the video's aspect ratio")
        start(d, server, "video")
        d.shell("input keyevent HOME")
        if not wait_pinned(d, True):
            d.fail("no PiP after Home")
        size = last_size(server)
        if size is None:
            d.fail("page was not resized in PiP")
        ratio = size[0] / size[1]
        print(f"   PiP page {size[0]}x{size[1]}, ratio {ratio:.2f}, video {W / H:.2f}")
        if abs(ratio - W / H) > 0.08:
            d.fail(f"PiP ratio {ratio:.2f} != video {W / H:.2f}")

        print("2. back to the app -> PiP ends, the page stays in full screen")
        d.launch()
        if not wait_pinned(d, False):
            d.fail("still in PiP after returning")
        time.sleep(1.5)
        if d.find("Меню", exact=True) is not None:
            d.fail("toolbar shown: page left full screen")

        print("3. paused full-screen video + Home -> no PiP")
        start(d, server, "paused")
        d.shell("input keyevent HOME")
        if wait_pinned(d, True, timeout=4):
            d.fail("PiP for a paused video")

        print("4. full-screen element without video + Home -> no PiP")
        start(d, server, "div")
        d.shell("input keyevent HOME")
        if wait_pinned(d, True, timeout=4):
            d.fail("PiP for a full-screen page without video")
    finally:
        d.stop()
        server.close()
    print("OK")


if __name__ == "__main__":
    main()
