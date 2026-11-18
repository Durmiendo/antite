package dev.antite.keys

import java.security.SecureRandom

// The key phrase: 8, 12, 16 or 24 syllables of five lowercase letters, consonant-vowel-consonant-vowel-consonant, each
// one 16 bits. Two of them, the first and the eighth, are Reed-Solomon check syllables over GF(2^16); the rest carry
// the secret. Any one syllable written wrong, a check syllable included, is put right. Two wrong ones are almost
// always found to be wrong; the rare pair the code takes for one wrong syllable gives a key that opens nothing, so the
// phrase is still refused, only later. The letters leave out l and o, which pass for 1, I and 0.
object SyllablePhrase {
    val LENGTHS = intArrayOf(8, 12, 16, 24)
    const val CHECKS = 2
    val CHECK_AT = intArrayOf(0, 7)

    private const val CONSONANTS = "bdfghjkmnprstvwz"
    private const val VOWELS = "aeiu"

    fun secretBytes(length: Int): Int {
        require(length in LENGTHS) { "a phrase has ${LENGTHS.joinToString("/")} syllables, not $length" }
        return (length - CHECKS) * 2
    }

    sealed interface Read {
        // fixed: the position of the syllable put right, or null.
        class Ok(val secret: ByteArray, val fixed: Int?) : Read
        sealed interface Wrong : Read
        class WrongLength(val words: Int) : Wrong
        object TooManyWrong : Wrong
    }

    fun generate(length: Int, random: SecureRandom = SecureRandom()): List<String> {
        val secret = ByteArray(secretBytes(length)).also(random::nextBytes)
        try {
            return encode(secret)
        } finally {
            secret.fill(0)
        }
    }

    fun syllable(v: Int): String {
        require(v in 0..0xffff)
        return buildString {
            append(CONSONANTS[v ushr 12 and 15]); append(VOWELS[v ushr 10 and 3])
            append(CONSONANTS[v ushr 6 and 15]); append(VOWELS[v ushr 4 and 3]); append(CONSONANTS[v and 15])
        }
    }

    // The 16 bits of a syllable, or -1 if it is not one.
    fun value(s: String): Int {
        if (s.length != 5) return -1
        val c1 = CONSONANTS.indexOf(s[0]); val v1 = VOWELS.indexOf(s[1]); val c2 = CONSONANTS.indexOf(s[2])
        val v2 = VOWELS.indexOf(s[3]); val c3 = CONSONANTS.indexOf(s[4])
        if (c1 < 0 || v1 < 0 || c2 < 0 || v2 < 0 || c3 < 0) return -1
        return c1 shl 12 or (v1 shl 10) or (c2 shl 6) or (v2 shl 4) or c3
    }

    // The secret's length picks the phrase's: 12, 20, 28 or 44 bytes.
    fun encode(secret: ByteArray): List<String> {
        require(secret.size % 2 == 0 && secret.size / 2 + CHECKS in LENGTHS) { "no phrase carries ${secret.size} bytes" }
        val n = secret.size / 2 + CHECKS
        val data = IntArray(n - CHECKS) { (secret[2 * it].toInt() and 0xff shl 8) or (secret[2 * it + 1].toInt() and 0xff) }
        val code = Rs(n).encode(data)
        data.fill(0)
        val order = order(n)
        return try {
            List(n) { syllable(code[order[it]]) }
        } finally {
            code.fill(0)
        }
    }

    // The words as written, in any case and with any separators.
    fun read(text: String): Read {
        val words = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        val n = words.size
        if (n !in LENGTHS) return Read.WrongLength(n)
        val shown = words.map(::value)
        val unknown = shown.indices.filter { shown[it] < 0 }
        if (unknown.size > 1) return Read.TooManyWrong
        val order = order(n)
        val code = IntArray(n)
        for (pos in 0 until n) code[order[pos]] = maxOf(shown[pos], 0)
        val fixedAt = Rs(n).correct(code, unknown.map { order[it] }) ?: run { code.fill(0); return Read.TooManyWrong }
        val secret = ByteArray((n - CHECKS) * 2)
        for (j in 0 until n - CHECKS) {
            secret[2 * j] = (code[j] ushr 8).toByte(); secret[2 * j + 1] = code[j].toByte()
        }
        code.fill(0)
        return Read.Ok(secret, fixedAt.takeIf { it >= 0 }?.let { order.indexOf(it) })
    }

