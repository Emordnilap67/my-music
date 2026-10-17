package io.github.emordnilap67.mymusic;

import android.os.Process;

import java.util.concurrent.ThreadFactory;

final class Low implements ThreadFactory {
    static final Low THREADS = new Low();

    private Low() {}

    @Override
    public Thread newThread(final Runnable r) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                } catch (Throwable ignored) {
                }
                r.run();
            }
        }, "low");
        t.setDaemon(true);
        return t;
    }
}
