package dev.antite.keys

import java.io.IOException

// What locks the data keys of a ring: the device, a password, or both.
enum class KeyMode(val id: Int) {
    DEVICE(1), PASSWORD(2), BOTH(3);

    val usesPassword get() = this != DEVICE
    val usesDevice get() = this != PASSWORD

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: throw IOException("unknown key mode $id")
    }
}
