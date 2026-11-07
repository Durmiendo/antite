package dev.antite.browser;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.StrictMode;

final class StrictModeSetup {
    private StrictModeSetup() {
    }

    static void enableIfDebuggable(Context c) {
        if ((c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build());
    }
}
