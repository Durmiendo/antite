package dev.antite.browser

import android.app.Application
import dev.antite.browser.engine.Engines
import dev.antite.browser.l10n.L10n

class AntiteApp : Application() {
    private val engineResources: String? by lazy {
        val store = Engines.store(this)
        store.active()?.let { store.resources(it).path }
    }

    override fun onCreate() {
        super.onCreate()
        L10n.init(this)
    }

    // Gecko reads omni.ja and built-in extensions from the package resource path.
    override fun getPackageResourcePath(): String = engineResources ?: super.getPackageResourcePath()
}
