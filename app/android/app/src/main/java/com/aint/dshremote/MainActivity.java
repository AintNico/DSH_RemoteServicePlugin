package com.aint.dshremote;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements LiveLink.Listener {

    private static final int ACCENT = 0xFF1A73E8;
    private static final int GREEN = 0xFF188038;
    private static final int TEXT = 0xFF1B1F27;
    private static final int MUTED = 0xFF7A808C;
    private static final int PANEL = 0xFFF2F4F7;
    private static final int DANGER = 0xFFD93025;
    private static final int LINE = 0xFFE8EAED;

    private static final int REQ_PICK_FILE = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<JSONObject> messages = new ArrayList<>();
    private final List<JSONObject> sessions = new ArrayList<>();
    private final Map<String, View> approvalViews = new HashMap<>();

    private Prefs prefs;
    private Api api;
    private String sessionId;
    private String sessionTitle;
    private long lastSeq;
    private boolean sessionsVisible;
    private boolean uploading;
    private long lastSessionRefresh;

    private ChatAdapter chatAdapter;
    private SessionAdapter sessionAdapter;
    private ListView chatList;
    private LinearLayout approvalBox;
    private EditText composer;
    private TextView chatStatus;
    private Button attachButton;
    private TextView sessionStatus;

    private StringBuilder streamBuffer;
    private JSONObject streamingMessage;

    private Drawable bubbleUser;
    private Drawable bubbleAssistant;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        LiveLink.get().addListener(this);
        requestNotificationPermission();

        if (prefs.token().isEmpty()) {
            showPairing();
        } else {
            connect();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        LiveLink.get().setAppVisible(true);
        if (sessionsVisible && api != null) {
            loadSessions();
        }
    }

    @Override
    protected void onPause() {
        LiveLink.get().setAppVisible(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        LiveLink.get().removeListener(this);
        super.onDestroy();
        io.shutdownNow();
    }

    /** Gesture or key back: leave the conversation first, then the app. */
    @Override
    public void onBackPressed() {
        if (sessionId != null) {
            sessionId = null;
            approvalViews.clear();
            showSessions();
            return;
        }
        super.onBackPressed();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFICATIONS);
        }
    }

    // ---------- 配对 ----------

    private void showPairing() {
        sessionsVisible = false;
        sessionId = null;

        LinearLayout root = column(24);
        root.setGravity(Gravity.CENTER_VERTICAL);

        root.addView(label("连接到 DSH 插件", 24, TEXT, true));
        root.addView(space(6));
        root.addView(label("配对码在电脑端窗口里：左下角「配对码」按钮。", 13, MUTED, false));
        root.addView(space(20));

        EditText server = input("服务地址", prefs.server(), InputType.TYPE_TEXT_VARIATION_URI);
        EditText code = input("配对码", "", InputType.TYPE_CLASS_NUMBER);
        code.setMaxEms(6);
        EditText device = input("设备名称", android.os.Build.MODEL, InputType.TYPE_CLASS_TEXT);
        root.addView(server);
        root.addView(space(10));
        root.addView(code);
        root.addView(space(10));
        root.addView(device);
        root.addView(space(18));

        Button pair = button("配对", ACCENT, Color.WHITE);
        root.addView(pair, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView error = label("", 13, DANGER, false);
        root.addView(space(10));
        root.addView(error);

        pair.setOnClickListener(v -> {
            String serverUrl = server.getText().toString().trim();
            String pairingCode = code.getText().toString().trim();
            String deviceName = device.getText().toString().trim();
            if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
                serverUrl = "http://" + serverUrl;
            }
            if (serverUrl.isEmpty() || pairingCode.length() != 6) {
                error.setText("请填写服务地址和 6 位配对码");
                return;
            }
            final String targetServer = serverUrl;
            final String targetCode = pairingCode;
            pair.setEnabled(false);
            pair.setAlpha(0.5f);
            error.setText("");
            io.execute(() -> {
                try {
                    JSONObject result = Api.pair(targetServer, targetCode, deviceName.isEmpty() ? "Android" : deviceName);
                    String token = result.optString("token", "");
                    if (token.isEmpty()) throw new Exception("服务端没有返回令牌");
                    prefs.save(targetServer, token);
                    main.post(this::connect);
                } catch (Exception exception) {
                    main.post(() -> {
                        pair.setEnabled(true);
                        pair.setAlpha(1f);
                        error.setText(friendly(exception));
                    });
                }
            });
        });

        setScreen(root);
    }

    // ---------- 会话 ----------

    private void connect() {
        api = new Api(prefs.server(), prefs.token());
        startRemoteService();
        showSessions();
    }

    private void startRemoteService() {
        Intent intent = new Intent(this, RemoteService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void showSessions() {
        sessionId = null;
        sessionsVisible = true;
        chatList = null;
        chatAdapter = null;
        streamingMessage = null;
        streamBuffer = null;

        LinearLayout root = column(0);

        LinearLayout header = row();
        header.setPadding(dp(16), dp(14), dp(16), dp(10));
        header.addView(label("会话", 20, TEXT, true), weight(1));
        header.addView(button("刷新", PANEL, ACCENT), new LinearLayout.LayoutParams(dp(72), dp(40)));
        root.addView(header);

        sessionStatus = label("正在加载…", 12, MUTED, false);
        sessionStatus.setPadding(dp(16), 0, dp(16), dp(8));
        root.addView(sessionStatus);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        sessionAdapter = new SessionAdapter();
        list.setAdapter(sessionAdapter);
        root.addView(list, verticalWeight(1));

        LinearLayout footer = row();
        footer.setPadding(dp(16), dp(10), dp(16), dp(16));
        Button unpair = button("解除配对", PANEL, ACCENT);
        footer.addView(unpair, new LinearLayout.LayoutParams(dp(104), dp(40)));
        root.addView(footer);

        unpair.setOnClickListener(v -> {
            prefs.clear();
            LiveLink.get().stop();
            stopService(new Intent(this, RemoteService.class));
            api = null;
            sessions.clear();
            sessionAdapter.notifyDataSetChanged();
            showPairing();
        });

        setScreen(root);
        loadSessions();
    }

    private void loadSessions() {
        if (api == null) {
            return;
        }
        lastSessionRefresh = System.currentTimeMillis();
        io.execute(() -> {
            try {
                JSONArray array = api.sessions();
                List<JSONObject> rows = new ArrayList<>();
                for (int i = 0; i < (array == null ? 0 : array.length()); i++) {
                    rows.add(array.getJSONObject(i));
                }
                main.post(() -> {
                    if (!sessionsVisible) {
                        return;
                    }
                    sessions.clear();
                    sessions.addAll(rows);
                    sessionAdapter.notifyDataSetChanged();
                    sessionStatus.setText(rows.isEmpty() ? "还没有会话" : rows.size() + " 个会话");
                });
            } catch (Exception exception) {
                main.post(() -> {
                    if (sessionStatus != null) {
                        sessionStatus.setText(friendly(exception));
                    }
                });
            }
        });
    }

    private String titleOf(JSONObject session) {
        String cwd = session.optString("cwd", "");
        if (cwd.isEmpty()) {
            return session.optString("id");
        }
        int cut = Math.max(cwd.lastIndexOf('\\'), cwd.lastIndexOf('/'));
        String name = cut >= 0 ? cwd.substring(cut + 1) : cwd;
        return name.isEmpty() ? cwd : name;
    }

    // ---------- 对话 ----------

    private void showChat() {
        sessionsVisible = false;
        lastSeq = 0;

        LinearLayout root = column(0);

        LinearLayout header = row();
        header.setPadding(dp(8), dp(10), dp(14), dp(8));

        TextView back = label("‹", 30, ACCENT, true);
        back.setPadding(dp(12), 0, dp(10), dp(4));
        back.setOnClickListener(v -> onBackPressed());
        header.addView(back);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = label(sessionTitle == null ? "" : sessionTitle, 17, TEXT, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleBox.addView(titleView);
        chatStatus = label(LiveLink.get().connected() ? "已连接" : "连接中…", 11, MUTED, false);
        titleBox.addView(chatStatus);
        header.addView(titleBox, weight(1));

        Button files = button("文件", PANEL, ACCENT);
        LinearLayout.LayoutParams filesParams = new LinearLayout.LayoutParams(dp(60), dp(40));
        filesParams.rightMargin = dp(8);
        header.addView(files, filesParams);

        Button stop = button("停止", PANEL, ACCENT);
        header.addView(stop, new LinearLayout.LayoutParams(dp(60), dp(40)));
        root.addView(header);

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        chatList = new ListView(this);
        chatList.setDivider(null);
        chatList.setDividerHeight(0);
        chatList.setClipToPadding(false);
        chatList.setPadding(0, dp(6), 0, dp(6));
        chatAdapter = new ChatAdapter();
        chatList.setAdapter(chatAdapter);
        root.addView(chatList, verticalWeight(1));

        approvalBox = new LinearLayout(this);
        approvalBox.setOrientation(LinearLayout.VERTICAL);
        approvalBox.setPadding(dp(12), 0, dp(12), dp(6));
        root.addView(approvalBox);

        LinearLayout composerRow = row();
        composerRow.setPadding(dp(10), dp(8), dp(10), dp(14));

        attachButton = button("＋", PANEL, ACCENT);
        attachButton.setTextSize(20);
        LinearLayout.LayoutParams attachParams = new LinearLayout.LayoutParams(dp(46), dp(46));
        attachParams.rightMargin = dp(8);
        composerRow.addView(attachButton, attachParams);

        composer = new EditText(this);
        composer.setHint("输入消息");
        composer.setBackground(roundRect(PANEL, 14));
        composer.setPadding(dp(14), dp(10), dp(14), dp(10));
        composer.setMaxLines(4);
        composer.setTextSize(15);
        composerRow.addView(composer, weight(1));

        Button send = button("发送", ACCENT, Color.WHITE);
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(72), dp(46));
        sendParams.leftMargin = dp(8);
        composerRow.addView(send, sendParams);

        root.addView(composerRow);

        messages.clear();
        streamingMessage = null;
        streamBuffer = null;
        chatList.setAdapter(chatAdapter);

        stop.setOnClickListener(v -> io.execute(() -> {
            try {
                api.cancel(sessionId);
            } catch (Exception exception) {
                main.post(() -> Toast.makeText(this, friendly(exception), Toast.LENGTH_SHORT).show());
            }
        }));
        send.setOnClickListener(v -> sendMessage());
        attachButton.setOnClickListener(v -> pickFile());
        files.setOnClickListener(v -> showWorkspace());

        setScreen(root);
        approvalViews.clear();
        approvalBox.removeAllViews();
        loadMessages();
    }

    private void loadMessages() {
        final String target = sessionId;
        io.execute(() -> {
            try {
                JSONArray array = api.messages(target);
                List<JSONObject> rows = new ArrayList<>();
                long maxSeq = 0;
                for (int i = 0; i < (array == null ? 0 : array.length()); i++) {
                    JSONObject row = array.getJSONObject(i);
                    rows.add(row);
                    maxSeq = Math.max(maxSeq, row.optLong("seq", 0));
                }
                final long throughSeq = maxSeq;
                main.post(() -> {
                    if (target == null || !target.equals(sessionId)) {
                        return;
                    }
                    messages.clear();
                    messages.addAll(rows);
                    lastSeq = throughSeq;
                    chatAdapter.notifyDataSetChanged();
                    scrollToBottom();
                });
            } catch (Exception exception) {
                main.post(() -> Toast.makeText(this, friendly(exception), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Pull only what was missed while the stream was disconnected. */
    private void syncMessages() {
        if (api == null || sessionId == null || chatAdapter == null) {
            return;
        }
        final String target = sessionId;
        final long since = lastSeq;
        io.execute(() -> {
            try {
                JSONArray array = api.messages(target, since);
                List<JSONObject> rows = new ArrayList<>();
                long maxSeq = since;
                for (int i = 0; i < (array == null ? 0 : array.length()); i++) {
                    JSONObject row = array.getJSONObject(i);
                    rows.add(row);
                    maxSeq = Math.max(maxSeq, row.optLong("seq", 0));
                }
                final long throughSeq = maxSeq;
                if (rows.isEmpty()) {
                    return;
                }
                main.post(() -> {
                    if (target == null || !target.equals(sessionId)) {
                        return;
                    }
                    messages.addAll(rows);
                    lastSeq = Math.max(lastSeq, throughSeq);
                    chatAdapter.notifyDataSetChanged();
                    scrollToBottom();
                });
            } catch (Exception ignored) {
                // The stream will retry; the next reconnect pulls again.
            }
        });
    }

    private void sendMessage() {
        if (composer == null || sessionId == null || uploading) {
            return;
        }
        String text = composer.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        final String target = sessionId;
        composer.setText("");
        io.execute(() -> {
            try {
                api.prompt(target, text);
            } catch (Exception exception) {
                main.post(() -> Toast.makeText(this, friendly(exception), Toast.LENGTH_LONG).show());
            }
        });
    }

    // ---------- 文件 ----------

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        uploadFile(data.getData());
    }

    private void uploadFile(Uri uri) {
        if (api == null || sessionId == null || uploading) {
            return;
        }

        String name = "file";
        long size = -1;
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex);
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception ignored) {
            // Fall back to the defaults above.
        }

        final String fileName = name;
        final long fileSize = size;
        final String caption = composer == null ? "" : composer.getText().toString().trim();
        final String target = sessionId;

        uploading = true;
        attachButton.setEnabled(false);
        attachButton.setAlpha(0.4f);
        if (chatStatus != null) {
            chatStatus.setText("正在上传 " + fileName + "…");
        }

        io.execute(() -> {
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                if (stream == null) {
                    throw new Exception("无法读取所选文件");
                }
                api.upload(target, fileName, stream, fileSize, caption);
                main.post(() -> {
                    uploading = false;
                    if (attachButton != null) {
                        attachButton.setEnabled(true);
                        attachButton.setAlpha(1f);
                    }
                    if (composer != null) {
                        composer.setText("");
                    }
                    if (chatStatus != null) {
                        chatStatus.setText("已发送 " + fileName);
                    }
                });
            } catch (Exception exception) {
                main.post(() -> {
                    uploading = false;
                    if (attachButton != null) {
                        attachButton.setEnabled(true);
                        attachButton.setAlpha(1f);
                    }
                    String message = friendly(exception);
                    if (chatStatus != null) {
                        chatStatus.setText("上传失败：" + message);
                    }
                    Toast.makeText(this, "上传失败：" + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ---------- 工作区下载 ----------

    private void showWorkspace() {
        if (api == null || sessionId == null) {
            return;
        }
        final String target = sessionId;

        Dialog dialog = new Dialog(this);
        LinearLayout box = column(16);

        TextView pathView = label(".", 14, TEXT, true);
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);

        TextView status = label("", 12, MUTED, false);
        LinearLayout actions = row();
        Button up = button("上一级", PANEL, ACCENT);
        Button close = button("关闭", PANEL, ACCENT);
        LinearLayout.LayoutParams upParams = new LinearLayout.LayoutParams(0, dp(40), 1);
        upParams.rightMargin = dp(8);
        actions.addView(up, upParams);
        actions.addView(close, new LinearLayout.LayoutParams(0, dp(40), 1));

        box.addView(pathView);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        box.addView(status);
        box.addView(actions);

        dialog.setContentView(box);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(roundRect(Color.WHITE, 16));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        final String[] current = {"."};
        final Runnable[] refresh = new Runnable[1];

        refresh[0] = () -> {
            final String path = current[0];
            pathView.setText(path);
            status.setText("读取中…");
            io.execute(() -> {
                try {
                    JSONObject result = api.workspace(target, path);
                    JSONArray entries = result.optJSONArray("entries");
                    List<JSONObject> items = new ArrayList<>();
                    for (int i = 0; i < (entries == null ? 0 : entries.length()); i++) {
                        items.add(entries.getJSONObject(i));
                    }
                    main.post(() -> {
                        rows.removeAllViews();
                        if (items.isEmpty()) {
                            rows.addView(label("（空目录）", 13, MUTED, false));
                        }
                        for (JSONObject entry : items) {
                            final String name = entry.optString("name");
                            final boolean directory = entry.optBoolean("directory");
                            TextView rowView = label((directory ? "📁  " : "📄  ") + name, 14, TEXT, directory);
                            rowView.setPadding(dp(4), dp(12), dp(4), dp(12));
                            rowView.setBackground(new RippleDrawable(ColorStateList.valueOf(0x1F000000), null, null));
                            rowView.setOnClickListener(v -> {
                                if (directory) {
                                    current[0] = ".".equals(path) ? name : path + "/" + name;
                                    refresh[0].run();
                                } else {
                                    downloadWorkspaceFile(target, ".".equals(path) ? name : path + "/" + name, status);
                                }
                            });
                            rows.addView(rowView);
                        }
                        status.setText(items.size() + " 项");
                    });
                } catch (Exception exception) {
                    main.post(() -> status.setText(friendly(exception)));
                }
            });
        };

        up.setOnClickListener(v -> {
            if (".".equals(current[0])) {
                return;
            }
            int cut = current[0].lastIndexOf('/');
            current[0] = cut > 0 ? current[0].substring(0, cut) : ".";
            refresh[0].run();
        });
        close.setOnClickListener(v -> dialog.dismiss());

        dialog.setOnDismissListener(d -> {
            // Nothing to release; the work list is owned by the closure.
        });
        refresh[0].run();
    }

    private void downloadWorkspaceFile(String target, String path, TextView status) {
        status.setText("正在准备下载…");
        io.execute(() -> {
            Uri uri = null;
            try {
                JSONObject signed = api.signFile(target, path);
                String url = signed.optString("url", "");
                String fileName = signed.optString("name", "file");
                if (url.isEmpty()) {
                    throw new Exception("服务端没有返回下载地址");
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DSH");
                    uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) {
                        throw new Exception("无法创建下载文件");
                    }
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) {
                            throw new Exception("无法写入下载文件");
                        }
                        api.download(url, out);
                    }
                    main.post(() -> status.setText("已保存到 下载/DSH/" + fileName));
                } else {
                    File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "DSH");
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new Exception("无法创建下载目录");
                    }
                    File file = new File(dir, fileName);
                    try (OutputStream out = new FileOutputStream(file)) {
                        api.download(url, out);
                    }
                    main.post(() -> status.setText("已保存到 " + file.getAbsolutePath()));
                }
            } catch (Exception exception) {
                if (uri != null) {
                    try {
                        getContentResolver().delete(uri, null, null);
                    } catch (Exception ignored) {
                        // Best-effort cleanup of the pending entry.
                    }
                }
                main.post(() -> status.setText("下载失败：" + friendly(exception)));
            }
        });
    }

    // ---------- 实时流 ----------

    @Override
    public void onFrame(JSONObject frame) {
        String kind = frame.optString("kind");
        if ("stream-start".equals(kind)) {
            if (sessionId != null && sessionId.equals(frame.optString("sessionId"))) {
                beginStream();
            }
            return;
        }
        if ("stream".equals(kind)) {
            if (sessionId != null && sessionId.equals(frame.optString("sessionId"))) {
                appendStream(frame.optString("text", ""));
            }
            return;
        }
        if ("stream-end".equals(kind)) {
            if (sessionId != null && sessionId.equals(frame.optString("sessionId"))) {
                endStream();
            }
            return;
        }

        if ("message".equals(kind)) {
            if (sessionId != null && sessionId.equals(frame.optString("sessionId"))) {
                long seq = frame.optLong("seq", 0);
                if (seq > lastSeq) {
                    // The committed copy replaces the live stream, so drop the placeholder.
                    if ("assistant".equals(frame.optString("role"))) {
                        removeStreamingPlaceholder();
                    }
                    lastSeq = seq;
                    messages.add(frame);
                    if (chatAdapter != null) {
                        chatAdapter.notifyDataSetChanged();
                        scrollToBottom();
                    }
                }
            } else if (sessionsVisible && System.currentTimeMillis() - lastSessionRefresh > 2000) {
                loadSessions();
            }
        } else if ("approval".equals(kind)) {
            addApprovalCard(frame);
        } else if ("approval-resolved".equals(kind)) {
            View view = approvalViews.remove(frame.optString("id"));
            if (view != null && approvalBox != null) {
                approvalBox.removeView(view);
            }
        }
    }

    private void beginStream() {
        if (chatAdapter == null) {
            return;
        }
        removeStreamingPlaceholder();
        streamBuffer = new StringBuilder();
        streamingMessage = new JSONObject();
        try {
            streamingMessage.put("kind", "message");
            streamingMessage.put("role", "assistant");
            streamingMessage.put("sessionId", sessionId);
            streamingMessage.put("seq", Long.MAX_VALUE);
            streamingMessage.put("text", "");
        } catch (Exception ignored) {
            // A synthetic object cannot fail here, but never let it break the stream.
        }
        messages.add(streamingMessage);
        chatAdapter.notifyDataSetChanged();
        scrollToBottom();
    }

    private void appendStream(String text) {
        if (streamingMessage == null) {
            beginStream();
            if (streamingMessage == null) {
                return;
            }
        }
        streamBuffer.append(text);
        try {
            streamingMessage.put("text", streamBuffer.toString());
        } catch (Exception ignored) {
            // Ignore a failed update; the committed message still arrives.
        }
        if (chatAdapter != null) {
            chatAdapter.notifyDataSetChanged();
            scrollToBottom();
        }
    }

    private void endStream() {
        removeStreamingPlaceholder();
        if (chatAdapter != null) {
            chatAdapter.notifyDataSetChanged();
        }
    }

    private void removeStreamingPlaceholder() {
        if (streamingMessage != null) {
            messages.remove(streamingMessage);
            streamingMessage = null;
            streamBuffer = null;
        }
    }

    @Override
    public void onConnection(boolean connected) {
        if (chatStatus != null && sessionId != null) {
            chatStatus.setText(connected ? "已连接" : "连接中断，正在重连…");
            if (connected) {
                syncMessages();
            }
        }
        if (sessionStatus != null && sessionsVisible) {
            sessionStatus.setText(connected ? (sessions.isEmpty() ? "还没有会话" : sessions.size() + " 个会话")
                    : "连接中断，正在重连…");
            if (connected) {
                loadSessions();
            }
        }
    }

    private void scrollToBottom() {
        if (chatList != null) {
            chatList.post(() -> {
                if (chatList.getCount() > 0) {
                    chatList.setSelection(chatList.getCount() - 1);
                }
            });
        }
    }

    private void addApprovalCard(JSONObject approval) {
        if (approvalBox == null || sessionId == null || !sessionId.equals(approval.optString("sessionId"))) {
            return;
        }
        String id = approval.optString("id");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(roundRect(0xFFFFF4E5, 14));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(8);

        card.addView(label("待审批 · " + approval.optString("toolName", "工具调用"), 14, TEXT, true));
        String reason = approval.optString("reason", "");
        if (!reason.isEmpty()) {
            TextView reasonView = label(reason, 13, TEXT, false);
            reasonView.setPadding(0, dp(6), 0, dp(10));
            card.addView(reasonView);
        }

        LinearLayout buttons = row();
        Button allow = button("允许一次", GREEN, Color.WHITE);
        Button deny = button("拒绝", LINE, TEXT);

        LinearLayout.LayoutParams allowParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        allowParams.rightMargin = dp(8);
        buttons.addView(allow, allowParams);
        buttons.addView(deny, new LinearLayout.LayoutParams(0, dp(44), 1));
        card.addView(buttons);

        allow.setOnClickListener(v -> answer(id, "allowed-once", card));
        deny.setOnClickListener(v -> answer(id, "rejected", card));

        approvalViews.put(id, card);
        approvalBox.addView(card, cardParams);
    }

    private void answer(String id, String outcome, View card) {
        io.execute(() -> {
            try {
                api.answer(id, outcome);
            } catch (Exception exception) {
                main.post(() -> Toast.makeText(this, friendly(exception), Toast.LENGTH_SHORT).show());
            }
            main.post(() -> {
                approvalViews.remove(id);
                if (approvalBox != null) {
                    approvalBox.removeView(card);
                }
            });
        });
    }

    // ---------- 适配器 ----------

    private static final class SessionHolder {
        View dot;
        TextView title;
        TextView subtitle;
        LinearLayout row;
    }

    private final class SessionAdapter extends BaseAdapter {
        private final int ROW = 0;

        @Override
        public int getCount() {
            return sessions.size();
        }

        @Override
        public Object getItem(int position) {
            return sessions.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            SessionHolder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(12), dp(16), dp(12));
                row.setBackgroundColor(Color.WHITE);

                View dot = new View(MainActivity.this);
                GradientDrawable dotShape = new GradientDrawable();
                dotShape.setShape(GradientDrawable.OVAL);
                dotShape.setColor(0xFFC4C8CE);
                dot.setBackground(dotShape);
                LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
                dotParams.rightMargin = dp(12);
                row.addView(dot, dotParams);

                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView title = label("", 16, TEXT, true);
                TextView subtitle = label("", 12, MUTED, false);
                subtitle.setMaxLines(1);
                subtitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                texts.addView(title);
                texts.addView(subtitle);
                row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                View divider = new View(MainActivity.this);
                divider.setBackgroundColor(LINE);

                LinearLayout wrapper = new LinearLayout(MainActivity.this);
                wrapper.setOrientation(LinearLayout.VERTICAL);
                wrapper.addView(row);
                wrapper.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

                holder = new SessionHolder();
                holder.row = row;
                holder.dot = dot;
                holder.title = title;
                holder.subtitle = subtitle;
                wrapper.setTag(holder);
                convertView = wrapper;
            } else {
                holder = (SessionHolder) convertView.getTag();
            }

            JSONObject session = sessions.get(position);
            boolean running = session.optBoolean("running");
            holder.title.setText(titleOf(session));
            holder.subtitle.setText((running ? "运行中" : "空闲") + " · " + session.optString("cwd", ""));

            GradientDrawable dotShape = new GradientDrawable();
            dotShape.setShape(GradientDrawable.OVAL);
            dotShape.setColor(running ? GREEN : 0xFFC4C8CE);
            holder.dot.setBackground(dotShape);

            final String id = session.optString("id");
            final String title = titleOf(session);
            holder.row.setOnClickListener(v -> {
                sessionId = id;
                sessionTitle = title;
                showChat();
            });
            return convertView;
        }
    }

    private final class ChatAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return messages.size();
        }

        @Override
        public Object getItem(int position) {
            return messages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FrameLayout holder;
            TextView bubble;
            if (convertView == null) {
                holder = new FrameLayout(MainActivity.this);
                bubble = new TextView(MainActivity.this);
                bubble.setTextIsSelectable(true);
                bubble.setLineSpacing(dp(2), 1f);
                holder.addView(bubble, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                holder.setTag(bubble);
                convertView = holder;
            } else {
                holder = (FrameLayout) convertView;
                bubble = (TextView) holder.getTag();
            }

            JSONObject message = messages.get(position);
            String role = message.optString("role");
            String raw = message.optString("text");
            if ("tool".equals(role)) {
                bubble.setText(raw);
            } else {
                bubble.setText(Markdown.render(raw));
            }
            bubble.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.78f));

            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bubble.getLayoutParams();
            params.gravity = "user".equals(role) ? Gravity.END : Gravity.START;
            params.leftMargin = dp(12);
            params.rightMargin = dp(12);
            params.topMargin = dp(3);
            params.bottomMargin = dp(3);
            bubble.setLayoutParams(params);

            if ("user".equals(role)) {
                bubble.setTextSize(15);
                bubble.setTextColor(Color.WHITE);
                bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
                bubble.setBackground(bubbleUser());
            } else if ("tool".equals(role)) {
                bubble.setTextSize(12);
                bubble.setTextColor(MUTED);
                bubble.setPadding(dp(14), dp(4), dp(14), dp(4));
                bubble.setBackground(null);
            } else {
                bubble.setTextSize(15);
                bubble.setTextColor(TEXT);
                bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
                bubble.setBackground(bubbleAssistant());
            }
            return convertView;
        }
    }

    private Drawable bubbleUser() {
        if (bubbleUser == null) {
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(ACCENT);
            shape.setCornerRadius(dp(14));
            bubbleUser = shape;
        }
        return bubbleUser;
    }

    private Drawable bubbleAssistant() {
        if (bubbleAssistant == null) {
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(PANEL);
            shape.setCornerRadius(dp(14));
            bubbleAssistant = shape;
        }
        return bubbleAssistant;
    }

    // ---------- 控件 ----------

    /**
     * Hosts one screen in an inset-aware container. targetSdk 35 enforces edge-to-edge,
     * so the IME inset must be applied here or the composer hides under the keyboard.
     */
    private void setScreen(View content) {
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.WHITE);
        container.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            container.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                Insets ime = windowInsets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                if (ime.bottom > 0) {
                    scrollToBottom();
                }
                return windowInsets;
            });
            container.requestApplyInsets();
        }

        setContentView(container);
    }

    private LinearLayout column(int padding) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.WHITE);
        layout.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return view;
    }

    private EditText input(String hint, String value, int inputType) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setText(value);
        view.setInputType(inputType);
        view.setBackground(roundRect(PANEL, 12));
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setSingleLine(true);
        return view;
    }

    /** One button shape everywhere: flat, fixed height, ripple feedback. */
    private Button button(String text, int background, int textColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(textColor);
        button.setStateListAnimator(null);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(ripple(background, 12,
                background == PANEL ? 0x1F000000 : 0x33FFFFFF));
        return button;
    }

    private Drawable ripple(int color, int radius, int rippleColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), shape, null);
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private View space(int height) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height)));
        return view;
    }

    private LinearLayout.LayoutParams weight(int weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    private LinearLayout.LayoutParams verticalWeight(int weight) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String friendly(Exception exception) {
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
}
