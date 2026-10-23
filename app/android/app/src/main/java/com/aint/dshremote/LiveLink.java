package com.aint.dshremote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one background link to the plugin. It owns the SSE connection so the UI and the
 * foreground service observe the same stream instead of opening competing sockets.
 */
public final class LiveLink {

    public interface Listener {
        void onFrame(JSONObject frame);

        void onConnection(boolean connected);
    }

    private static final LiveLink INSTANCE = new LiveLink();

    public static LiveLink get() {
        return INSTANCE;
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Thread thread;
    private volatile boolean connected;
    private volatile boolean appVisible;

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void setAppVisible(boolean visible) {
        appVisible = visible;
    }

    public boolean appVisible() {
        return appVisible;
    }

    public boolean connected() {
        return connected;
    }

    public synchronized void start(Context context) {
        if (thread != null) {
            return;
        }

        Prefs prefs = new Prefs(context);
        if (prefs.token().isEmpty()) {
            return;
        }
        Api api = new Api(prefs.server(), prefs.token());

        thread = new Thread(() -> api.streamEvents(
                () -> main.post(() -> {
                    connected = true;
                    for (Listener listener : listeners) {
                        listener.onConnection(true);
                    }
                }),
                frame -> main.post(() -> {
                    for (Listener listener : listeners) {
                        listener.onFrame(frame);
                    }
                })));
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void restart(Context context) {
        stop();
        start(context);
    }

    public synchronized void stop() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        connected = false;
        main.post(() -> {
            for (Listener listener : listeners) {
                listener.onConnection(false);
            }
        });
    }
}
