package dev.antite.browser.engine

import android.app.AppComponentFactory
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File

class ShellFactory : AppComponentFactory() {
    override fun instantiateClassLoader(cl: ClassLoader, ai: ApplicationInfo): ClassLoader {
        val store = EngineStore(File(ai.dataDir, "files"))
        // Child processes of a running browser must keep its engine, so only a fresh main process switches.
        if (Application.getProcessName() == ai.packageName) {
            runCatching { store.applyPending() }.onFailure { Log.w("Antite", "cannot switch engine", it) }
        }
        val engine = store.active() ?: return cl
        val dex = store.dexFiles(engine).joinToString(File.pathSeparator) { it.path }
        val libs = listOf(store.libDir(engine).path, ai.nativeLibraryDir).joinToString(File.pathSeparator)
        Log.i("Antite", "engine ${engine.name}")
        return DexClassLoader(dex, null, libs, cl)
    }

    // Providers are created at process start, before the setup screen can download an engine.
    override fun instantiateProvider(cl: ClassLoader, className: String): ContentProvider = try {
        super.instantiateProvider(cl, className)
    } catch (e: ClassNotFoundException) {
        Log.w("Antite", "no engine yet, $className replaced by an empty provider")
        EmptyProvider()
    }

    class EmptyProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
    }
}
