package dev.antite.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import kotlin.random.Random

class SyllablePhraseTest {
    private val random = Random(20261119)

    private fun secret(length: Int) = random.nextBytes(SyllablePhrase.secretBytes(length))

    private fun ok(text: String): SyllablePhrase.Read.Ok {
        val r = SyllablePhrase.read(text)
        assertTrue("$text -> $r", r is SyllablePhrase.Read.Ok)
        return r as SyllablePhrase.Read.Ok
    }

    @Test
    fun fieldIsWhole() = assertTrue(SyllablePhrase.Rs.primitive())

    @Test
    fun everySyllableValueRoundTrips() {
        val seen = HashSet<String>()
        for (v in 0..0xffff) {
            val s = SyllablePhrase.syllable(v)
            assertEquals(v, SyllablePhrase.value(s))
            assertFalse(s.contains('l') || s.contains('o'))
            assertTrue(seen.add(s))
        }
    }

    @Test
    fun secretSizesFollowTheLength() {
        assertEquals(listOf(12, 20, 28, 44), SyllablePhrase.LENGTHS.map(SyllablePhrase::secretBytes))
        assertThrows(IllegalArgumentException::class.java) { SyllablePhrase.secretBytes(15) }
        assertThrows(IllegalArgumentException::class.java) { SyllablePhrase.encode(ByteArray(13)) }
        assertThrows(IllegalArgumentException::class.java) { SyllablePhrase.encode(ByteArray(26)) }
    }

    @Test
    fun roundTripsEveryLength() {
        for (n in SyllablePhrase.LENGTHS) repeat(50) {
            val s = secret(n)
            val words = SyllablePhrase.encode(s)
            assertEquals(n, words.size)
            val r = ok(words.joinToString(" "))
            assertArrayEquals(s, r.secret)
            assertNull(r.fixed)
        }
    }

    @Test
    fun generatedPhrasesRead() {
        for (n in SyllablePhrase.LENGTHS) assertNull(ok(SyllablePhrase.generate(n, SecureRandom()).joinToString(" ")).fixed)
    }

    @Test
    fun anyCaseAndSeparators() {
        val s = secret(8)
        val words = SyllablePhrase.encode(s)
        val messy = words.mapIndexed { i, w -> if (i % 2 == 0) w.uppercase() else w }.joinToString(" -\n,\t")
        assertArrayEquals(s, ok("  $messy. ").secret)
    }

    @Test
    fun wrongCountIsNotAPhrase() {
        val words = SyllablePhrase.encode(secret(12))
        val r = SyllablePhrase.read(words.dropLast(1).joinToString(" "))
        assertEquals(11, (r as SyllablePhrase.Read.WrongLength).words)
        assertEquals(0, (SyllablePhrase.read("") as SyllablePhrase.Read.WrongLength).words)
    }

    // A typo in one letter spoils one syllable: every syllable, check ones too, every other value of every letter.
    @Test
    fun oneWrongLetterAnywhereIsPutRight() {
        val letters = "abcdefghijklmnopqrstuvwxyz"
        for (n in SyllablePhrase.LENGTHS) {
            val s = secret(n)
            val words = SyllablePhrase.encode(s)
            for (pos in 0 until n) for (ch in 0 until 5) for (l in letters) {
                if (l == words[pos][ch]) continue
                val typo = words.toMutableList()
                typo[pos] = typo[pos].substring(0, ch) + l + typo[pos].substring(ch + 1)
                val r = ok(typo.joinToString(" "))
                assertArrayEquals("n=$n pos=$pos ${typo[pos]}", s, r.secret)
                assertEquals(pos, r.fixed)
            }
        }
    }

    @Test
    fun oneWholeSyllableWrongIsPutRight() {
        for (n in SyllablePhrase.LENGTHS) repeat(200) {
            val s = secret(n)
            val words = SyllablePhrase.encode(s).toMutableList()
            val pos = random.nextInt(n)
            val was = words[pos]
            while (words[pos] == was) words[pos] = SyllablePhrase.syllable(random.nextInt(0x10000))
            val r = ok(words.joinToString(" "))
            assertArrayEquals(s, r.secret)
            assertEquals(pos, r.fixed)
        }
    }

    @Test
    fun syllableDroppedOrAddedIsAWrongLength() {
        val words = SyllablePhrase.encode(secret(16))
        assertTrue(SyllablePhrase.read((words + "babab").joinToString(" ")) is SyllablePhrase.Read.WrongLength)
    }

    @Test
    fun twoUnreadableSyllablesAreRefused() {
        val words = SyllablePhrase.encode(secret(12)).toMutableList()
        words[2] = "xxxxx"; words[9] = "qqqqq"
        assertSame(SyllablePhrase.Read.TooManyWrong, SyllablePhrase.read(words.joinToString(" ")))
    }

    @Test
    fun oneUnreadableAndOneWrongAreRefused() {
        for (n in SyllablePhrase.LENGTHS) repeat(300) {
            val words = SyllablePhrase.encode(secret(n)).toMutableList()
            val a = random.nextInt(n)
            var b = random.nextInt(n)
            while (b == a) b = random.nextInt(n)
            words[a] = "xxxxx"
            val was = words[b]
            while (words[b] == was) words[b] = SyllablePhrase.syllable(random.nextInt(0x10000))
            assertSame(SyllablePhrase.Read.TooManyWrong, SyllablePhrase.read(words.joinToString(" ")))
        }
    }

    // Two wrong syllables: the code finds nearly all of them; what it takes for one wrong syllable is put "right" into
    // another secret, which opens nothing. Measured rate is about n / 65535.
    @Test
    fun twoWrongSyllablesNeverGiveTheSecretBack() {
        for (n in SyllablePhrase.LENGTHS) {
            var missed = 0
            val rounds = 20000
            repeat(rounds) {
                val s = secret(n)
                val words = SyllablePhrase.encode(s).toMutableList()
                val a = random.nextInt(n)
                var b = random.nextInt(n)
                while (b == a) b = random.nextInt(n)
                for (p in intArrayOf(a, b)) {
                    val was = words[p]
                    while (words[p] == was) words[p] = SyllablePhrase.syllable(random.nextInt(0x10000))
                }
                when (val r = SyllablePhrase.read(words.joinToString(" "))) {
                    is SyllablePhrase.Read.Ok -> { missed++; assertFalse(s.contentEquals(r.secret)) }
                    else -> assertSame(SyllablePhrase.Read.TooManyWrong, r)
                }
            }
            assertTrue("n=$n missed $missed of $rounds", missed.toDouble() / rounds < 3.0 * n / 65535 + 0.0005)
        }
    }

    // The format is shared with other builds: a fixed secret must keep giving these syllables.
    @Test
    fun knownPhrase() {
        val s = ByteArray(12) { it.toByte() }
        assertEquals(GOLDEN_8, SyllablePhrase.encode(s).joinToString(" "))
        assertArrayEquals(s, ok(GOLDEN_8).secret)
    }

    private companion object {
        const val GOLDEN_8 = "hubiv babad banag bebaj benam bibap binas pafiw"
    }
}
