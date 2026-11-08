package dev.antite.browser.engine

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

object ShellInstaller {
    private fun apkFile(context: Context, offer: ShellOffer) =
        File(context.cacheDir, "shell").apply { mkdirs() }.resolve("antite-${offer.version}.apk")

    // Downloads and verifies the APK, then hands it to the system installer, which asks the user to confirm.
    fun install(context: Context, offer: ShellOffer, progress: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        val apk = apkFile(context, offer)
        File(context.cacheDir, "shell").listFiles()?.filter { it != apk }?.forEach { it.delete() }
        downloadVerified(EngineUpdates.fetcher, offer.apk, apk) { progress(it, offer.apk.size) }
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context, id, Intent(context, ShellInstallReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(status.intentSender)
        }
        EngineUpdates.cancelNotification(context, EngineUpdates.NOTIFY_SHELL)
    }
}

class ShellInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("Antite", "shell update failed: $status $message")
                EngineUpdates.state(context).edit()
                    .putString("lastError", "новая версия Antite не установилась: ${message ?: status}").apply()
            }
        }
    }
}
