package dev.antite.browser.e2etools

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Not a test: writes the browser's settings for e2e scripts in one go, instead of tapping through the settings pages.
// true/false become booleans, whole numbers ints, text strings; null removes the key. The browser reads them on its
// next start, so the script restarts it.
//   am instrument -w -e class dev.antite.browser.e2etools.PrefsTool -e json '{"key": value}' ...
@RunWith(AndroidJUnit4::class)
class PrefsTool {
    @Test
    fun apply() {
        val json = InstrumentationRegistry.getArguments().getString("json")
        assumeTrue("run only from e2e scripts with -e json", json != null)
        val o = JSONObject(json!!)
        val sp = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("antite", Context.MODE_PRIVATE)
        val e = sp.edit()
        for (k in o.keys()) {
            when (val v = o.get(k)) {
                JSONObject.NULL -> e.remove(k)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is String -> e.putString(k, v)
                else -> throw IllegalArgumentException("$k: unsupported value $v")
            }
        }
        check(e.commit()) { "settings not written" }
    }

    // No tabs on the next start: a script that counts tabs needs a known number, not what earlier runs left.
    //   am instrument -w -e class dev.antite.browser.e2etools.PrefsTool#noTabs -e tabs none ...
    @Test
    fun noTabs() {
        assumeTrue("run only from e2e scripts with -e tabs none", InstrumentationRegistry.getArguments().getString("tabs") == "none")
        val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        File(files, "tabs.json").delete()
        File(files, "thumbs").deleteRecursively()
    }
}
