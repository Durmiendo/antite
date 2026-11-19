package dev.antite.browser.data

// Ids that cannot be passed for one another (decision 0017); at run time each is the bare value inside it.

// A category id names its history log and its key file, so it is checked to be a plain file name part.
@JvmInline
value class CategoryId(val value: String) {
    init {
        require(PLAIN.matches(value)) { "bad category id" }
    }

    override fun toString() = value

    companion object {
        private val PLAIN = Regex("[A-Za-z0-9_-]{1,64}")

        fun parse(s: String?): CategoryId? = s?.takeIf { PLAIN.matches(it) }?.let(::CategoryId)
    }
}

@JvmInline
value class ContainerId(val value: String) {
    init {
        require(value.isNotEmpty()) { "empty container id" }
    }

    override fun toString() = value

    companion object {
        fun parse(s: String?): ContainerId? = s?.takeIf { it.isNotEmpty() }?.let(::ContainerId)
    }
}

@JvmInline
value class TabId(val value: Long) {
    override fun toString() = value.toString()
}

@JvmInline
value class VaultFileId(val value: Long) {
    override fun toString() = value.toString()
}

@JvmInline
value class VaultFolderId(val value: Long) {
    override fun toString() = value.toString()
}
