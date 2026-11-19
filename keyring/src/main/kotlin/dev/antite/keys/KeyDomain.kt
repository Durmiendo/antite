package dev.antite.keys

// A domain of the key ring: one data key, sealed in the file of its name (decision 0017: not a bare String).
@JvmInline
value class KeyDomain(val name: String) {
    override fun toString() = name
}
