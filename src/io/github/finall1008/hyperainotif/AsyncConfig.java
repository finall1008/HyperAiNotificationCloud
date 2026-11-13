package io.github.finall1008.hyperainotif;

import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** State is owned by the handler; only the provider query runs on the executor. */
final class AsyncConfig implements Supplier<Bundle> {
    private final Handler handler;
    private final Executor executor;
    private final Supplier<Bundle> query;
    private final Runnable updated;
    private Bundle cached = new Bundle();
    private boolean loading;
    private boolean dirty;

    AsyncConfig(Handler handler, Executor executor, Supplier<Bundle> query, Runnable updated) {
        this.handler = handler;
        this.executor = executor;
        this.query = query;
        this.updated = updated;
    }

    @Override
    public Bundle get() {
        return cached;
    }

    void refresh() {
        if (loading) {
            dirty = true;
            return;
        }
        loading = true;
        executor.execute(() -> {
            Bundle result = null;
            try {
                result = query.get();
            } catch (Exception e) {
                Log.w("HyperAiNotifCloud", "Failed to refresh configuration", e);
            }
            Bundle value = result;
            handler.post(() -> {
                loading = false;
                if (dirty) {
                    dirty = false;
                    refresh();
                    return;
                }
                if (value != null) cached = new Bundle(value);
                updated.run();
            });
        });
    }
}
