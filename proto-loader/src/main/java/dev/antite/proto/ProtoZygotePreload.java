package dev.antite.proto;

import android.app.ZygotePreload;
import android.content.pm.ApplicationInfo;
import android.util.Log;

public class ProtoZygotePreload implements ZygotePreload {
    @Override
    public void doPreload(ApplicationInfo ai) {
        try {
            ClassLoader engine = ProtoFactory.engineLoader(ai, getClass().getClassLoader().getParent());
            ProtoFactory.zygoteLoader = engine;
            ProtoFactory.zygoteState = "engine loader built";
            Log.i(ProtoFactory.TAG, "app zygote: engine loader ready");
            Object gecko = engine.loadClass("org.mozilla.gecko.process.ZygotePreload").getDeclaredConstructor().newInstance();
            ((ZygotePreload) gecko).doPreload(ai);
            Log.i(ProtoFactory.TAG, "app zygote: Gecko preload done");
        } catch (Throwable t) {
            ProtoFactory.zygoteState = "failed: " + t;
            Log.e(ProtoFactory.TAG, "app zygote preload failed", t);
        }
    }
}