    // Where each written syllable sits in the code word: data first, the two checks last.
    private fun order(n: Int): IntArray {
        val out = IntArray(n)
        var d = 0
        for (pos in 0 until n) out[pos] = when (pos) {
            CHECK_AT[0] -> n - CHECKS
            CHECK_AT[1] -> n - CHECKS + 1
            else -> d++
        }
        return out
    }

    // Reed-Solomon over GF(2^16) (x^16 + x^12 + x^3 + x + 1), n symbols of which the last two are checks: the code
    // word c(x) has the roots a and a^2. Symbol i of the word is the coefficient of x^(n - 1 - i).
    internal class Rs(private val n: Int) {
        // c(x) evaluated at a^k.
        private fun syndrome(c: IntArray, k: Int): Int {
            var s = 0
            for (i in c.indices) s = s xor mul(c[i], pow(k * (n - 1 - i)))
            return s
        }

        fun encode(data: IntArray): IntArray {
            require(data.size == n - CHECKS)
            val c = IntArray(n)
            System.arraycopy(data, 0, c, 0, n - CHECKS)
            // The checks p1 (x^1) and p0 (x^0) solve p1 a + p0 = s1, p1 a^2 + p0 = s2.
            val s1 = syndrome(c, 1)
            val s2 = syndrome(c, 2)
            val p1 = div(s1 xor s2, pow(1) xor pow(2))
            c[n - 2] = p1
            c[n - 1] = s1 xor mul(p1, pow(1))
            check(syndrome(c, 1) == 0 && syndrome(c, 2) == 0)
            return c
        }

        // Puts the word right in place. erasure: the one symbol known to be unreadable, if any. Returns the index put
        // right, -1 if the word was whole, null if it cannot be put right.
        fun correct(c: IntArray, erasure: List<Int>): Int? {
            require(c.size == n && erasure.size <= 1)
            val s1 = syndrome(c, 1)
            val s2 = syndrome(c, 2)
            val at: Int
            val e: Int
            if (erasure.isEmpty()) {
                if (s1 == 0 && s2 == 0) return -1
                if (s1 == 0 || s2 == 0) return null
                // e X = s1, e X^2 = s2: X = s2 / s1, e = s1 / X.
                val p = log[div(s2, s1)]
                if (p > n - 1) return null
                at = n - 1 - p
                e = div(s1, pow(p))
            } else {
                // The erasure is the only wrong symbol, so the second syndrome must agree with the first.
                at = erasure[0]
                val x = pow(n - 1 - at)
                e = div(s1, x)
                if (mul(e, mul(x, x)) != s2) return null
            }
            c[at] = c[at] xor e
            return at
        }

        companion object {
            private const val POLY = 0x1100B
            private const val N = 65535
            private val exp = IntArray(2 * N)
            private val log = IntArray(N + 1)

            init {
                var x = 1
                for (i in 0 until N) {
                    exp[i] = x
                    log[x] = i
                    x = x shl 1
                    if (x and 0x10000 != 0) x = x xor POLY
                }
                for (i in N until 2 * N) exp[i] = exp[i - N]
            }

            // The table holds every non-zero element exactly once: the polynomial is primitive.
            internal fun primitive(): Boolean = exp.take(N).toSet().size == N

            fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
            fun div(a: Int, b: Int): Int {
                require(b != 0)
                return if (a == 0) 0 else exp[log[a] + N - log[b]]
            }
            private fun pow(e: Int) = exp[((e % N) + N) % N]
        }
    }
}
