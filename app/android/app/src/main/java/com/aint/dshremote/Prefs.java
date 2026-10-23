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
        return prefs.getString("server", "http://192.168.33.191:7317");
    }

    public String token() {
        return prefs.getString("token", "");
    }

    public void save(String server, String token) {
        prefs.edit().putString("server", server).putString("token", token).apply();
    }

    public void clear() {
        prefs.edit().remove("token").apply();
    }
}
