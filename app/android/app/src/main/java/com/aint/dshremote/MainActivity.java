package com.aint.dshremote;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int ACCENT = 0xFF1A73E8;
    private static final int TEXT = 0xFF1B1F27;
    private static final int MUTED = 0xFF7A808C;
    private static final int PANEL = 0xFFF2F4F7;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<JSONObject> messages = new ArrayList<>();
    private final Map<String, View> approvalViews = new HashMap<>();

    private Prefs prefs;
    private Api api;
    private String sessionId;
    private String sessionTitle;
    private Thread eventsThread;
    private ChatAdapter chatAdapter;
    private ListView chatList;
    private LinearLayout approvalBox;
    private EditText composer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        if (prefs.token().isEmpty()) {
            showPairing();
        } else {
            connect();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (eventsThread != null) {
            eventsThread.interrupt();
        }
        io.shutdownNow();
    }

    // ---------- 配对 ----------

    private void showPairing() {
        LinearLayout root = column(24);
        root.setGravity(Gravity.CENTER_VERTICAL);

        root.addView(label("连接到 DSH 插件", 24, TEXT, true));
        root.addView(space(6));
        root.addView(label("在电脑端打开 http://127.0.0.1:7317/desktop 查看配对码。", 13, MUTED, false));
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

        Button pair = new Button(this);
        pair.setText("配对");
        pair.setAllCaps(false);
        pair.setTextColor(Color.WHITE);
        pair.setBackground(roundRect(ACCENT, 12));
        root.addView(pair, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView error = label("", 13, 0xFFD93025, false);
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
            pair.setEnabled(false);
            error.setText("");
            io.execute(() -> {
                try {
                    JSONObject result = Api.pair(targetServer, pairingCode, deviceName.isEmpty() ? "Android" : deviceName);
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
        showSessions();
        startEvents();
    }

    private void showSessions() {
        LinearLayout root = column(0);

        LinearLayout header = row();
        header.setPadding(dp(16), dp(14), dp(16), dp(10));
        header.addView(label("会话", 20, TEXT, true), weight(1));
        Button refresh = smallButton("刷新");
        header.addView(refresh);
        root.addView(header);

        ListView list = new ListView(this);
        root.addView(list, verticalWeight(1));

        TextView status = label("正在加载…", 12, MUTED, false);
        status.setPadding(dp(16), 0, dp(16), dp(6));
        root.addView(status);

        Button unpair = smallButton("解除配对");
        LinearLayout footer = row();
        footer.setPadding(dp(16), dp(8), dp(16), dp(16));
        footer.addView(unpair);
        root.addView(footer);

        List<String> labels = new ArrayList<>();
        android.widget.ArrayAdapter<String> adapter =
                new android.widget.ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        list.setAdapter(adapter);

        final List<String> ids = new ArrayList<>();
        final List<String> titles = new ArrayList<>();

        Runnable load = () -> io.execute(() -> {
            try {
                JSONArray sessions = api.sessions();
                List<String> newLabels = new ArrayList<>();
                List<String> newIds = new ArrayList<>();
                List<String> newTitles = new ArrayList<>();
                for (int i = 0; i < (sessions == null ? 0 : sessions.length()); i++) {
                    JSONObject session = sessions.getJSONObject(i);
                    String id = session.optString("id");
                    String cwd = session.optString("cwd", "");
                    String title = cwd.isEmpty() ? id : cwd.substring(Math.max(cwd.lastIndexOf('\\'), cwd.lastIndexOf('/')) + 1);
                    if (title.isEmpty()) title = id;
                    String state = session.optBoolean("running") ? "运行中" : "空闲";
                    newLabels.add(title + "\n" + state + " · " + cwd);
                    newIds.add(id);
                    newTitles.add(title);
                }
                main.post(() -> {
                    ids.clear();
                    ids.addAll(newIds);
                    titles.clear();
                    titles.addAll(newTitles);
                    labels.clear();
                    labels.addAll(newLabels);
                    adapter.notifyDataSetChanged();
                    status.setText(newLabels.isEmpty() ? "没有会话" : newLabels.size() + " 个会话");
                });
            } catch (Exception exception) {
                main.post(() -> {
                    status.setText(friendly(exception));
                    Toast.makeText(this, friendly(exception), Toast.LENGTH_LONG).show();
                });
            }
        });

        refresh.setOnClickListener(v -> load.run());
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < ids.size()) {
                sessionId = ids.get(position);
                sessionTitle = titles.get(position);
                showChat();
            }
        });
        unpair.setOnClickListener(v -> {
            prefs.clear();
            if (eventsThread != null) {
                eventsThread.interrupt();
                eventsThread = null;
            }
            api = null;
            showPairing();
        });

        setScreen(root);
        load.run();
    }

    // ---------- 对话 ----------

    private void showChat() {
        LinearLayout root = column(0);

        LinearLayout header = row();
        header.setPadding(dp(12), dp(10), dp(16), dp(8));
        TextView back = label("‹", 28, ACCENT, true);
        back.setPadding(dp(8), 0, dp(12), 0);
        header.addView(back);
        header.addView(label(sessionTitle == null ? "" : sessionTitle, 18, TEXT, true), weight(1));
        Button stop = smallButton("停止");
        header.addView(stop);
        root.addView(header);

        chatList = new ListView(this);
        chatList.setDivider(null);
        root.addView(chatList, verticalWeight(1));

        approvalBox = new LinearLayout(this);
        approvalBox.setOrientation(LinearLayout.VERTICAL);
        approvalBox.setPadding(dp(12), 0, dp(12), 0);
        root.addView(approvalBox);

        LinearLayout composerRow = row();
        composerRow.setPadding(dp(12), dp(8), dp(12), dp(16));
        composer = new EditText(this);
        composer.setHint("输入消息");
        composer.setBackground(roundRect(PANEL, 12));
        composer.setPadding(dp(12), dp(8), dp(12), dp(8));
        composer.setMaxLines(4);
        composerRow.addView(composer, weight(1));
        Button send = new Button(this);
        send.setText("发送");
        send.setAllCaps(false);
        send.setTextColor(Color.WHITE);
        send.setBackground(roundRect(ACCENT, 12));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(76), dp(44));
        sendParams.leftMargin = dp(8);
        composerRow.addView(send, sendParams);
        root.addView(composerRow);

        messages.clear();
        chatAdapter = new ChatAdapter();
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
        composer.setOnEditorActionListener((v, actionId, event) -> {
            sendMessage();
            return true;
        });

        setScreen(root);
        approvalViews.clear();
        approvalBox.removeAllViews();
        loadMessages();
    }

    private void loadMessages() {
        String target = sessionId;
        io.execute(() -> {
            try {
                JSONArray array = api.messages(target);
                List<JSONObject> rows = new ArrayList<>();
                for (int i = 0; i < (array == null ? 0 : array.length()); i++) {
                    rows.add(array.getJSONObject(i));
                }
                main.post(() -> {
                    if (!target.equals(sessionId)) return;
                    messages.clear();
                    messages.addAll(rows);
                    chatAdapter.notifyDataSetChanged();
                    chatList.setSelection(Math.max(0, messages.size() - 1));
                });
            } catch (Exception exception) {
                main.post(() -> Toast.makeText(this, friendly(exception), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void sendMessage() {
        if (composer == null || sessionId == null) return;
        String text = composer.getText().toString().trim();
        if (text.isEmpty()) return;
        String target = sessionId;
        composer.setText("");
        io.execute(() -> {
            try {
                api.prompt(target, text);
            } catch (Exception exception) {
                main.post(() -> Toast.makeText(this, friendly(exception), Toast.LENGTH_LONG).show());
            }
        });
    }

    // ---------- 实时流 ----------

    private void startEvents() {
        if (eventsThread != null) return;
        eventsThread = new Thread(() -> api.streamEvents(frame -> main.post(() -> handleFrame(frame))));
        eventsThread.start();
    }

    private void handleFrame(JSONObject frame) {
        String kind = frame.optString("kind");
        if ("message".equals(kind)) {
            if (sessionId != null && sessionId.equals(frame.optString("sessionId")) && chatAdapter != null) {
                messages.add(frame);
                chatAdapter.notifyDataSetChanged();
                chatList.setSelection(Math.max(0, messages.size() - 1));
            }
        } else if ("approval".equals(kind)) {
            addApprovalCard(frame);
        } else if ("approval-resolved".equals(kind)) {
            View view = approvalViews.remove(frame.optString("id"));
            if (view != null && approvalBox != null) approvalBox.removeView(view);
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
        card.setBackground(roundRect(0xFFFFF4E5, 12));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(8);

        String tool = approval.optString("toolName", "工具调用");
        String reason = approval.optString("reason", "");
        card.addView(label("待审批 · " + tool, 14, TEXT, true));
        if (!reason.isEmpty()) {
            TextView reasonView = label(reason, 13, TEXT, false);
            reasonView.setPadding(0, dp(4), 0, dp(8));
            card.addView(reasonView);
        }

        LinearLayout buttons = row();
        Button allow = new Button(this);
        allow.setText("允许一次");
        allow.setAllCaps(false);
        allow.setTextColor(Color.WHITE);
        allow.setBackground(roundRect(0xFF188038, 10));
        Button deny = new Button(this);
        deny.setText("拒绝");
        deny.setAllCaps(false);
        deny.setBackground(roundRect(0xFFE8EAED, 10));
        deny.setTextColor(TEXT);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        buttonParams.rightMargin = dp(8);
        buttons.addView(allow, buttonParams);
        buttons.addView(deny, new LinearLayout.LayoutParams(0, dp(42), 1));
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
                if (approvalBox != null) approvalBox.removeView(card);
            });
        });
    }

    // ---------- 适配器与控件 ----------

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
            view.setPadding(dp(12), dp(8), dp(12), dp(8));
            view.setTextIsSelectable(true);

            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(12));
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
            view.setMaxWidth(dp(320));

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = "user".equals(role) ? Gravity.END : Gravity.START;
            params.leftMargin = dp(12);
            params.rightMargin = dp(12);
            params.topMargin = dp(4);
            params.bottomMargin = dp(4);
            holder.addView(view, params);
            return holder;
        }
    }

    /**
     * Hosts one screen in an inset-aware container. targetSdk 35 enforces edge-to-edge,
     * so `adjustResize` alone no longer lifts the composer above the keyboard; the IME
     * inset has to be applied here explicitly.
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
                if (ime.bottom > 0 && chatList != null && chatList.getCount() > 0) {
                    chatList.setSelection(chatList.getCount() - 1);
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
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private EditText input(String hint, String value, int inputType) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setText(value);
        view.setInputType(inputType);
        view.setBackground(roundRect(PANEL, 12));
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setSingleLine(true);
        return view;
    }

    private Button smallButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(ACCENT);
        button.setBackground(roundRect(PANEL, 10));
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

    /** Fill the remaining height of a vertical LinearLayout. */
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
                case "bad-code": return "配对码不正确";
                case "expired": return "配对码已过期，请在电脑上重新生成";
                case "too-many-attempts": return "尝试次数过多，请稍后再试";
                case "unauthorized": return "登录已失效，请重新配对";
                default: return "请求失败：" + code;
            }
        }
        return "网络错误：" + exception.getMessage();
    }
}
