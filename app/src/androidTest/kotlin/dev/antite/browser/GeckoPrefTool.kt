package dev.antite.browser

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

// Not a test: sets or clears one Gecko pref in the app process for e2e scripts, which have no other way to reach
// Gecko prefs (the engine is loaded from files, Waydroid has no root). The pref is kept in prefs.js.
//   am instrument -w -e class dev.antite.browser.GeckoPrefTool -e pref NAME -e value VALUE|clear [-e type string] ...
@RunWith(AndroidJUnit4::class)
class GeckoPrefTool {
    @Test
    fun apply() {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("pref")
        assumeTrue("run only from e2e scripts with -e pref", name != null)
        val value = args.getString("value") ?: "clear"
        val string = args.getString("type") == "string"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var loader: ClassLoader? = null
            scenario.onActivity { loader = it.browser.engine.javaClass.classLoader }
            val c = loader!!.loadClass("org.mozilla.geckoview.GeckoPreferenceController")
            val known = poll(onMain { c.getMethod("getGeckoPref", String::class.java).invoke(null, name) })
            val type = known!!.javaClass.getField("type").getInt(known)
            assertNotEquals("Gecko does not know $name", c.getField("PREF_TYPE_INVALID").getInt(null), type)
            poll(onMain {
                if (value == "clear") c.getMethod("clearGeckoUserPref", String::class.java).invoke(null, name)
                else c.getMethod("setGeckoPref", String::class.java,
                    if (string) String::class.java else java.lang.Boolean::class.java, Int::class.javaPrimitiveType)
                    .invoke(null, name, if (string) value else value.toBoolean(), c.getField("PREF_BRANCH_USER").getInt(null))
            })
            // Gecko writes prefs.js shortly after a change; the process ends with the instrumentation.
            Thread.sleep(2000)
        }
    }

    // GeckoView takes these calls on a thread with a Handler and answers on it, so the result is awaited here.
    private fun onMain(call: () -> Any?): Any? {
        var r: Any? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { r = call() }
        return r
    }

    private fun poll(result: Any?): Any? = result!!.javaClass.getMethod("poll", Long::class.javaPrimitiveType).invoke(result, 10_000L)
}
