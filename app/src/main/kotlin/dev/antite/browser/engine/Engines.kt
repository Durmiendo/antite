package dev.antite.browser.engine

import android.content.Context
import android.content.Intent
import android.os.Process
import dev.antite.browser.MainActivity
import dev.antite.engine.Engine
import dev.antite.engine.EngineFactory
import org.json.JSONObject
import java.io.File

object Engines {
    fun store(context: Context) = EngineStore(context.filesDir)

    fun create(context: Context): Engine? {
        val store = store(context)
        val dir = store.active() ?: return null
        store.countStart()
        val factory = JSONObject(File(dir, "engine.json").readText()).getString("factory")
        val cls = try {
            Class.forName(factory, true, context.classLoader)
        } catch (e: ClassNotFoundException) {
            return null
        }
        return (cls.getDeclaredConstructor().newInstance() as EngineFactory).create(context)
    }

    // The engine class loader is chosen when a process starts, so a new engine needs a fresh process.
    fun restart(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        Process.killProcess(Process.myPid())
    }
}
