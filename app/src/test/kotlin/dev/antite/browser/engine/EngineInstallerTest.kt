package dev.antite.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class EngineInstallerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var keys: KeyPair
    private val files = mutableMapOf<String, ByteArray>()
    private val fetcher = Fetcher { url -> files[url]?.inputStream() ?: throw FileNotFoundException(url) }
    private val omni = ByteArray(300_000) { (it * 31).toByte() }

    @Before
    fun setUp() {
        keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun adapter(version: String = "1.0") = zip(
        "engine.json" to """{"type":"gecko","api":1,"version":"$version","factory":"x.Factory"}""".toByteArray(),
        "classes.dex" to byteArrayOf(1, 2, 3),
        "assets/extensions/icons/manifest.json" to "{}".toByteArray(),
    )

    private fun elf(abi: String): ByteArray {
        val (cls, machine) = mapOf("x86_64" to (2 to 62), "arm64-v8a" to (2 to 183), "x86" to (1 to 3))[abi] ?: (1 to 40)
        return byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), cls.toByte(), 1) +
            ByteArray(12) + byteArrayOf(machine.toByte(), 0) + ByteArray(44)
    }

    private fun aar(abi: String = "x86_64", libAbi: String = abi) = zip(
        "classes.jar" to byteArrayOf(9),
        "jni/$abi/libxul.so" to elf(libAbi),
        "jni/$abi/libmozglue.so" to elf(libAbi),
        "jni/arm64-v8a/libother.so" to elf("arm64-v8a"),
        "assets/omni.ja" to omni,
    )

    private fun artifact(name: String, bytes: ByteArray, mirrors: List<Pair<String, ByteArray>> = emptyList()): String {
        val urls = mirrors.map { (u, b) -> files[u] = b; u } + "https://host/$name".also { files[it] = bytes }
        return """{"url":[${urls.joinToString(",") { "\"$it\"" }}],"size":${bytes.size},"sha256":"${sha(bytes)}"}"""
    }

    private fun index(serial: Long = 5, adapterJson: String = artifact("a.zip", adapter()), aarJson: String = artifact("e.aar", aar())) =
        """{"format":1,"serial":$serial,"engines":[{"id":"gecko-release","type":"gecko","api":1,"channel":"release",
           "version":"1.0","minShell":1,"adapter":$adapterJson,"upstream":{"x86_64":$aarJson}}]}""".toByteArray()

    private fun sign(b: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(b); sign() }

    private fun parse(json: ByteArray, lastSerial: Long = 0) = EngineIndex.verify(json, sign(json), keys.public, lastSerial)

    @Test
    fun validIndexIsAccepted() {
        val entry = parse(index()).choose("x86_64", api = 1, shellVersion = 1)
        assertNotNull(entry)
        assertEquals("gecko-release-1.0-x86_64", entry!!.dirName("x86_64"))
    }

    @Test
    fun shellEntryIsParsed() {
        assertNull(parse(index()).shell)
        val apk = artifact("antite.apk", byteArrayOf(1, 2, 3))
        val json = String(index()).replace("\"engines\":", "\"shell\":{\"version\":7,\"apk\":{\"x86_64\":$apk}},\"engines\":")
        val shell = parse(json.toByteArray()).shell!!
        assertEquals(7, shell.version)
        assertEquals(3L, shell.apk.getValue("x86_64").size)
        assertNull(shell.apk["arm64-v8a"])
    }

    @Test
    fun tamperedIndexIsRejected() {
        val json = index()
        val sig = sign(json)
        val tampered = String(json).replace("\"serial\":5", "\"serial\":6").toByteArray()
        assertThrows(IndexException::class.java) { EngineIndex.verify(tampered, sig, keys.public, 0) }
    }

    @Test
    fun indexSignedByAnotherKeyIsRejected() {
        val json = index()
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        assertThrows(IndexException::class.java) { EngineIndex.verify(json, sign(json), other.public, 0) }
    }

    @Test
    fun olderIndexIsRejected() {
        assertThrows(IndexException::class.java) { parse(index(serial = 4), lastSerial = 5) }
    }

    @Test
    fun choiceRespectsAbiApiAndShellVersion() {
        val i = parse(index())
        assertNull(i.choose("arm64-v8a", 1, 1))
        assertNull(i.choose("x86_64", 2, 1))
        assertNull(parse(String(index()).replace("\"minShell\":1", "\"minShell\":3").toByteArray()).choose("x86_64", 1, 2))
    }

    @Test
    fun installLaysOutTheEngine() {
        val entry = parse(index()).choose("x86_64", 1, 1)!!
        var last = 0L
        val dir = EngineInstaller(tmp.root, fetcher).install(entry, "x86_64") { done, total -> last = done; assertTrue(done <= total) }

        assertEquals(entry.dirName("x86_64"), dir.name)
        assertEquals("x86_64", EngineStore.abiOf(dir))
        assertTrue(java.io.File(dir, "classes.dex").exists())
        assertTrue(java.io.File(dir, "engine.json").exists())
        assertEquals(setOf("libxul.so", "libmozglue.so"), java.io.File(dir, "lib").list()!!.toSet())
        assertTrue(java.io.File(dir, EngineStore.READY).exists())
        assertFalse("downloads are removed", java.io.File(dir, "engine.aar").exists())
        assertFalse("files are read-only", java.io.File(dir, "classes.dex").canWrite())
        ZipFile(java.io.File(dir, EngineStore.RESOURCES)).use { z ->
            val o = z.getEntry("assets/omni.ja")
            assertEquals("omni.ja must be stored for Gecko", ZipEntry.STORED, o.method)
            assertTrue(z.getInputStream(o).readBytes().contentEquals(omni))
            assertNotNull(z.getEntry("assets/extensions/icons/manifest.json"))
        }
        assertTrue(last > 0)
        assertEquals(listOf(entry.dirName("x86_64")), tmp.root.list()!!.toList())
    }

    @Test
    fun badMirrorFallsBackToTheNextAddress() {
        val good = aar()
        val json = index(aarJson = artifact("e.aar", good, mirrors = listOf("https://mirror/e.aar" to aar("broken"))))
        val dir = EngineInstaller(tmp.root, fetcher).install(parse(json).choose("x86_64", 1, 1)!!, "x86_64") { _, _ -> }
        assertTrue(java.io.File(dir, "lib/libxul.so").exists())
    }

    @Test
    fun hashMismatchLeavesNothingBehind() {
        val json = index()
        val entry = parse(json).choose("x86_64", 1, 1)!!
        files["https://host/e.aar"] = aar("x86_64") + byteArrayOf(0)
        assertThrows(IOException::class.java) { EngineInstaller(tmp.root, fetcher).install(entry, "x86_64") { _, _ -> } }
        assertEquals(emptyList<String>(), tmp.root.list()!!.toList())
    }

    @Test
    fun librariesForAnotherArchitectureAreRejected() {
        val json = index(aarJson = artifact("e.aar", aar("x86_64", libAbi = "arm64-v8a")))
        val entry = parse(json).choose("x86_64", 1, 1)!!
        assertThrows(IOException::class.java) { EngineInstaller(tmp.root, fetcher).install(entry, "x86_64") { _, _ -> } }
        assertEquals(emptyList<String>(), tmp.root.list()!!.toList())
    }

    @Test
    fun engineOfAnotherArchitectureIsNotActive() {
        val files = tmp.newFolder("files")
        val dir = EngineInstaller(EngineStore(files, "x86_64").dir, fetcher)
            .install(parse(index()).choose("x86_64", 1, 1)!!, "x86_64") { _, _ -> }
        EngineStore(files, "x86_64").activate(dir)
        assertEquals(dir, EngineStore(files, "x86_64").active())
        assertNull(EngineStore(files, "arm64-v8a").active())
    }

    @Test
    fun processArchitectureMapsToAbi() {
        assertEquals("arm64-v8a", Abi.ofArch("aarch64"))
        assertEquals("x86_64", Abi.ofArch("amd64"))
        assertEquals("armeabi-v7a", Abi.ofArch("armv8l"))
        assertNull(Abi.ofArch("mips"))
        assertTrue(Abi.elfMatches(elf("arm64-v8a"), "arm64-v8a"))
        assertFalse(Abi.elfMatches(elf("arm64-v8a"), "x86_64"))
        assertFalse(Abi.elfMatches(elf("x86"), "x86_64"))
        assertFalse(Abi.elfMatches(byteArrayOf(7, 7), "x86_64"))
    }

    @Test
    fun adapterForAnotherVersionIsRejected() {
        val json = index(adapterJson = artifact("a.zip", adapter(version = "2.0")))
        val entry = parse(json).choose("x86_64", 1, 1)!!
        assertThrows(IOException::class.java) { EngineInstaller(tmp.root, fetcher).install(entry, "x86_64") { _, _ -> } }
    }

    @Test
    fun pathEscapingEntryIsRejected() {
        val evil = zip("engine.json" to """{"type":"gecko","api":1,"version":"1.0"}""".toByteArray(), "../evil.dex" to byteArrayOf(1))
        val entry = parse(index(adapterJson = artifact("a.zip", evil))).choose("x86_64", 1, 1)!!
        assertThrows(IOException::class.java) { EngineInstaller(tmp.root, fetcher).install(entry, "x86_64") { _, _ -> } }
        assertFalse(java.io.File(tmp.root.parentFile, "evil.dex").exists())
    }

    @Test
    fun activationPointsAtTheInstalledEngine() {
        val store = EngineStore(tmp.newFolder("files"), "x86_64")
        assertNull(store.active())
        val dir = EngineInstaller(store.dir, fetcher).install(parse(index()).choose("x86_64", 1, 1)!!, "x86_64") { _, _ -> }
        assertNull("installing does not activate", store.active())
        store.activate(dir)
        assertEquals(dir, store.active())
        assertEquals(listOf("classes.dex"), store.dexFiles(dir).map { it.name })
    }
}
