package dev.antite.browser

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StrictMode

object StrictModeSetup {
    fun enableIfDebuggable(c: Context) {
        if (c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build(),
        )
    }
}
