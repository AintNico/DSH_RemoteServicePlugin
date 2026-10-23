package com.aint.dshremote;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements LiveLink.Listener {

    private static final int ACCENT = 0xFF1A73E8;
    private static final int TEXT = 0xFF1B1F27;
    private static final int MUTED = 0xFF7A808C;
    private static final int PANEL = 0xFFF2F4F7;
    private static final int DANGER = 0xFFD93025;

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

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFICATIONS);
        }
    }

    // ---------- 配对 ----------

    private void showPairing() {
        sessionsVisible = false;
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
        root.addView(space(16));

        Button pair = primaryButton("配对");
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

        LinearLayout root = column(0);

        LinearLayout header = row();
        header.setPadding(dp(16), dp(14), dp(16), dp(10));
        header.addView(label("会话", 20, TEXT, true), weight(1));
        Button refresh = smallButton("刷新");
        header.addView(refresh);
        root.addView(header);

        sessionStatus = label("正在加载…", 12, MUTED, false);
        sessionStatus.setPadding(dp(16), 0, dp(16), dp(6));
        root.addView(sessionStatus);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        sessionAdapter = new SessionAdapter();
        list.setAdapter(sessionAdapter);
        root.addView(list, verticalWeight(1));

        Button unpair = smallButton("解除配对");
        LinearLayout footer = row();
        footer.setPadding(dp(16), dp(8), dp(16), dp(16));
        footer.addView(unpair);
        root.addView(footer);

        refresh.setOnClickListener(v -> loadSessions());
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < sessions.size()) {
                JSONObject session = sessions.get(position);
                sessionId = session.optString("id");
                sessionTitle = titleOf(session);
                showChat();
            }
        });
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
        LinearLayout root = column(0);

        LinearLayout header = row();
        header.setPadding(dp(10), dp(10), dp(14), dp(8));
        TextView back = label("‹", 30, ACCENT, true);
        back.setPadding(dp(10), 0, dp(10), dp(4));
        header.addView(back);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(label(sessionTitle == null ? "" : sessionTitle, 17, TEXT, true));
        chatStatus = label(LiveLink.get().connected() ? "已连接" : "连接中…", 11, MUTED, false);
        titleBox.addView(chatStatus);
        header.addView(titleBox, weight(1));

        Button stop = smallButton("停止");
        header.addView(stop);
        root.addView(header);

        chatList = new ListView(this);
        chatList.setDivider(null);
        chatList.setDividerHeight(0);
        chatList.setClipToPadding(false);
        chatAdapter = new ChatAdapter();
        chatList.setAdapter(chatAdapter);
        root.addView(chatList, verticalWeight(1));

        approvalBox = new LinearLayout(this);
        approvalBox.setOrientation(LinearLayout.VERTICAL);
        approvalBox.setPadding(dp(12), 0, dp(12), 0);
        root.addView(approvalBox);

        LinearLayout composerRow = row();
        composerRow.setPadding(dp(10), dp(8), dp(10), dp(14));

        attachButton = new Button(this);
        attachButton.setText("＋");
        attachButton.setTextSize(20);
        attachButton.setAllCaps(false);
        attachButton.setTextColor(ACCENT);
        attachButton.setBackground(roundRect(PANEL, 12));
        LinearLayout.LayoutParams attachParams = new LinearLayout.LayoutParams(dp(48), dp(46));
        attachParams.rightMargin = dp(6);
        composerRow.addView(attachButton, attachParams);

        composer = new EditText(this);
        composer.setHint("输入消息");
        composer.setBackground(roundRect(PANEL, 12));
        composer.setPadding(dp(14), dp(10), dp(14), dp(10));
        composer.setMaxLines(4);
        composerRow.addView(composer, weight(1));

        Button send = primaryButton("发送");
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(76), dp(46));
        sendParams.leftMargin = dp(6);
        composerRow.addView(send, sendParams);
        root.addView(composerRow);

        messages.clear();
        chatList.setAdapter(chatAdapter);

        back.setOnClickListener(v -> {
            sessionId = null;
            approvalViews.clear();
            showSessions();
        });
        stop.setOnClickListener(v -> io.execute(() -> {
            try {
                api.cancel(sessionId);
            } catch (Exception exception) {
                main.post(() -> Toast.makeText(this, friendly(exception), Toast.LENGTH_SHORT).show());
            }
        }));
        send.setOnClickListener(v -> sendMessage());
        attachButton.setOnClickListener(v -> pickFile());

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
                for (int i = 0; i < (array == null ? 0 : array.length()); i++) {
                    rows.add(array.getJSONObject(i));
                }
                main.post(() -> {
                    if (!target.equals(sessionId)) {
                        return;
                    }
                    messages.clear();
                    messages.addAll(rows);
                    chatAdapter.notifyDataSetChanged();
                    scrollToBottom();
                });
            } catch (Exception exception) {
                main.post(() -> Toast.makeText(this, friendly(exception), Toast.LENGTH_LONG).show());
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

    // ---------- 实时流 ----------

    @Override
    public void onFrame(JSONObject frame) {
        String kind = frame.optString("kind");
        if ("message".equals(kind)) {
            if (sessionId != null && sessionId.equals(frame.optString("sessionId")) && chatAdapter != null) {
                messages.add(frame);
                chatAdapter.notifyDataSetChanged();
                scrollToBottom();
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

    @Override
    public void onConnection(boolean connected) {
        if (chatStatus != null && sessionId != null) {
            chatStatus.setText(connected ? "已连接" : "连接中断，正在重连…");
        }
        if (sessionStatus != null && sessionsVisible) {
            sessionStatus.setText(connected ? sessionStatus.getText() : "连接中断，正在重连…");
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
            reasonView.setPadding(0, dp(4), 0, dp(10));
            card.addView(reasonView);
        }

        LinearLayout buttons = row();
        Button allow = new Button(this);
        allow.setText("允许一次");
        allow.setAllCaps(false);
        allow.setTextColor(Color.WHITE);
        allow.setBackground(roundRect(0xFF188038, 12));
        Button deny = new Button(this);
        deny.setText("拒绝");
        deny.setAllCaps(false);
        deny.setBackground(roundRect(0xFFE8EAED, 12));
        deny.setTextColor(TEXT);

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

    private final class SessionAdapter extends BaseAdapter {
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
            JSONObject session = sessions.get(position);
            boolean running = session.optBoolean("running");
            String cwd = session.optString("cwd", "");

            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackgroundColor(Color.WHITE);

            View dot = new View(MainActivity.this);
            GradientDrawable dotDrawable = new GradientDrawable();
            dotDrawable.setShape(GradientDrawable.OVAL);
            dotDrawable.setColor(running ? 0xFF188038 : 0xFFC4C8CE);
            dot.setBackground(dotDrawable);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
            dotParams.rightMargin = dp(12);
            row.addView(dot, dotParams);

            LinearLayout texts = new LinearLayout(MainActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(label(titleOf(session), 16, TEXT, true));
            TextView subtitle = label((running ? "运行中" : "空闲") + " · " + cwd, 12, MUTED, false);
            subtitle.setMaxLines(1);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            texts.addView(subtitle);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            View divider = new View(MainActivity.this);
            divider.setBackgroundColor(0xFFE8EAED);
            LinearLayout wrapper = new LinearLayout(MainActivity.this);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.addView(row);
            wrapper.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

            // The ListView's own item-click does not fire for this composed row, so the row
            // itself carries the navigation intent.
            final String id = session.optString("id");
            final String title = titleOf(session);
            row.setClickable(true);
            row.setOnClickListener(v -> {
                sessionId = id;
                sessionTitle = title;
                showChat();
            });
            return wrapper;
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
            JSONObject message = messages.get(position);
            String role = message.optString("role");
            String text = message.optString("text");

            FrameLayout holder = new FrameLayout(MainActivity.this);
            TextView view = new TextView(MainActivity.this);
            view.setText(text);
            view.setTextSize("tool".equals(role) ? 12 : 15);
            view.setLineSpacing(dp(2), 1f);
            view.setPadding(dp(14), dp(10), dp(14), dp(10));
            view.setTextIsSelectable(true);

            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(14));
            if ("user".equals(role)) {
                background.setColor(ACCENT);
                view.setTextColor(Color.WHITE);
            } else if ("tool".equals(role)) {
                background.setColor(0x00000000);
                view.setTextColor(MUTED);
            } else {
                background.setColor(PANEL);
                view.setTextColor(TEXT);
            }
            view.setBackground(background);
            view.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.78f));

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = "user".equals(role) ? Gravity.END : Gravity.START;
            params.leftMargin = dp(12);
            params.rightMargin = dp(12);
            params.topMargin = dp(3);
            params.bottomMargin = dp(3);
            holder.addView(view, params);
            return holder;
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

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackground(roundRect(ACCENT, 12));
        return button;
    }

    private Button smallButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(ACCENT);
        button.setBackground(roundRect(PANEL, 12));
        return button;
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
