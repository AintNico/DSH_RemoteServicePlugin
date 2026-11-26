package com.aint.dshremote.ui;

import android.net.Uri;
import android.text.TextUtils;

/**
 * Parser for the deep link the desktop QR encodes:
 *
 *   dshremote://pair?v=1&url=http%3A%2F%2F192.168.1.23%3A7317&code=123456
 *
 * The phone accepts the same link from its own camera (system scanner) and from the in-app
 * scanner, so both entry points share this one parser.
 */
public final class PairLink {

    public final String url;
    public final String code;

    private PairLink(String url, String code) {
        this.url = url;
        this.code = code;
    }

    public static boolean isPairLink(Uri uri) {
        return uri != null
                && "dshremote".equalsIgnoreCase(uri.getScheme())
                && "pair".equalsIgnoreCase(uri.getHost());
    }

    /** Returns null when the link is incomplete; callers show their own message. */
    public static PairLink parse(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        Uri uri;
        try {
            uri = Uri.parse(raw.trim());
        } catch (Exception error) {
            return null;
        }
        if (!isPairLink(uri)) {
            return null;
        }
        String url = uri.getQueryParameter("url");
        String code = uri.getQueryParameter("code");
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(code)) {
            return null;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        return new PairLink(url, code.trim());
    }
}
