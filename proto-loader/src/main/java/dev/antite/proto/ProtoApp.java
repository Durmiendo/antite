package dev.antite.proto;

import android.app.Application;

import java.io.File;

public class ProtoApp extends Application {
    // Gecko passes this path as -greomni and reads assets/omni.ja from it.
    @Override
    public String getPackageResourcePath() {
        File omni = new File(ProtoFactory.engineDir(getApplicationInfo()), "gecko.zip");
        return omni.exists() ? omni.getPath() : super.getPackageResourcePath();
    }
}
