package dev.antite.browser

import android.app.Application
import android.util.Log
import dev.antite.browser.engine.Engines
import dev.antite.browser.l10n.L10n
import dev.antite.browser.transfer.DataTransfer

class AntiteApp : Application() {
    private val engineResources: String? by lazy {
        val store = Engines.store(this)
        store.active()?.let { store.resources(it).path }
    }

    override fun onCreate() {
        super.onCreate()
        // Before anything opens the keys or the data; the tab processes never touch them.
        if (getProcessName() == packageName) runCatching { DataTransfer.recover(this) }.onFailure { Log.e("Antite", "transfer not recovered", it) }
        L10n.init(this)
    }

    // Gecko reads omni.ja and built-in extensions from the package resource path.
    override fun getPackageResourcePath(): String = engineResources ?: super.getPackageResourcePath()
}
