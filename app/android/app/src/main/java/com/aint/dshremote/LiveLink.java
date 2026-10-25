package com.aint.dshremote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
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
    private Context appContext;
    private ConnectivityManager.NetworkCallback networkCallback;
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
        appContext = context.getApplicationContext();
        watchNetwork();
        openLink(prefs.server(), prefs.token());
    }

    private void openLink(String server, String token) {
        Api api = new Api(server, token);
        thread = new Thread(() -> api.streamEvents(
                () -> main.post(() -> {
                    connected = true;
                    for (Listener listener : listeners) {
                        listener.onConnection(true);
                    }
                }),
                // The stream dropped; Api retries on its own, this only updates the UI.
                () -> main.post(() -> {
                    connected = false;
                    for (Listener listener : listeners) {
                        listener.onConnection(false);
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

    /**
     * Reconnect immediately when Wi-Fi comes back instead of waiting for the backoff timer:
     * the common case is a phone that dozed off or roamed between access points.
     */
    private void watchNetwork() {
        if (networkCallback != null || appContext == null) {
            return;
        }
        ConnectivityManager manager =
                (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return;
        }

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (!connected) {
                    restart(appContext);
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (!connected && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    restart(appContext);
                }
            }
        };

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        try {
            manager.registerNetworkCallback(request, networkCallback);
        } catch (Exception ignored) {
            // Without the callback the backoff loop still reconnects, just slower.
            networkCallback = null;
        }
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
