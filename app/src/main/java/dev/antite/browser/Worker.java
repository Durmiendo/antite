package dev.antite.browser;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class Worker {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    void run(Runnable work) {
        io.execute(work);
    }

    <T> void read(Supplier<T> query, Consumer<T> onMain) {
        io.execute(() -> {
            T v = query.get();
            main.post(() -> onMain.accept(v));
        });
    }
}
