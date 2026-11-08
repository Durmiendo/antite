package dev.antite.engine

class BackgroundService(
    val id: String,
    val title: String,
    val summary: String,
    val hosts: List<String>,
    val defaultEnabled: Boolean,
    val offEffect: String? = null,
) {
    companion object {
        const val SAFE_BROWSING = "safe-browsing"
    }
}
