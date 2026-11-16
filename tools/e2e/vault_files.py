"""Secure storage, files from outside: a picture shared by another app lands in the storage and opens there; a file
picked with "Добавить файлы" lands in the open folder; folders filter the grid; nothing in the storage folder is
readable on the disk; the files are still there after the app is restarted.

Usage: python3 -I tools/e2e/vault_files.py --serial HOST:PORT --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

Waydroid has no screen lock, so the storage opens without an unlock prompt. Latin names only: adb input text cannot
type Cyrillic. Leaves no file or folder behind.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from downloads import media_size  # noqa: E402
from media_vault import open_vault, row_actions  # noqa: E402
from tab_categories import to_browser  # noqa: E402

FOLDER = "Docs"


def push(d, out, name, data):
    path = os.path.join(out, name)
    with open(path, "wb") as f:
        f.write(data)
    d.adb("push", path, f"/sdcard/Download/{name}")
    d.shell(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/{name}", check=False)
    end = time.time() + 20
    while media_size(d, name) != len(data):
        if time.time() > end:
            d.fail(f"{name} did not appear in the media store")
        time.sleep(1)
    out = d.shell(f"content query --uri content://media/external/downloads --projection _id "
                  f"--where \"_display_name='{name}'\"")
    return "content://media/external/downloads/" + out.split("_id=")[1].split()[0].rstrip(",")


def delete_from_vault(d, name):
    if d.find(name, exact=True) is None:
        return
    row_actions(d, name, "Удалить")
    d.tap("Удалить", exact=True)
    time.sleep(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    ap.add_argument("--test-apk", required=True)
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    stamp = int(time.time()) % 100000
    marker = f"MARKER-{stamp}-" + "plain text that must not lie on the disk " * 20
    picture, note = f"vf-{stamp}.png", f"vf-{stamp}.txt"
    push(d, a.out, note, marker.encode())
    d.install(a.test_apk)
    try:
        d.stop()
        d.launch()
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(2)

        print("1. a picture shared by another app lands in the storage and opens there")
        # adb shell cannot grant access to a media store file; the test APK's provider plays the other app.
        d.shell(f"am start -a android.intent.action.SEND -t image/png "
                f"--eu android.intent.extra.STREAM content://{PACKAGE}.test.share/{picture} -n {PACKAGE}/.ShareToVault")
        time.sleep(4)
        open_vault(d)
        d.tap(picture, exact=True)
        d.wait("1 / ", timeout=10)
        d.shell("input keyevent BACK")
        time.sleep(1)

        print("2. a file picked with the add button lands in the open folder")
        d.tap("Новая папка", exact=True)
        d.type(FOLDER)
        d.tap("Сохранить", exact=True)
        time.sleep(1)
        d.wait("В этой папке пока нет файлов", exact=True)
        add = [n for n in d.nodes() if n.get("content-desc") == "Добавить файлы"]
        x, y = d.center(add[0])
        d.shell(f"input tap {x} {y}")
        time.sleep(3)
        d.tap(note, timeout=20)
        d.wait(FOLDER, timeout=20, exact=True)
        d.wait(note, timeout=20, exact=True)
        if d.find(picture, exact=True) is not None:
            d.fail("the folder shows a file that is not in it")
        d.tap("Все", exact=True)
        time.sleep(1)
        for name in (picture, note):
            d.wait(name, exact=True)
        print(f"   the picked original is {'still there' if media_size(d, note) is not None else 'deleted'}")

        print("3. nothing in the storage folder is readable on the disk")
        d.install(a.test_apk)
        out = d.shell(f"am instrument -w -e class {PACKAGE}.VaultDiskTool -e marker MARKER-{stamp} "
                      f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner", check=False)
        if "OK (1 test)" not in out:
            d.fail("storage files on the disk: " + out)

        print("4. after a restart the files are in place and the note is still in its folder")
        d.launch()
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(2)
        open_vault(d)
        d.tap(FOLDER, exact=True)
        time.sleep(1)
        d.wait(note, exact=True)
        if d.find(picture, exact=True) is not None:
            d.fail("the folder shows a file that is not in it after the restart")
    finally:
        d.stop()
        d.launch()
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(2)
        open_vault(d)
        if d.find(FOLDER, exact=True) is not None:
            x, y = d.center(d.find(FOLDER, exact=True))
            d.shell(f"input swipe {x} {y} {x} {y} 900")
            time.sleep(1)
            d.tap("Удалить папку", exact=True)
            d.tap("Удалить", exact=True)
            time.sleep(1)
        for name in (picture, note):
            delete_from_vault(d, name)
        to_browser(d)
        for name in (picture, note):
            d.delete_media(name)
            d.shell(f"rm -f /sdcard/Download/{name}", check=False)
    print("OK")


if __name__ == "__main__":
    main()
