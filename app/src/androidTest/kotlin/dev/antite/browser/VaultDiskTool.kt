package dev.antite.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// Not a test of its own: lets an e2e script look at the secure storage folder, which adb cannot read. Fails if any
// file there holds the marker text in the open or is not an encrypted one.
//   am instrument -w -e class dev.antite.browser.VaultDiskTool -e marker TEXT ...
@RunWith(AndroidJUnit4::class)
class VaultDiskTool {
    @Test
    fun noPlainContent() {
        val marker = InstrumentationRegistry.getArguments().getString("marker")
        assumeTrue("run only from e2e scripts with -e marker", marker != null)
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "vault")
        val files = dir.listFiles().orEmpty()
        assertTrue("no encrypted files in ${dir.path}: ${files.map { it.name }}", files.any { it.name == "index.box" } && files.size > 1)
        for (f in files) {
            assertTrue("${f.name} is not an encrypted file (${f.length()} bytes, modified ${java.util.Date(f.lastModified())}), all: ${files.map { it.name }}", f.name.endsWith(".enc") || f.name.endsWith(".thumb") || f.name == "index.box")
            assertFalse("${f.name} holds the marker in the open", String(f.readBytes(), Charsets.ISO_8859_1).contains(marker!!))
        }
    }
}
