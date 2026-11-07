package dev.antite.proto;

import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.DexClassLoader;

public class ProtoFactory extends AppComponentFactory {
    static final String TAG = "AntiteProto";
    static final File SOURCE = new File("/data/local/tmp/antite-engine");

    static File engineDir(ApplicationInfo ai) {
        return new File(ai.dataDir, "files/engine");
    }

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo ai) {
        File dir = engineDir(ai);
        try {
            install(dir);
        } catch (IOException e) {
            Log.e(TAG, "engine install failed", e);
            return cl;
        }
        List<String> dex = new ArrayList<>();
        dex.add(ai.sourceDir);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".dex"));
        if (files != null) for (File f : files) dex.add(f.getPath());
        String libs = new File(dir, "lib").getPath() + File.pathSeparator + ai.nativeLibraryDir;
        Log.i(TAG, "engine class loader: " + dex + " libs=" + libs);
        return new DexClassLoader(String.join(File.pathSeparator, dex), null, libs, cl.getParent());
    }

    private static synchronized void install(File dir) throws IOException {
        File ready = new File(dir, "ready");
        if (ready.exists()) return;
        if (!SOURCE.isDirectory()) throw new IOException("no engine at " + SOURCE);
        copyTree(SOURCE, dir);
        if (!ready.createNewFile()) throw new IOException("cannot mark engine ready");
    }

    private static void copyTree(File from, File to) throws IOException {
        if (!to.isDirectory() && !to.mkdirs()) throw new IOException("mkdir " + to);
        File[] children = from.listFiles();
        if (children == null) throw new IOException("cannot list " + from);
        for (File c : children) {
            File t = new File(to, c.getName());
            if (c.isDirectory()) {
                copyTree(c, t);
                continue;
            }
            try (InputStream in = new FileInputStream(c); OutputStream out = new FileOutputStream(t)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            // Android 14 refuses to load writable dex files.
            if (!t.setReadOnly()) throw new IOException("cannot make read-only: " + t);
        }
    }
}
