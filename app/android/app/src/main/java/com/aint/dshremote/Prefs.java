package com.aint.dshremote;

import android.content.Context;
import android.content.SharedPreferences;

/** Small persistent store for the paired server and token. */
public final class Prefs {

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        prefs = context.getSharedPreferences("dsh-remote", Context.MODE_PRIVATE);
    }

    public String server() {
        return prefs.getString("server", "");
    }

    public String token() {
        return prefs.getString("token", "");
    }

    /** Keeps the token; only the host moved (new DHCP lease, router restart). */
    public void setServer(String server) {
        prefs.edit().putString("server", server).apply();
    }

    public void save(String server, String token) {
        prefs.edit().putString("server", server).putString("token", token).apply();
    }

    public void clear() {
        prefs.edit().remove("token").apply();
    }
}
