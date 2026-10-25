package com.aint.dshremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private View approvalSection;
    private TextView approvalHeader;
    private ScrollView approvalScroll;
    private boolean approvalExpanded = true;
    /** Every approval still waiting, from any session, so none is dropped while off-screen. */
    private final Map<String, JSONObject> liveApprovals = new java.util.LinkedHashMap<>();
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

    /** One file waiting to be sent with the next message. */
    private static final class PendingFile {
        final Uri uri;
        final String name;
        final long size;

        PendingFile(Uri uri, String name, long size) {
            this.uri = uri;
            this.name = name;
            this.size = size;
        }
    }

    private final List<PendingFile> pendingFiles = new ArrayList<>();
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

        // A multi-file share arrives as ClipData; stage every item.
        List<Uri> shared = new ArrayList<>();
        if (intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
            ClipData clip = intent.getClipData();
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri item = clip.getItemAt(i).getUri();
                if (item != null) {
                    shared.add(item);
                }
            }
        }
        if (shared.isEmpty() && uri != null) {
            shared.add(uri);
        }
        if (shared.isEmpty()) {
            return;
        }

        if (sessionId != null && chatAdapter != null) {
            for (Uri item : shared) {
                stageAttachment(item);
            }
            Toast.makeText(this, shared.size() == 1 ? "已添加待发送附件" : "已添加 " + shared.size() + " 个待发送文件",
                    Toast.LENGTH_SHORT).show();
        } else {
            sharedUri = shared.get(0);
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

        View root = getLayoutInflater().inflate(R.layout.activity_pairing, null);
        final EditText server = root.findViewById(R.id.pairing_server);
        final EditText code = root.findViewById(R.id.pairing_code);
        final EditText device = root.findViewById(R.id.pairing_device);
        Button pair = root.findViewById(R.id.pairing_submit);
        Button discover = root.findViewById(R.id.pairing_discover);
        final TextView error = root.findViewById(R.id.pairing_error);

        server.setText(prefs.server());
        server.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        device.setInputType(InputType.TYPE_CLASS_TEXT);
        device.setText(android.os.Build.MODEL);

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
                    JSONObject result = Api.pair(targetServer, targetCode,
                            deviceName.isEmpty() ? "Android" : deviceName, prefs.deviceId());
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
        pendingFiles.clear();
        pendingRow = null;
        pendingLabel = null;

        View root = getLayoutInflater().inflate(R.layout.activity_sessions, null);

        refreshButton = root.findViewById(R.id.sessions_refresh);
        sessionStatus = root.findViewById(R.id.sessions_status);
        ListView list = root.findViewById(R.id.sessions_list);
        Button unpair = root.findViewById(R.id.sessions_unpair);

        sessionAdapter = new SessionAdapter();
        list.setAdapter(sessionAdapter);
        refreshButton.setOnClickListener(v -> loadSessions(true));

        unpair.setOnClickListener(v -> confirmUnpair());

        setScreen(root);
        loadSessions();
    }

    /** Unpairing drops the token, so it asks first instead of taking one stray tap. */
    private void confirmUnpair() {
        new AlertDialog.Builder(this)
                .setTitle("解除配对？")
                .setMessage("解除后这台手机会删除本地令牌，必须重新输入配对码才能连回来。\n\n"
                        + "只是想切换电脑的话，可以先用「自动发现电脑」。")
                .setPositiveButton("解除配对", (dialog, which) -> {
                    prefs.clear();
                    LiveLink.get().stop();
                    stopService(new Intent(this, RemoteService.class));
                    api = null;
                    sessionRows.clear();
                    if (sessionAdapter != null) {
                        sessionAdapter.notifyDataSetChanged();
                    }
                    showPairing();
                })
                .setNegativeButton("取消", null)
                .show();
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
                    } else if (isUnauthorized(exception)) {
                        // The token expired (24h of inactivity) or was revoked on the desktop.
                        prefs.clear();
                        LiveLink.get().stop();
                        Toast.makeText(this, "授权已过期，请重新配对", Toast.LENGTH_LONG).show();
                        showPairing();
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

    private static boolean isUnauthorized(Throwable error) {
        return error instanceof Api.ApiException
                && ((Api.ApiException) error).code().equals("unauthorized");
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

    /**
     * Builds the grouped list with running work first: a workspace that currently has a
     * running session floats above the idle ones, and the rest keep their original order.
     */
    private List<ListRow> buildSessionRows(JSONObject payload) {
        List<ListRow> rows = new ArrayList<>();

        // Collect groups first so they can be ordered by activity.
        List<Group> ordered = new ArrayList<>();
        JSONArray groups = payload.optJSONArray("workspaces");
        int groupCount = groups == null ? 0 : groups.length();
        for (int i = 0; i < groupCount; i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) {
                continue;
            }
            JSONArray items = group.optJSONArray("sessions");
            Group entry = new Group();
            entry.title = group.optString("title", "工作区");
            entry.detail = (items == null ? 0 : items.length()) + " 个会话 · "
                    + shortenPath(group.optString("path", ""));
            for (int j = 0; j < (items == null ? 0 : items.length()); j++) {
                JSONObject session = items.optJSONObject(j);
                if (session != null) {
                    entry.sessions.add(session);
                    if (session.optBoolean("running")) {
                        entry.running = true;
                    }
                }
            }
            ordered.add(entry);
        }

        JSONArray ungrouped = payload.optJSONArray("ungrouped");
        if (ungrouped != null && ungrouped.length() > 0) {
            Group loose = new Group();
            loose.title = "未分组";
            loose.detail = ungrouped.length() + " 个会话";
            for (int j = 0; j < ungrouped.length(); j++) {
                JSONObject session = ungrouped.optJSONObject(j);
                if (session != null) {
                    loose.sessions.add(session);
                    if (session.optBoolean("running")) {
                        loose.running = true;
                    }
                }
            }
            ordered.add(loose);
        }

        // Stable sort: running groups first, everything else keeps its registry order.
        List<Group> sorted = new ArrayList<>(ordered);
        sorted.sort((a, b) -> Boolean.compare(b.running, a.running));

        for (Group entry : sorted) {
            rows.add(ListRow.section(entry.title, entry.detail));
            for (JSONObject session : entry.sessions) {
                rows.add(ListRow.session(session));
            }
        }
        return rows;
    }

    /** One workspace (or the ungrouped bucket) while the list is being assembled. */
    private static final class Group {
        String title;
        String detail;
        boolean running;
        final List<JSONObject> sessions = new ArrayList<>();
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

        View root = getLayoutInflater().inflate(R.layout.activity_chat, null);

        TextView back = root.findViewById(R.id.chat_back);
        TextView titleView = root.findViewById(R.id.chat_title);
        chatStatus = root.findViewById(R.id.chat_status);
        chatList = root.findViewById(R.id.chat_list);
        approvalSection = root.findViewById(R.id.approval_section);
        approvalHeader = root.findViewById(R.id.approval_header);
        approvalScroll = root.findViewById(R.id.approval_scroll);
        approvalBox = root.findViewById(R.id.approval_box);
        pendingRow = root.findViewById(R.id.pending_row);
        pendingLabel = root.findViewById(R.id.pending_label);
        Button clearAttachment = root.findViewById(R.id.pending_clear);
        attachButton = root.findViewById(R.id.chat_attach);
        composer = root.findViewById(R.id.chat_composer);
        Button send = root.findViewById(R.id.chat_send);
        Button files = root.findViewById(R.id.chat_files);
        Button stop = root.findViewById(R.id.chat_stop);

        // The tray scrolls instead of growing without bound.
        approvalScroll.getLayoutParams().height = (int) (getResources().getDisplayMetrics().heightPixels * 0.34f);
        approvalScroll.setLayoutParams(approvalScroll.getLayoutParams());
        approvalHeader.setOnClickListener(v -> {
            approvalExpanded = !approvalExpanded;
            updateApprovalTray();
        });

        titleView.setText(sessionTitle == null ? "" : sessionTitle);
        chatStatus.setText(LiveLink.get().connected() ? "已连接" : "连接中…");
        back.setOnClickListener(v -> onBackPressed());
        clearAttachment.setOnClickListener(v -> clearPending());

        chatAdapter = new ChatAdapter();
        chatList.setAdapter(chatAdapter);

        messages.clear();
        streamingMessage = null;
        streamBuffer = null;

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
        // Approvals from other sessions keep waiting here until they are answered or expire.
        for (JSONObject pending : new ArrayList<>(liveApprovals.values())) {
            addApprovalCard(pending);
        }
        updateApprovalTray();
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
        if (!pendingFiles.isEmpty()) {
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
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQ_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FILE || resultCode != RESULT_OK || data == null) {
            return;
        }
        if (data.getClipData() != null) {
            ClipData clip = data.getClipData();
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) {
                    stageAttachment(uri);
                }
            }
            return;
        }
        if (data.getData() != null) {
            stageAttachment(data.getData());
        }
    }

    /** Picking files only stages them; the user still writes the instruction before sending. */
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

        pendingFiles.add(new PendingFile(uri, name == null ? "file" : name, size));
        renderPending();
        if (composer != null) {
            composer.requestFocus();
        }
    }

    private void clearPending() {
        pendingFiles.clear();
        renderPending();
    }

    private void renderPending() {
        if (pendingRow == null || pendingLabel == null) {
            return;
        }
        if (pendingFiles.isEmpty()) {
            pendingRow.setVisibility(View.GONE);
            return;
        }

        if (pendingFiles.size() == 1) {
            PendingFile file = pendingFiles.get(0);
            pendingLabel.setText("待发送：" + file.name
                    + (file.size > 0 ? "（" + formatSize(file.size) + "）" : ""));
        } else {
            long total = 0;
            StringBuilder names = new StringBuilder();
            for (PendingFile file : pendingFiles) {
                if (file.size > 0) {
                    total += file.size;
                }
                if (names.length() > 0) {
                    names.append("、");
                }
                names.append(file.name);
            }
            pendingLabel.setText(pendingFiles.size() + " 个文件（" + formatSize(total) + "）：" + names);
        }
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

    /**
     * Uploads every staged file as one message: each file is staged with its own receipt,
     * then a single prompt binds them all together with the typed text.
     */
    private void sendAttachment(String caption) {
        if (api == null || sessionId == null || uploading || pendingFiles.isEmpty()) {
            return;
        }

        final List<PendingFile> files = new ArrayList<>(pendingFiles);
        final String target = sessionId;

        uploading = true;
        if (attachButton != null) {
            attachButton.setEnabled(false);
            attachButton.setAlpha(0.4f);
        }
        if (chatStatus != null) {
            chatStatus.setText(files.size() == 1
                    ? "正在上传 " + files.get(0).name + "…"
                    : "正在上传 " + files.size() + " 个文件…");
        }

        io.execute(() -> {
            try {
                JSONArray receipts = new JSONArray();
                for (int index = 0; index < files.size(); index++) {
                    PendingFile file = files.get(index);
                    if (chatStatus != null && files.size() > 1) {
                        final int current = index + 1;
                        main.post(() -> chatStatus.setText("正在上传 " + current + "/" + files.size() + "…"));
                    }
                    try (InputStream stream = getContentResolver().openInputStream(file.uri)) {
                        if (stream == null) {
                            throw new Exception("无法读取 " + file.name);
                        }
                        JSONObject staged = api.upload(target, file.name, stream, file.size, "", false);
                        String receiptId = staged.optString("receiptId", "");
                        if (receiptId.isEmpty()) {
                            throw new Exception("服务端没有返回回执");
                        }
                        receipts.put(receiptId);
                    }
                }

                api.sendBatch(target, caption, receipts);
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
                        chatStatus.setText(files.size() == 1
                                ? "已发送 " + files.get(0).name
                                : "已发送 " + files.size() + " 个文件");
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
        LinearLayout box = (LinearLayout) getLayoutInflater().inflate(R.layout.dialog_workspace, null);
        TextView pathView = box.findViewById(R.id.workspace_path);
        ScrollView scroll = box.findViewById(R.id.workspace_scroll);
        LinearLayout rows = box.findViewById(R.id.workspace_rows);
        TextView status = box.findViewById(R.id.workspace_status);
        Button up = box.findViewById(R.id.workspace_up);
        Button close = box.findViewById(R.id.workspace_close);
        View selectionBar = box.findViewById(R.id.workspace_selection);
        Button shareAll = box.findViewById(R.id.workspace_share_all);
        Button saveAll = box.findViewById(R.id.workspace_save_all);
        Button cancelSelection = box.findViewById(R.id.workspace_cancel_select);

        // The list gets a fixed height: a weighted child only resolves when the dialog is
        // measured exactly, which the platform does not guarantee here.
        LinearLayout.LayoutParams scrollParams = (LinearLayout.LayoutParams) scroll.getLayoutParams();
        scrollParams.weight = 0f;
        scrollParams.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.52f);
        scroll.setLayoutParams(scrollParams);

        dialog.setContentView(box);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        final String[] current = {"."};
        final Set<String> selected = new LinkedHashSet<>();
        final boolean[] selectionMode = {false};
        final List<JSONObject> items = new ArrayList<>();
        final Runnable[] paint = new Runnable[1];
        final Runnable[] refresh = new Runnable[1];

        paint[0] = () -> {
            pathView.setText(current[0]);
            rows.removeAllViews();

            if (items.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("（空目录）");
                empty.setTextSize(13);
                empty.setTextColor(getColor(R.color.muted));
                rows.addView(empty);
            }

            for (JSONObject entry : items) {
                final String name = entry.optString("name");
                final boolean directory = entry.optBoolean("directory");
                final long size = entry.optLong("size", -1);
                final String fullPath = ".".equals(current[0]) ? name : current[0] + "/" + name;

                View rowView = getLayoutInflater().inflate(R.layout.item_workspace_entry, rows, false);
                TextView check = rowView.findViewById(R.id.entry_check);
                TextView icon = rowView.findViewById(R.id.entry_icon);
                TextView nameView = rowView.findViewById(R.id.entry_name);
                TextView meta = rowView.findViewById(R.id.entry_meta);

                boolean picked = selected.contains(fullPath);
                if (selectionMode[0] && !directory) {
                    check.setVisibility(View.VISIBLE);
                    check.setText(picked ? "☑" : "☐");
                    check.setTextColor(getColor(picked ? R.color.accent : R.color.muted));
                } else {
                    check.setVisibility(View.GONE);
                }

                icon.setText(directory ? "📁" : "📄");
                nameView.setText(name);
                nameView.setTypeface(null, directory
                        ? android.graphics.Typeface.BOLD
                        : android.graphics.Typeface.NORMAL);
                if (!directory && size > 0) {
                    meta.setText(formatSize(size));
                    meta.setVisibility(View.VISIBLE);
                } else {
                    meta.setVisibility(View.GONE);
                }

                rowView.setOnClickListener(v -> {
                    if (selectionMode[0] && !directory) {
                        if (!selected.remove(fullPath)) {
                            selected.add(fullPath);
                        }
                        paint[0].run();
                        return;
                    }
                    if (directory) {
                        current[0] = fullPath;
                        refresh[0].run();
                        return;
                    }
                    // Nothing is fetched until the user picks an action.
                    chooseFileAction(target, fullPath, name, status);
                });
                rowView.setOnLongClickListener(v -> {
                    if (directory) {
                        return false;
                    }
                    selectionMode[0] = true;
                    selected.add(fullPath);
                    paint[0].run();
                    return true;
                });
                rows.addView(rowView);
            }

            selectionBar.setVisibility(selectionMode[0] ? View.VISIBLE : View.GONE);
            status.setText(selectionMode[0]
                    ? "已选 " + selected.size() + " 项（长按或点选，可多选）"
                    : items.size() + " 项 · 长按文件可多选");
        };

        refresh[0] = () -> {
            String path = current[0];
            pathView.setText(path);
            status.setText("读取中…");
            io.execute(() -> {
                try {
                    JSONObject result = api.workspace(target, path);
                    JSONArray entries = result.optJSONArray("entries");
                    List<JSONObject> fetched = new ArrayList<>();
                    for (int i = 0; i < (entries == null ? 0 : entries.length()); i++) {
                        fetched.add(entries.getJSONObject(i));
                    }
                    main.post(() -> {
                        items.clear();
                        items.addAll(fetched);
                        selected.clear();
                        selectionMode[0] = false;
                        paint[0].run();
                    });
                } catch (Exception exception) {
                    main.post(() -> status.setText(friendly(exception)));
                }
            });
        };

        up.setOnClickListener(v -> {
            if (selectionMode[0]) {
                selectionMode[0] = false;
                selected.clear();
                paint[0].run();
                return;
            }
            if (".".equals(current[0])) {
                Toast.makeText(this, "已经在工作区根目录", Toast.LENGTH_SHORT).show();
                return;
            }
            int cut = current[0].lastIndexOf('/');
            current[0] = cut > 0 ? current[0].substring(0, cut) : ".";
            refresh[0].run();
        });

        close.setOnClickListener(v -> {
            if (selectionMode[0]) {
                selectionMode[0] = false;
                selected.clear();
                paint[0].run();
                return;
            }
            dialog.dismiss();
        });

        cancelSelection.setOnClickListener(v -> {
            selectionMode[0] = false;
            selected.clear();
            paint[0].run();
        });

        shareAll.setOnClickListener(v -> {
            if (selected.isEmpty() || fileDownloadInFlight) {
                return;
            }
            shareWorkspaceFiles(target, new ArrayList<>(selected), status);
        });

        saveAll.setOnClickListener(v -> {
            if (selected.isEmpty() || fileDownloadInFlight) {
                return;
            }
            saveWorkspaceFiles(target, new ArrayList<>(selected), status);
        });

        refresh[0].run();
    }

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

    /** Downloads every selected file, then hands them to one share sheet as a group. */
    private void shareWorkspaceFiles(String target, List<String> paths, TextView status) {
        fileDownloadInFlight = true;
        status.setText("正在准备 " + paths.size() + " 个文件…");
        io.execute(() -> {
            List<Uri> uris = new ArrayList<>();
            String failure = null;
            for (int index = 0; index < paths.size(); index++) {
                final int current = index + 1;
                main.post(() -> status.setText("正在下载 " + current + "/" + paths.size() + "…"));
                try {
                    File file = downloadToCache(target, paths.get(index));
                    uris.add(ShareProvider.uriFor(this, file));
                } catch (Exception exception) {
                    failure = friendly(exception);
                    break;
                }
            }
            final String error = failure;
            final List<Uri> ready = uris;
            main.post(() -> {
                fileDownloadInFlight = false;
                if (!ready.isEmpty()) {
                    status.setText("已准备 " + ready.size() + " 个文件");
                    shareFiles(ready);
                }
                if (error != null) {
                    Toast.makeText(this, "下载失败：" + error, Toast.LENGTH_LONG).show();
                }
            });
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
            try {
                String saved = saveOneToDownloads(target, fullPath, fallbackName);
                main.post(() -> {
                    fileDownloadInFlight = false;
                    status.setText("已保存到 下载/DSH/" + saved);
                });
            } catch (Exception exception) {
                main.post(() -> {
                    fileDownloadInFlight = false;
                    status.setText("保存失败：" + friendly(exception));
                });
            }
        });
    }

    private void saveWorkspaceFiles(String target, List<String> paths, TextView status) {
        fileDownloadInFlight = true;
        status.setText("正在保存 " + paths.size() + " 个文件…");
        io.execute(() -> {
            int saved = 0;
            String failure = null;
            for (int index = 0; index < paths.size(); index++) {
                final int current = index + 1;
                main.post(() -> status.setText("正在保存 " + current + "/" + paths.size() + "…"));
                try {
                    saveOneToDownloads(target, paths.get(index), "file");
                    saved++;
                } catch (Exception exception) {
                    failure = friendly(exception);
                    break;
                }
            }
            final int done = saved;
            final String error = failure;
            main.post(() -> {
                fileDownloadInFlight = false;
                status.setText(error == null
                        ? "已保存 " + done + " 个文件到 下载/DSH/"
                        : "已保存 " + done + " 个，失败：" + error);
            });
        });
    }

    /** Streams one workspace file into MediaStore; returns the stored display name. */
    private String saveOneToDownloads(String target, String fullPath, String fallbackName) throws Exception {
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
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new Exception("无法创建下载文件");
            }
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new Exception("无法写入下载文件");
                }
                api.download(url, out);
            }
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "DSH");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new Exception("无法创建下载目录");
            }
            File file = new File(dir, fileName);
            try (OutputStream out = new FileOutputStream(file)) {
                api.download(url, out);
            }
        }
        return fileName;
    }

    private void shareFile(File file) {
        shareFiles(Collections.singletonList(ShareProvider.uriFor(this, file)));
    }

    private void shareFiles(List<Uri> uris) {
        if (uris.isEmpty()) {
            return;
        }
        try {
            Intent send;
            if (uris.size() == 1) {
                Uri uri = uris.get(0);
                send = new Intent(Intent.ACTION_SEND);
                send.setType(getContentResolver().getType(uri));
                send.putExtra(Intent.EXTRA_STREAM, uri);
            } else {
                send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                send.setType("*/*");
                send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(uris));
            }
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
            String approvalId = frame.optString("id");
            if (!approvalId.isEmpty()) {
                liveApprovals.put(approvalId, frame);
            }
            addApprovalCard(frame);
        } else if ("approval-resolved".equals(kind)) {
            String resolvedId = frame.optString("id");
            liveApprovals.remove(resolvedId);
            View view = approvalViews.remove(resolvedId);
            if (view != null && approvalBox != null) {
                approvalBox.removeView(view);
            }
            updateApprovalTray();
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

    /**
     * Renders one approval. Approvals from other sessions are kept too: they are answered
     * through the same broker, so hiding them only made requests look lost.
     */
    private void addApprovalCard(JSONObject approval) {
        if (approvalBox == null) {
            return;
        }
        String id = approval.optString("id");
        if (id.isEmpty() || approvalViews.containsKey(id)) {
            return;
        }

        View card = getLayoutInflater().inflate(R.layout.item_approval, approvalBox, false);
        TextView title = card.findViewById(R.id.approval_title);
        TextView reasonView = card.findViewById(R.id.approval_reason);
        Button allow = card.findViewById(R.id.approval_allow);
        Button deny = card.findViewById(R.id.approval_deny);

        String tool = approval.optString("toolName", "工具调用");
        String session = approval.optString("sessionId", "");
        String label = sessionId != null && sessionId.equals(session)
                ? "待审批 · " + tool
                : "待审批 · " + tool + "（" + sessionNameOf(session) + "）";
        title.setText(label);

        String reason = approval.optString("reason", "");
        reasonView.setText(reason);
        reasonView.setVisibility(reason.isEmpty() ? View.GONE : View.VISIBLE);

        allow.setOnClickListener(v -> answer(id, "allowed-once", card));
        deny.setOnClickListener(v -> answer(id, "rejected", card));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);

        approvalViews.put(id, card);
        approvalBox.addView(card, params);
        updateApprovalTray();
    }

    /** Short label for a session id, using the cached workspace listing. */
    private String sessionNameOf(String id) {
        for (ListRow row : sessionRows) {
            if (!row.section && row.session != null && id.equals(row.session.optString("id"))) {
                return titleOf(row.session);
            }
        }
        return id.isEmpty() ? "其它会话" : "其它会话";
    }

    /** Header shows how many are waiting; the list scrolls and can be folded away. */
    private void updateApprovalTray() {
        if (approvalSection == null || approvalHeader == null || approvalScroll == null) {
            return;
        }
        int count = approvalViews.size();
        if (count == 0) {
            approvalSection.setVisibility(View.GONE);
            approvalExpanded = true;
            return;
        }

        approvalSection.setVisibility(View.VISIBLE);
        approvalHeader.setText("待审批 " + count + " 项 " + (approvalExpanded ? "▾" : "▸"));
        approvalScroll.setVisibility(approvalExpanded ? View.VISIBLE : View.GONE);
    }

    private void answer(String id, String outcome, View card) {
        io.execute(() -> {
            try {
                api.answer(id, outcome);
            } catch (Exception exception) {
                main.post(() -> Toast.makeText(this, friendly(exception), Toast.LENGTH_SHORT).show());
            }
            main.post(() -> {
                liveApprovals.remove(id);
                updateApprovalTray();
                approvalViews.remove(id);
                if (approvalBox != null) {
                    approvalBox.removeView(card);
                }
                updateApprovalTray();
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
                return sectionView(item, convertView, parent);
            }
            return sessionView(item.session, convertView, parent);
        }

        private View sectionView(ListRow item, View convertView, ViewGroup parent) {
            View view = convertView != null
                    ? convertView
                    : getLayoutInflater().inflate(R.layout.item_session_section, parent, false);
            TextView title = view.findViewById(R.id.session_section_title);
            TextView detail = view.findViewById(R.id.session_section_detail);
            title.setText(item.title);
            detail.setText(item.subtitle);
            return view;
        }

        private View sessionView(JSONObject session, View convertView, ViewGroup parent) {
            View view = convertView != null
                    ? convertView
                    : getLayoutInflater().inflate(R.layout.item_session, parent, false);

            TextView title = view.findViewById(R.id.session_title);
            TextView subtitle = view.findViewById(R.id.session_subtitle);
            View dot = view.findViewById(R.id.session_dot);
            View row = view.findViewById(R.id.session_row);

            boolean running = session.optBoolean("running");
            title.setText(titleOf(session));
            subtitle.setText((running ? "运行中" : "空闲") + " · " + session.optString("cwd", ""));

            GradientDrawable dotShape = new GradientDrawable();
            dotShape.setShape(GradientDrawable.OVAL);
            dotShape.setColor(running ? GREEN : 0xFFC4C8CE);
            dot.setBackground(dotShape);

            final String id = session.optString("id");
            final String titleText = titleOf(session);
            row.setOnClickListener(v -> {
                sessionId = id;
                sessionTitle = titleText;
                showChat();
            });
            return view;
        }
    }

    private static final class SectionHolder {
        TextView title;
        TextView subtitle;
    }

    private final class ChatAdapter extends BaseAdapter {
        private static final int TYPE_USER = 0;
        private static final int TYPE_ASSISTANT = 1;
        private static final int TYPE_TOOL = 2;

        /**
         * A bubble may use at most this share of the viewport. Without the cap a long message
         * grows until only the opposite margin is left, so the bubble looks glued to the far
         * edge instead of keeping air on both sides.
         */
        private final int bubbleMaxWidth =
                (int) (getResources().getDisplayMetrics().widthPixels * 0.76f);

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
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int position) {
            String role = messages.get(position).optString("role");
            if ("user".equals(role)) {
                return TYPE_USER;
            }
            if ("tool".equals(role)) {
                return TYPE_TOOL;
            }
            return TYPE_ASSISTANT;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            int type = getItemViewType(position);
            View view = convertView;
            if (view == null) {
                int layout = type == TYPE_USER ? R.layout.item_message_user
                        : type == TYPE_TOOL ? R.layout.item_message_tool
                        : R.layout.item_message_assistant;
                view = getLayoutInflater().inflate(layout, parent, false);
            }

            TextView text = view.findViewById(R.id.message_text);
            JSONObject message = messages.get(position);
            String raw = message.optString("text");
            if (type == TYPE_TOOL) {
                // Tool output is plain text, not a bubble: it keeps the full width.
                text.setText(raw);
            } else {
                text.setMaxWidth(bubbleMaxWidth);
                CharSequence rendered = markdownCache.get(message);
                if (rendered == null) {
                    rendered = Markdown.render(raw);
                    markdownCache.put(message, rendered);
                }
                text.setText(rendered);
            }
            return view;
        }
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
