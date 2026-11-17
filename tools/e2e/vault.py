"""Secure storage and downloads screens, and the key ring password dialog."""
import time

from nav import long_press, open_settings, to_browser


def media_size(d, name):
    out = d.shell(f"content query --uri content://media/external/downloads --projection _display_name:_size "
                  f"--where \"_display_name='{name}'\"", check=False)
    return int(out.split("_size=")[1].split()[0].rstrip(",")) if "_size=" in out else None


def open_vault(d):
    to_browser(d)
    d.tap("Меню", exact=True)
    d.tap("Защищённое хранилище", exact=True)
    time.sleep(1)
    # Without a screen lock the unlock button opens the storage at once.
    if d.find("Разблокировать", exact=True) is not None:
        d.tap("Разблокировать", exact=True)
        time.sleep(1)


def open_downloads(d):
    to_browser(d)
    d.tap("Меню", exact=True)
    d.tap("Скачанные файлы", exact=True)
    time.sleep(1)


def row_actions(d, name, action):
    """Long press on a row and pick an action; a press that lands in the list's enter animation is repeated."""
    for _ in range(3):
        long_press(d, name)
        if d.find(action, exact=True) is not None:
            d.tap(action, exact=True)
            return
    d.fail(f"no {action!r} on the long press of {name!r}")


def file_action(d, name, action):
    """A file of the storage: a long press selects it, "Ещё" of the selection holds every action."""
    for _ in range(3):
        long_press(d, name)
        more = [n for n in d.nodes() if n.get("content-desc") == "Ещё"]
        if more:
            x, y = d.center(more[0])
            d.shell(f"input tap {x} {y}")
            time.sleep(0.5)
            if d.find(action, exact=True) is not None:
                d.tap(action, exact=True)
                return
            d.shell("input keyevent 4")
    d.fail(f"no {action!r} for the selected {name!r}")


def add_menu(d, item):
    """"+" at the top of the storage, then one of its choices."""
    plus = [n for n in d.nodes() if n.get("content-desc") == "Добавить"]
    if not plus:
        d.fail("no add button")
    x, y = d.center(plus[0])
    d.shell(f"input tap {x} {y}")
    time.sleep(0.5)
    d.tap(item, exact=True)


def new_folder(d, name):
    """A folder in the open one, through "+"."""
    add_menu(d, "Новая папка")
    d.type(name)
    d.tap("Сохранить", exact=True)
    time.sleep(1)


def wait_media(d, name, size, what, timeout=20):
    end = time.time() + timeout
    while media_size(d, name) != size:
        if time.time() > end:
            d.fail(what)
        time.sleep(1)


PASSWORD = "e2eSecret42"


def type_into(d, field, text):
    d.tap(field, exact=True)
    time.sleep(0.5)
    d.type(text)
    time.sleep(0.3)


def pick(d, row, option):
    d.tap(row, exact=True)
    time.sleep(1)
    d.tap(option, exact=True)
    time.sleep(1)


def password_dialog(d, text, twice=False):
    d.wait("Пароль", exact=True, timeout=10)
    type_into(d, "Пароль", text)
    if twice:
        # With the keyboard up uiautomator does not list the dialog; Tab moves to the second field, Back only hides the
        # keyboard (Escape would close the dialog too).
        d.shell("input keyevent 61")
        time.sleep(0.3)
        d.type(text)
    d.shell("input keyevent 4")
    time.sleep(0.8)
    d.tap("OK", exact=True)


def open_keys(d):
    to_browser(d)
    open_settings(d)
    d.tap("Безопасность", exact=True)
    time.sleep(1)
    d.scroll_to("Шифрование и ключи")
    d.tap("Шифрование и ключи", exact=True)
    time.sleep(1)


def reset(d, keep_vault=False):
    """Back to the device key and AES, whatever an earlier run left."""
    to_browser(d)
    if d.find("Пароль Antite") is not None:
        password_dialog(d, PASSWORD)
    open_keys(d)
    if d.find("Каскад XChaCha20 + AES", exact=True) is not None or d.find("XChaCha20-Poly1305", exact=True) is not None:
        pick(d, "Шифр", "AES-256-GCM")
        if d.find("Пароль Antite") is not None:
            password_dialog(d, PASSWORD)
    if d.find("стирание гарантировано") is None or d.find("Устройство", exact=True) is None:
        pick(d, "Ключ", "Устройство")
        time.sleep(1)
        if d.find("Пароль Antite") is not None:
            password_dialog(d, PASSWORD)
    d.wait("стирание гарантировано", timeout=30)
    d.wait("Устройство", exact=True)
    to_browser(d)
