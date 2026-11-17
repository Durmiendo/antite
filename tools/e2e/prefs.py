"""Browser settings written directly, as JSON, instead of tapping through the settings pages: much faster, for the
set-up of a script. Needs the androidTest APK installed. The browser is stopped; launch it after."""
import json

from device import PACKAGE


def set_prefs(d, values):
    """values: key -> bool, int, str, or None to remove the key."""
    text = json.dumps(values, ensure_ascii=True)
    if "'" in text:
        raise ValueError("no single quotes in settings values")
    out = d.shell(f"am instrument -w -e class {PACKAGE}.e2etools.PrefsTool#apply -e json '{text}' "
                  f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner")
    if "OK (1 test)" not in out:
        d.fail(f"settings not written: {out[-300:]}")
    d.stop()


def no_tabs(d):
    """The browser starts with no tabs: counts in a script do not depend on what earlier runs left."""
    out = d.shell(f"am instrument -w -e class {PACKAGE}.e2etools.PrefsTool#noTabs -e tabs none "
                  f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner")
    if "OK (1 test)" not in out:
        d.fail(f"tabs not reset: {out[-300:]}")
    d.stop()
