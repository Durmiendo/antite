"""Getting around the interface: browser, tab switcher, settings pages."""
import io
import time

from PIL import Image


def to_browser(d):
    """Back until the toolbar shows; counting presses breaks when a page pops itself or the keyboard takes one."""
    for _ in range(6):
        if d.find("Меню", exact=True) is not None:
            return
        d.shell("input keyevent BACK")
        time.sleep(1)
    d.fail("could not get back to the browser")


def open_switcher(d):
    count = [n for n in d.nodes() if ((t := n.get("text") or "").isdigit() or t == ":D") and d.bounds(n)[1] < 230]
    if not count:
        d.fail("no tab count in the toolbar")
    x, y = d.center(count[0])
    d.shell(f"input tap {x} {y}")
    time.sleep(1.5)


def open_tabs_settings(d):
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    time.sleep(1)
    d.tap("Настройки", exact=True)
    d.wait("Поиск по настройкам", exact=True)
    time.sleep(1)
    d.tap("Вкладки", exact=True)
    time.sleep(1)


def long_press(d, text):
    x, y = d.center(d.wait(text, exact=True))
    d.shell(f"input swipe {x} {y} {x} {y} 900")
    time.sleep(1)


def open_settings(d):
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    d.tap("Настройки", exact=True)
    d.wait("Поиск по настройкам", exact=True)
    time.sleep(1)


def open_settings_page(d, page):
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    d.tap("Настройки", exact=True)
    d.wait("Поиск по настройкам", exact=True)
    time.sleep(1)
    d.tap(page, exact=True)
    time.sleep(1)


def back_to_page(d, times):
    for _ in range(times):
        d.shell("input keyevent BACK")
        time.sleep(1)
    d.wait("Меню", exact=True)


def open_updates(d):
    d.stop()
    d.launch()
    # A cold start can be slow; Back before the page is up would send the browser to the background.
    end = time.time() + 20
    while time.time() < end and d.find("Меню", exact=True) is None:
        time.sleep(1)
    for _ in range(4):
        if d.find("Меню", exact=True) is not None:
            break
        time.sleep(3)
        # An update offer may cover the toolbar; Back dismisses it without postponing.
        d.shell("input keyevent BACK")
    d.wait("Меню", timeout=30, exact=True)
    time.sleep(1)
    d.tap("Меню", exact=True)
    d.scroll_to("Настройки")
    d.tap("Настройки", exact=True)
    d.scroll_to("О программе")
    d.tap("О программе", exact=True)
    d.tap("Обновления", exact=True)


def toolbar_bottom(d):
    """Where the dark toolbar ends on screen: the page is white, so the first light pixel below the status bar."""
    img = Image.open(io.BytesIO(d.adb("exec-out", "screencap", "-p", binary=True))).convert("L")
    x = img.width // 2
    y = 0
    while y < img.height and img.getpixel((x, y)) < 200:
        y += 1
    return y


def chip_count(d, name):
    """The number drawn right of a strip chip's name, or None."""
    nodes = d.nodes()
    label = next((n for n in nodes if n.get("text") == name and d.bounds(n)[1] < 260), None)
    if label is None:
        return None
    x1, y1, x2, y2 = d.bounds(label)
    for n in nodes:
        t = n.get("text") or ""
        bx1, by1, bx2, by2 = d.bounds(n)
        # A hundred tabs and more are drawn as ":D".
        if (t.isdigit() or t == ":D") and abs((by1 + by2) - (y1 + y2)) < 20 and 0 <= bx1 - x2 < 60:
            return int(t) if t.isdigit() else 100
    return None


def set_each_count(d, on):
    """The switch row exposes no checked state, so the strip tells whether counts are drawn."""
    open_switcher(d)
    shown = chip_count(d, "Все") is not None
    d.shell("input keyevent BACK")
    time.sleep(1)
    if shown == on:
        return
    open_tabs_settings(d)
    d.scroll_to("Число вкладок у каждой категории")
    d.tap("Число вкладок у каждой категории", exact=True)
    to_browser(d)


def open_category_settings(d):
    open_tabs_settings(d)
    d.scroll_to("Мои категории")
    d.tap("Мои категории", exact=True)
    time.sleep(1)
