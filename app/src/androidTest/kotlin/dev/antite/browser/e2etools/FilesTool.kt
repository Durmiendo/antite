package dev.antite.browser.e2etools

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Not a test: lets an e2e script see the names in a folder of the app's files, which adb cannot read (run-as does not
// work on Waydroid). Reported as the status line "files=" of am instrument -r, names separated by "/".
//   am instrument -w -r -e class dev.antite.browser.e2etools.FilesTool -e dir history/keys ...
@RunWith(AndroidJUnit4::class)
class FilesTool {
    @Test
    fun list() {
        val dir = InstrumentationRegistry.getArguments().getString("dir")
        assumeTrue("run only from e2e scripts with -e dir", dir != null)
        val names = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, dir!!).list().orEmpty().sorted()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("files", names.joinToString("/")) })
    }
}
