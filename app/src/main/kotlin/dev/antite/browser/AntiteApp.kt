package dev.antite.browser

import android.app.Application
import dev.antite.browser.engine.Engines

class AntiteApp : Application() {
    private val engineResources: String? by lazy {
        val store = Engines.store(this)
        store.active()?.let { store.resources(it).path }
    }

    // Gecko reads omni.ja and built-in extensions from the package resource path.
    override fun getPackageResourcePath(): String = engineResources ?: super.getPackageResourcePath()
}
