package dev.antite.browser.engine

object Abi {
    // Native libraries load into this process, so its architecture matters, not every ABI the device supports.
    val current: String? get() = ofArch(System.getProperty("os.arch") ?: "")

    fun ofArch(arch: String): String? = when (arch.lowercase()) {
        "aarch64", "arm64" -> "arm64-v8a"
        "x86_64", "amd64" -> "x86_64"
        "armv7l", "armv8l", "arm" -> "armeabi-v7a"
        "i686", "i386", "x86" -> "x86"
        else -> null
    }

    fun elfMatches(header: ByteArray, abi: String): Boolean {
        if (header.size < 20 || header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte() || header[5] != 1.toByte()
        ) return false
        val elfClass = header[4].toInt()
        val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        return when (abi) {
            "arm64-v8a" -> elfClass == 2 && machine == 183
            "x86_64" -> elfClass == 2 && machine == 62
            "armeabi-v7a" -> elfClass == 1 && machine == 40
            "x86" -> elfClass == 1 && machine == 3
            else -> false
        }
    }
}
