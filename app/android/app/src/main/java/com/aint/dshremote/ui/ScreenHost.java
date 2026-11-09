package com.aint.dshremote.ui;

import android.app.Activity;
import android.os.Handler;
import android.view.View;

import com.aint.dshremote.Api;
import com.aint.dshremote.LiveLink;
import com.aint.dshremote.Prefs;

import java.util.concurrent.ExecutorService;

/**
 * What a screen may ask of the router. {@code MainActivity} is the only implementation: it owns
 * the activity, the worker thread, the shared {@link Api} and screen switching, while the screen
 * classes only build and drive their own views.
 */
public interface ScreenHost {

    Activity activity();

    ExecutorService io();

    Handler main();

    Prefs prefs();

    Api api();

    void setApi(Api api);

    LiveLink link();

    int dp(int value);

    void toast(String message);

    String friendly(Exception error);

    void showPairing();

    void showSessions();

    void showChat(String sessionId, String title);

    /** Installs a screen's root view (the old {@code setScreen}). */
    void showView(View view);

    /** Opens the document picker; the result is staged into the current chat. */
    void startFilePicker();
}
