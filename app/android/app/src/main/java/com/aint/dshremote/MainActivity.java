package com.aint.dshremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
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

    /** Data bound to the virtualized chat list; the visible rows are far fewer. */
    private static final int MAX_MESSAGES = 300;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<JSONObject> messages = new ArrayList<>();
    private final List<ListRow> sessionRows = new ArrayList<>();
    private final Map<String, View> approvalViews = new HashMap<>();

    /** Rendered markdown is expensive; cache it per message and let GC follow the message list. */
    private final Map<JSONObject, CharSequence> markdownCache =
            Collections.synchronizedMap(new WeakHashMap<>());

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
    private Button refreshButton;
    private long refreshStartedAt;
    private int sessionCount;
    private LinearLayout pendingRow;
    private TextView pendingLabel;

    private Uri pendingUri;
    private String pendingName;
    private long pendingSize = -1;
    private Uri sharedUri;
    private boolean fileDownloadInFlight;

    private StringBuilder streamBuffer;
    private JSONObject streamingMessage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        LiveLink.get().addListener(this);
        requestNotificationPermission();

        if (prefs.token().isEmpty() || prefs.server().isEmpty()) {
            showPairing();
        } else {
            connect();
        }

        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    /**
     * Receives a file shared from another app (QQ, WeChat, any file manager). Those apps keep
     * their chat files in private storage that no third-party app can read on Android 11+, so
     * sharing is the supported way in. The file is staged, never sent immediately.
     */
    private void handleShareIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        // SEND/SEND_MULTIPLE is the system share sheet; VIEW is what QQ's "用其他应用打开" uses.
        if (!Intent.ACTION_SEND.equals(action)
                && !Intent.ACTION_SEND_MULTIPLE.equals(action)
                && !Intent.ACTION_VIEW.equals(action)) {
            return;
        }

        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        }
        if (uri == null) {
            Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) {
                uri = (Uri) stream;
            }
        }
        if (uri == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
            uri = intent.getClipData().getItemAt(0).getUri();
        }
        if (uri == null) {
            return;
        }

        if (sessionId != null && chatAdapter != null) {
            stageAttachment(uri);
            Toast.makeText(this, "已添加待发送附件", Toast.LENGTH_SHORT).show();
        } else {
            sharedUri = uri;
            Toast.makeText(this, "请先打开一个会话，附件的说明文字写完再发送", Toast.LENGTH_LONG).show();
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

        root.addView(space(10));
        Button discover = button("自动发现电脑", PANEL, ACCENT);
        root.addView(discover, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        TextView error = label("", 13, DANGER, false);
        root.addView(space(10));
        root.addView(error);

        discover.setOnClickListener(v -> {
            discover.setEnabled(false);
            discover.setAlpha(0.5f);
            discover.setText("正在扫描同一网络…");
            error.setText("");
            io.execute(() -> {
                List<String> found = Discovery.scan(7317, 3);
                main.post(() -> {
                    discover.setEnabled(true);
                    discover.setAlpha(1f);
                    discover.setText("自动发现电脑");
                    if (found.isEmpty()) {
                        error.setText("没找到运行插件的电脑：确认手机和电脑连的是同一个 Wi-Fi");
                        return;
                    }
                    server.setText(found.get(0));
                    error.setText(found.size() > 1
                            ? "找到 " + found.size() + " 台，已填入第一台：" + found.get(0)
                            : "已找到 " + found.get(0));
                });
            });
        });

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
        pendingUri = null;
        pendingName = null;
        pendingSize = -1;
        pendingRow = null;
        pendingLabel = null;

        LinearLayout root = column(0);

        LinearLayout header = row();
        header.setPadding(dp(16), dp(14), dp(16), dp(10));
        header.addView(label("会话", 20, TEXT, true), weight(1));
        refreshButton = button("刷新", PANEL, ACCENT);
        header.addView(refreshButton, new LinearLayout.LayoutParams(dp(72), dp(40)));
        refreshButton.setOnClickListener(v -> loadSessions(true));
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
            sessionRows.clear();
            sessionAdapter.notifyDataSetChanged();
            showPairing();
        });

        setScreen(root);
        loadSessions();
    }

    private void loadSessions() {
        loadSessions(false);
    }

    /**
     * Loads the workspace-grouped session list. The manual flag drives the refresh button so a
     * tap always has visible feedback, even when the payload is unchanged.
     */
    private void loadSessions(boolean manual) {
        if (api == null) {
            return;
        }
        lastSessionRefresh = System.currentTimeMillis();
        if (manual) {
            startRefresh();
        }
        io.execute(() -> {
            try {
                JSONObject payload = api.workspaces();
                List<ListRow> rows = buildSessionRows(payload);
                int sections = 0;
                for (ListRow row : rows) {
                    if (row.section) {
                        sections++;
                    }
                }
                int count = rows.size() - sections;
                final int groups = sections;
                main.post(() -> {
                    finishRefresh();
                    if (!sessionsVisible) {
                        return;
                    }
                    sessionRows.clear();
                    sessionRows.addAll(rows);
                    sessionCount = count;
                    sessionAdapter.notifyDataSetChanged();
                    sessionStatus.setText(describeList(count, groups));
                });
            } catch (Exception exception) {
                main.post(() -> {
                    finishRefresh();
                    if (sessionStatus != null) {
                        sessionStatus.setText(friendly(exception));
                    }
                    // A moved host (new DHCP lease) is the common failure: look for it again
                    // instead of making the user retype an IP.
                    if (isNetworkFailure(exception)) {
                        maybeRediscover();
                    }
                });
            }
        });
    }

    private boolean rediscovering;

    private static boolean isNetworkFailure(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof java.io.IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * The saved host stopped answering, usually because the router handed the PC a new
     * address. The device token outlives the address, so finding the new host is enough.
     */
    private void maybeRediscover() {
        if (rediscovering || prefs.token().isEmpty()) {
            return;
        }
        rediscovering = true;
        final String previous = prefs.server();
        if (sessionStatus != null) {
            sessionStatus.setText("连接不上，正在同一网络里搜索电脑…");
        }
        io.execute(() -> {
            List<String> found = Discovery.scan(7317, 3);
            main.post(() -> {
                rediscovering = false;
                if (found.isEmpty()) {
                    if (sessionStatus != null) {
                        sessionStatus.setText("没找到运行插件的电脑：确认手机和电脑连的是同一个 Wi-Fi");
                    }
                    return;
                }
                String best = found.get(0);
                if (best.equals(previous)) {
                    if (sessionStatus != null) {
                        sessionStatus.setText("找到了 " + best + "，但仍然连不上");
                    }
                    return;
                }
                prefs.setServer(best);
                api = new Api(best, prefs.token());
                LiveLink.get().restart(MainActivity.this);
                if (sessionStatus != null) {
                    sessionStatus.setText("已自动切换到 " + best);
                }
                loadSessions();
            });
        });
    }

    private String describeList(int count, int groups) {        if (count == 0 && groups == 0) {
            return "还没有会话";
        }
        return groups + " 个工作区 · " + count + " 个会话";
    }

    private void startRefresh() {
        if (refreshButton == null) {
            return;
        }
        refreshStartedAt = System.currentTimeMillis();
        refreshButton.setEnabled(false);
        refreshButton.setAlpha(0.5f);
        refreshButton.setText("刷新中");
        if (sessionStatus != null) {
            sessionStatus.setText("正在刷新…");
        }
    }

    private void finishRefresh() {
        if (refreshButton == null) {
            return;
        }
        // A local request can finish in tens of milliseconds; keep the state visible briefly so
        // the tap always looks acknowledged.
        long elapsed = System.currentTimeMillis() - refreshStartedAt;
        long remaining = Math.max(0, 450 - elapsed);
        main.postDelayed(() -> {
            if (refreshButton == null) {
                return;
            }
            refreshButton.setEnabled(true);
            refreshButton.setAlpha(1f);
            refreshButton.setText("刷新");
        }, remaining);
    }

    private List<ListRow> buildSessionRows(JSONObject payload) {
        List<ListRow> rows = new ArrayList<>();
        JSONArray groups = payload.optJSONArray("workspaces");
        int groupCount = groups == null ? 0 : groups.length();

        for (int i = 0; i < groupCount; i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) {
                continue;
            }
            JSONArray items = group.optJSONArray("sessions");
            int count = items == null ? 0 : items.length();
            rows.add(ListRow.section(
                    group.optString("title", "工作区"),
                    count + " 个会话 · " + shortenPath(group.optString("path", ""))));
            for (int j = 0; j < count; j++) {
                JSONObject session = items.optJSONObject(j);
                if (session != null) {
                    rows.add(ListRow.session(session));
                }
            }
        }

        JSONArray ungrouped = payload.optJSONArray("ungrouped");
        int loose = ungrouped == null ? 0 : ungrouped.length();
        if (loose > 0) {
            rows.add(ListRow.section("未分组", loose + " 个会话"));
            for (int j = 0; j < loose; j++) {
                JSONObject session = ungrouped.optJSONObject(j);
                if (session != null) {
                    rows.add(ListRow.session(session));
                }
            }
        }
        return rows;
    }

    private String shortenPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return path;
    }

    /** One entry in the session list: either a workspace header or a session. */
    private static final class ListRow {
        final boolean section;
        final String title;
        final String subtitle;
        final JSONObject session;

        private ListRow(boolean section, String title, String subtitle, JSONObject session) {
            this.section = section;
            this.title = title;
            this.subtitle = subtitle;
            this.session = session;
        }

        static ListRow section(String title, String subtitle) {
            return new ListRow(true, title, subtitle, null);
        }

        static ListRow session(JSONObject session) {
            return new ListRow(false, null, null, session);
        }
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

        pendingRow = row();
        pendingRow.setPadding(dp(12), dp(6), dp(8), dp(2));
        pendingRow.setVisibility(View.GONE);
        pendingLabel = label("", 13, TEXT, false);
        pendingLabel.setSingleLine(true);
        pendingLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        pendingRow.addView(pendingLabel, weight(1));
        Button clearAttachment = button("✕", PANEL, MUTED);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        clearParams.leftMargin = dp(8);
        pendingRow.addView(clearAttachment, clearParams);
        clearAttachment.setOnClickListener(v -> clearPending());
        root.addView(pendingRow);

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
        if (sharedUri != null) {
            Uri uri = sharedUri;
            sharedUri = null;
            stageAttachment(uri);
        }
        loadMessages();
    }

    /** Keeps the chat bounded: the list is virtualized, this bounds the data behind it. */
    private void trimMessages() {
        while (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }
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
                    trimMessages();
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
        if (pendingUri != null) {
            sendAttachment(text);
            return;
        }
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
        stageAttachment(data.getData());
    }

    /** Picking a file only stages it; the user still writes the instruction before sending. */
    private void stageAttachment(Uri uri) {
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
            // Keep the defaults.
        }

        pendingUri = uri;
        pendingName = name;
        pendingSize = size;
        renderPending();
        if (composer != null) {
            composer.requestFocus();
        }
    }

    private void clearPending() {
        pendingUri = null;
        pendingName = null;
        pendingSize = -1;
        renderPending();
    }

    private void renderPending() {
        if (pendingRow == null || pendingLabel == null) {
            return;
        }
        if (pendingUri == null) {
            pendingRow.setVisibility(View.GONE);
            return;
        }
        pendingLabel.setText("待发送：" + pendingName
                + (pendingSize > 0 ? "（" + formatSize(pendingSize) + "）" : ""));
        pendingRow.setVisibility(View.VISIBLE);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void sendAttachment(String caption) {
        if (api == null || sessionId == null || uploading || pendingUri == null) {
            return;
        }

        final Uri uri = pendingUri;
        final String fileName = pendingName == null ? "file" : pendingName;
        final long fileSize = pendingSize;
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
                    clearPending();
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
        // Fixed pixel height: a weighted child inside a Dialog only works when the content
        // view is measured exactly, which is not guaranteed across themes.
        int listHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.52f);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight));
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
                            final long size = entry.optLong("size", -1);
                            final String fullPath = ".".equals(path) ? name : path + "/" + name;

                            LinearLayout rowView = new LinearLayout(this);
                            rowView.setOrientation(LinearLayout.HORIZONTAL);
                            rowView.setGravity(Gravity.CENTER_VERTICAL);
                            rowView.setPadding(dp(8), dp(6), dp(8), dp(6));
                            rowView.setMinimumHeight(dp(48));
                            rowView.setBackground(rowRipple());

                            TextView icon = label(directory ? "📁" : "📄", 16, TEXT, false);
                            icon.setPadding(0, 0, dp(10), 0);
                            rowView.addView(icon);

                            LinearLayout texts = new LinearLayout(this);
                            texts.setOrientation(LinearLayout.VERTICAL);
                            TextView nameView = label(name, 14, TEXT, directory);
                            nameView.setSingleLine(true);
                            nameView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                            texts.addView(nameView);
                            TextView metaView = null;
                            if (!directory && size > 0) {
                                metaView = label(formatSize(size), 11, MUTED, false);
                                texts.addView(metaView);
                            }
                            rowView.addView(texts, weight(1));

                            final TextView meta = metaView;
                            rowView.setOnClickListener(v -> {
                                if (directory) {
                                    current[0] = fullPath;
                                    refresh[0].run();
                                    return;
                                }
                                // Nothing is fetched until the user picks an action.
                                chooseFileAction(target, fullPath, name, status);
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
                Toast.makeText(this, "已经在工作区根目录", Toast.LENGTH_SHORT).show();
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

    /**
     * A ripple clipped to the row. Without a mask the ripple is unbounded and paints a huge
     * circle over the dialog.
     */
    private Drawable rowRipple() {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(10));
        return new RippleDrawable(ColorStateList.valueOf(0x24000000), null, mask);
    }

    /**
     * Downloads into the app cache first. The file is only offered — share it straight to
     * another app, or explicitly save it to 下载/DSH. Nothing lands in Downloads unless the
     * user asks for it.
     */
    /**
     * Tapping a file opens this chooser; nothing is downloaded until an action is picked, so
     * "分享" and "保存到下载" never race each other.
     */
    private void chooseFileAction(String target, String fullPath, String name, TextView status) {
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(new String[] { "分享到其他应用", "保存到下载" }, (dialog, which) -> {
                    if (fileDownloadInFlight) {
                        Toast.makeText(this, "上一个文件还在处理…", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (which == 0) {
                        shareWorkspaceFile(target, fullPath, status);
                    } else {
                        saveWorkspaceFile(target, fullPath, name, status);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void shareWorkspaceFile(String target, String fullPath, TextView status) {
        fileDownloadInFlight = true;
        status.setText("正在准备分享…");
        io.execute(() -> {
            try {
                File file = downloadToCache(target, fullPath);
                main.post(() -> {
                    fileDownloadInFlight = false;
                    status.setText("已下载 " + file.getName() + "（" + formatSize(file.length()) + "）");
                    shareFile(file);
                });
            } catch (Exception exception) {
                main.post(() -> {
                    fileDownloadInFlight = false;
                    status.setText("下载失败：" + friendly(exception));
                });
            }
        });
    }

    /** Only 分享 needs a local copy; 保存到下载 streams straight into MediaStore. */
    private File downloadToCache(String target, String fullPath) throws Exception {
        JSONObject signed = api.signFile(target, fullPath);
        String url = signed.optString("url", "");
        if (url.isEmpty()) {
            throw new Exception("服务端没有返回下载地址");
        }
        String fileName = safeFileName(signed.optString("name", "file"));
        File file = new File(ShareProvider.shareDirectory(this), fileName);
        try (OutputStream out = new FileOutputStream(file)) {
            api.download(url, out);
        }
        return file;
    }

    private void saveWorkspaceFile(String target, String fullPath, String fallbackName, TextView status) {
        fileDownloadInFlight = true;
        status.setText("正在保存到下载…");
        io.execute(() -> {
            Uri uri = null;
            try {
                JSONObject signed = api.signFile(target, fullPath);
                String url = signed.optString("url", "");
                if (url.isEmpty()) {
                    throw new Exception("服务端没有返回下载地址");
                }
                String fileName = safeFileName(signed.optString("name", fallbackName));

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
                    main.post(() -> {
                        fileDownloadInFlight = false;
                        status.setText("已保存到 下载/DSH/" + fileName);
                    });
                } else {
                    File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "DSH");
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new Exception("无法创建下载目录");
                    }
                    File file = new File(dir, fileName);
                    try (OutputStream out = new FileOutputStream(file)) {
                        api.download(url, out);
                    }
                    main.post(() -> {
                        fileDownloadInFlight = false;
                        status.setText("已保存到 " + file.getAbsolutePath());
                    });
                }
            } catch (Exception exception) {
                if (uri != null) {
                    try {
                        getContentResolver().delete(uri, null, null);
                    } catch (Exception ignored) {
                        // Best-effort cleanup of the half-written entry.
                    }
                }
                main.post(() -> {
                    fileDownloadInFlight = false;
                    status.setText("保存失败：" + friendly(exception));
                });
            }
        });
    }

    private void shareFile(File file) {
        try {
            Uri uri = ShareProvider.uriFor(this, file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(getContentResolver().getType(uri));
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, file.getName());
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(send, "分享到");
            // We accept ACTION_SEND ourselves; offering that as a share target is noise.
            chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                    new ComponentName[] { new ComponentName(this, MainActivity.class) });
            startActivity(chooser);
        } catch (Exception exception) {
            Toast.makeText(this, "分享失败：" + friendly(exception), Toast.LENGTH_LONG).show();
        }
    }

    /** File names come from the host; strip anything that could escape the share directory. */
    private static String safeFileName(String name) {
        String cleaned = name == null ? "" : name.replace('\\', '_').replace('/', '_').trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) {
            return "file";
        }
        return cleaned;
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
            sessionStatus.setText(connected ? (sessionCount == 0 ? "还没有会话" : sessionCount + " 个会话")
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
        private static final int TYPE_SECTION = 0;
        private static final int TYPE_SESSION = 1;

        @Override
        public int getCount() {
            return sessionRows.size();
        }

        @Override
        public Object getItem(int position) {
            return sessionRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return sessionRows.get(position).section ? TYPE_SECTION : TYPE_SESSION;
        }

        @Override
        public boolean isEnabled(int position) {
            return !sessionRows.get(position).section;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ListRow item = sessionRows.get(position);
            if (item.section) {
                return sectionView(item, convertView);
            }
            return sessionView(item.session, convertView);
        }

        private View sectionView(ListRow item, View convertView) {
            LinearLayout box;
            TextView title;
            TextView subtitle;
            if (convertView == null || !(convertView.getTag() instanceof SectionHolder)) {
                box = new LinearLayout(MainActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(16), dp(16), dp(16), dp(6));
                box.setBackgroundColor(Color.WHITE);

                title = label("", 13, MUTED, true);
                subtitle = label("", 11, MUTED, false);
                subtitle.setSingleLine(true);
                subtitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                box.addView(title);
                box.addView(subtitle);

                SectionHolder holder = new SectionHolder();
                holder.title = title;
                holder.subtitle = subtitle;
                box.setTag(holder);
                convertView = box;
            } else {
                box = (LinearLayout) convertView;
                SectionHolder holder = (SectionHolder) convertView.getTag();
                title = holder.title;
                subtitle = holder.subtitle;
            }
            title.setText(item.title);
            subtitle.setText(item.subtitle);
            return convertView;
        }

        private View sessionView(JSONObject session, View convertView) {
            SessionHolder holder;
            if (convertView == null || !(convertView.getTag() instanceof SessionHolder)) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(28), dp(11), dp(16), dp(11));
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

    private static final class SectionHolder {
        TextView title;
        TextView subtitle;
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
            LinearLayout row;
            TextView bubble;
            View leftSpacer;
            View rightSpacer;
            Drawable userBg;
            Drawable assistantBg;
            if (convertView == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);

                leftSpacer = new View(MainActivity.this);
                rightSpacer = new View(MainActivity.this);
                bubble = new TextView(MainActivity.this);
                bubble.setTextIsSelectable(true);
                bubble.setLineSpacing(dp(2), 1f);

                row.addView(leftSpacer, new LinearLayout.LayoutParams(0, 1, 0));
                row.addView(bubble, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                row.addView(rightSpacer, new LinearLayout.LayoutParams(0, 1, 0));

                // One drawable per recycled row instead of per bind.
                userBg = bubbleUser();
                assistantBg = bubbleAssistant();
                row.setTag(new Object[] { bubble, leftSpacer, rightSpacer, userBg, assistantBg });
                convertView = row;
            } else {
                row = (LinearLayout) convertView;
                Object[] tag = (Object[]) row.getTag();
                bubble = (TextView) tag[0];
                leftSpacer = (View) tag[1];
                rightSpacer = (View) tag[2];
                userBg = (Drawable) tag[3];
                assistantBg = (Drawable) tag[4];
            }

            JSONObject message = messages.get(position);
            String role = message.optString("role");
            String raw = message.optString("text");
            if ("tool".equals(role)) {
                bubble.setText(raw);
            } else {
                CharSequence rendered = markdownCache.get(message);
                if (rendered == null) {
                    rendered = Markdown.render(raw);
                    markdownCache.put(message, rendered);
                }
                bubble.setText(rendered);
            }

            boolean isUser = "user".equals(role);
            bubble.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.76f));

            LinearLayout.LayoutParams bubbleParams = (LinearLayout.LayoutParams) bubble.getLayoutParams();
            bubbleParams.leftMargin = dp(12);
            bubbleParams.rightMargin = dp(12);
            bubbleParams.topMargin = dp(3);
            bubbleParams.bottomMargin = dp(3);
            bubble.setLayoutParams(bubbleParams);

            // A weighted spacer on the opposite side positions the bubble without relying on
            // gravity, which stays stable across recycled rows.
            LinearLayout.LayoutParams leftParams = (LinearLayout.LayoutParams) leftSpacer.getLayoutParams();
            leftParams.weight = isUser ? 1f : 0f;
            leftSpacer.setLayoutParams(leftParams);

            LinearLayout.LayoutParams rightParams = (LinearLayout.LayoutParams) rightSpacer.getLayoutParams();
            rightParams.weight = isUser ? 0f : 1f;
            rightSpacer.setLayoutParams(rightParams);

            if (isUser) {
                bubble.setTextSize(15);
                bubble.setTextColor(Color.WHITE);
                bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
                bubble.setBackground(userBg);
            } else if ("tool".equals(role)) {
                bubble.setTextSize(12);
                bubble.setTextColor(MUTED);
                bubble.setPadding(dp(14), dp(4), dp(14), dp(4));
                bubble.setBackground(null);
            } else {
                bubble.setTextSize(15);
                bubble.setTextColor(TEXT);
                bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
                bubble.setBackground(assistantBg);
            }
            return convertView;
        }
    }

    /**
     * Each bubble gets its own drawable: sharing one instance across recycled views makes the
     * drawable callback bounce between them, which shows up as bubbles that lose their
     * background.
     */
    private Drawable bubbleUser() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ACCENT);
        shape.setCornerRadius(dp(14));
        return shape;
    }

    private Drawable bubbleAssistant() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(PANEL);
        shape.setCornerRadius(dp(14));
        return shape;
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
