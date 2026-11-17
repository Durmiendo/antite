"""Secure storage as a file manager: sorting, selection (long press, taps, select all, back), moving a selection
with the toolbar and by dragging it onto a folder, renaming, details and deleting from the viewer.

Usage: python3 -I tools/e2e/vault_explorer.py --serial HOST:PORT --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

Latin names only (adb input text). Leaves the sort on "newest first" and no file or folder behind.
"""
import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from device import PACKAGE, Device  # noqa: E402
from nav import long_press  # noqa: E402
from vault import file_action, open_vault, row_actions, new_folder  # noqa: E402



def desc(d, text):
    node = next((n for n in d.nodes() if n.get("content-desc") == text), None)
    if node is None:
        d.fail(f"no button {text!r}")
    x, y = d.center(node)
    d.shell(f"input tap {x} {y}")
    time.sleep(1)


def top_left_first(d, names):
    """The names in the order the grid shows them: row by row, left to right."""
    found = [(d.bounds(n)[1] // 50, d.bounds(n)[0], n.get("text")) for n in d.nodes() if n.get("text") in names]
    return [t for _, _, t in sorted(found)]


def search(d, text):
    field = next(n for n in d.nodes() if n.get("class") == "android.widget.EditText")
    x, y = d.center(field)
    d.shell(f"input tap {x} {y}")
    d.shell("input keyevent 123 " + " ".join(["67"] * 30))
    if text:
        d.type(text)
    d.shell("input keyevent 4")
    time.sleep(1)


def purge(d, prefix):
    """Files an earlier run left behind: found by name, all selected, deleted."""
    search(d, prefix)
    files = [n.get("text") for n in d.nodes()
             if (n.get("text") or "").startswith(prefix) and n.get("class") != "android.widget.EditText"]
    if files:
        long_press(d, files[0])
        desc(d, "Выделить всё")
        desc(d, "Удалить")
        d.tap("Удалить", exact=True)
        time.sleep(1.5)
    search(d, "")


def sort_by(d, label):
    desc(d, "Сортировка")
    d.tap(label)
    time.sleep(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--out", default="build/e2e")
    ap.add_argument("--test-apk", required=True)
    a = ap.parse_args()
    d = Device(a.serial, a.out)
    stamp = int(time.time()) % 100000
    a_, b_, c_ = (f"ex-{stamp}-{x}.png" for x in "abc")
    renamed = f"ex-{stamp}-renamed.png"
    FOLDER = f"Box{stamp}"
    d.install(a.test_apk)
    try:
        d.stop()
        d.launch()
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(2)
        # What earlier runs left, and the sort they may have left on "by name" (the choice is kept).
        open_vault(d)
        for prefix in ("vm-", "ex-", "dbg-"):
            purge(d, prefix)
        sort_by(d, "Сначала новые")
        for n in (a_, b_, c_):
            d.shell(f"am start -a android.intent.action.SEND -t image/png "
                    f"--eu android.intent.extra.STREAM content://{PACKAGE}.test.share/{n} -n {PACKAGE}/.ShareToVault")
            time.sleep(2)
        open_vault(d)
        new_folder(d, FOLDER)
        d.shell("input keyevent 4")
        time.sleep(1)

        print("1. newest first, then by name")
        if top_left_first(d, [a_, b_, c_]) != [c_, b_, a_]:
            d.fail(f"not newest first: {top_left_first(d, [a_, b_, c_])}")
        sort_by(d, "По названию")
        if top_left_first(d, [a_, b_, c_]) != [a_, b_, c_]:
            d.fail(f"not by name: {top_left_first(d, [a_, b_, c_])}")
        sort_by(d, "Сначала новые")

        print("2. selection: a long press, taps, select all, back")
        # The search narrows the grid to this run's files, so "select all" does not depend on what else is there.
        search(d, f"ex-{stamp}")
        long_press(d, a_)
        d.wait("Выбрано: 1", exact=True)
        d.tap(b_, exact=True)
        d.wait("Выбрано: 2", exact=True)
        desc(d, "Выделить всё")
        d.wait("Выбрано: 3", exact=True)
        d.tap(c_, exact=True)
        d.wait("Выбрано: 2", exact=True)
        d.shell("input keyevent 4")
        d.wait("Защищённое хранилище", exact=True)
        if d.find("Выбрано:") is not None:
            d.fail("back did not end the selection")
        search(d, "")

        print("3. a selected file moved with the toolbar")
        long_press(d, a_)
        d.wait("Выбрано: 1", exact=True)
        desc(d, "В папку…")
        d.tap(FOLDER, exact=True)
        time.sleep(1.5)
        if d.find(a_, exact=True) is not None:
            d.fail("the moved file is still on the top")

        print("4. a file dragged onto the folder")
        src = d.find(b_, exact=True)
        dst = d.find(FOLDER, exact=True)
        x1, y1 = d.center(src)
        x2, y2 = d.center(dst)
        d.shell(f"input draganddrop {x1} {y1 - 150} {x2} {y2} 1500")
        time.sleep(1.5)
        if d.find(b_, exact=True) is not None:
            d.fail("the dragged file is still on the top")
        d.tap(FOLDER, exact=True)
        time.sleep(1)
        d.wait(a_, exact=True)
        d.wait(b_, exact=True)
        # A folder inside a folder, from "+": it opens there, under the path of both.
        new_folder(d, "Inner")
        d.wait("В этой папке пока нет файлов", exact=True)
        d.wait("Inner", exact=True)
        d.wait(FOLDER, exact=True)
        d.shell("input keyevent 4")
        time.sleep(1)
        d.wait("Inner", exact=True)
        d.shell("input keyevent 4")
        time.sleep(1)

        print("5. renamed")
        file_action(d, c_, "Переименовать")
        time.sleep(1)
        # Select all and delete: a run of DELs through adb loses some when the field is not ready yet.
        d.shell("input keycombination 113 29")
        d.shell("input keyevent 67")
        d.type(renamed)
        d.tap("Сохранить", exact=True)
        d.wait(renamed, exact=True)
        # The dialog's scrim takes taps until it has faded out.
        time.sleep(1.5)

        print("6. the viewer: details, then deleting the open file closes it")
        d.tap(renamed, exact=True)
        d.wait("1 / ", timeout=10)
        desc(d, "Ещё")
        d.tap("Сведения", exact=True)
        d.wait("Тип: image/png")
        d.tap("Закрыть", exact=True)
        desc(d, "Ещё")
        d.tap("Удалить", exact=True)
        d.tap("Удалить", exact=True)
        time.sleep(2)
        if d.find(renamed, exact=True) is not None:
            d.fail("the deleted file is still shown")
    finally:
        d.stop()
        d.launch()
        d.wait("Меню", timeout=30, exact=True)
        time.sleep(2)
        open_vault(d)
        purge(d, f"ex-{stamp}")
        if d.find(FOLDER, exact=True) is not None:
            row_actions(d, FOLDER, "Удалить папку")
            if d.find("Удалить", exact=True) is not None:
                d.tap("Удалить", exact=True)
    print("OK")


if __name__ == "__main__":
    main()
