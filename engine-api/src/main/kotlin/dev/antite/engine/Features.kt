package dev.antite.engine

data class Extension(val id: String, val name: String, val optionsUrl: String?, val hasAction: Boolean)

interface Extensions {
    fun list(): List<Extension>

    fun ensureInstalled(id: String, url: String)

    fun clickAction(id: String)

    fun setTabActive(tab: EngineTab, active: Boolean)

    fun setPopupHandler(handler: (create: (TabCallbacks) -> EngineTab) -> Unit)

    fun setChangeListener(listener: () -> Unit)
}

data class Language(val code: String, val name: String)

data class LanguageModel(val language: Language, val downloaded: Boolean, val size: Long)

interface Translation {
    fun supported(done: (from: List<Language>, to: List<Language>) -> Unit, failed: () -> Unit)

    fun models(done: (List<LanguageModel>) -> Unit, failed: () -> Unit)

    fun downloadModel(code: String, done: (Boolean) -> Unit)

    fun deleteModel(code: String, done: (Boolean) -> Unit)

    fun neverTranslateSites(done: (List<String>) -> Unit)

    fun allowTranslateSite(site: String, done: () -> Unit)
}
