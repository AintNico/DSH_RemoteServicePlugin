package com.aint.dshremote.ui;

import android.app.Activity;
import android.widget.Toast;

import com.aint.dshremote.Api;

import org.json.JSONObject;

import java.util.Locale;

/** Helpers shared by more than one screen. */
public final class UiKit {

    /** Colour of the "running" dot in the session list. */
    public static final int GREEN = 0xFF188038;

    private UiKit() {
    }

    public static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    public static void toast(Activity activity, String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    public static int color(Activity activity, int colorRes) {
        return activity.getColor(colorRes);
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** File names come from the host; strip anything that could escape the share directory. */
    public static String safeFileName(String name) {
        String cleaned = name == null ? "" : name.replace('\\', '_').replace('/', '_').trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) {
            return "file";
        }
        return cleaned;
    }

    public static String friendly(Exception exception) {
        if (exception instanceof Api.ApiException) {
            String code = ((Api.ApiException) exception).code();
            switch (code) {
                case "bad-code":
                    return "配对码不正确";
                case "expired":
                    return "配对码已过期，请在电脑端重新生成";
                case "too-many-attempts":
                    return "尝试次数过多，请稍后再试";
                case "unauthorized":
                    return "登录已失效，请重新配对";
                default:
                    return "请求失败：" + code;
            }
        }
        return "网络错误：" + exception.getMessage();
    }

    /**
     * The server folds a title from the session log (the task name DSH itself shows); the
     * folder name is only a fallback for sessions that have no title yet.
     */
    public static String titleOf(JSONObject session) {
        String title = session.optString("title", "");
        if (!title.isEmpty()) {
            return title;
        }
        String cwd = session.optString("cwd", "");
        if (cwd.isEmpty()) {
            return session.optString("id");
        }
        int cut = Math.max(cwd.lastIndexOf('\\'), cwd.lastIndexOf('/'));
        String name = cut >= 0 ? cwd.substring(cut + 1) : cwd;
        return name.isEmpty() ? cwd : name;
    }

    public static String shortenPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return path;
    }
}
