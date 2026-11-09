package com.aint.dshremote.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.aint.dshremote.Markdown;
import com.aint.dshremote.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

/** 会话页面：消息列表、流式输出、输入框、附件与待处理托盘。 */
public final class ChatScreen {

    /** Keeps the chat bounded: the list is virtualized, this bounds the data behind it. */
    private static final int MAX_MESSAGES = 300;

    private final ScreenHost host;
    private final String sessionId;
    private final String sessionTitle;
    private final Function<String, String> sessionNames;
    private final Uri initialShare;
    private final Runnable onBack;
    private final WorkspaceBrowser browser;

    private final List<JSONObject> messages = new ArrayList<>();
    /** Rendered markdown is expensive; cache it per message and let GC follow the message list. */
    private final Map<JSONObject, CharSequence> markdownCache =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final List<PendingFile> pendingFiles = new ArrayList<>();

    private ChatAdapter chatAdapter;
    private ListView chatList;
    private EditText composer;
    private TextView chatStatus;
    private Button attachButton;
    private LinearLayout pendingRow;
    private TextView pendingLabel;
    private boolean uploading;
    private long lastSeq;
    private StringBuilder streamBuffer;
    private JSONObject streamingMessage;
    private PendingTray tray;

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

    public ChatScreen(ScreenHost host, String sessionId, String title,
                      Function<String, String> sessionNames, Uri initialShare, Runnable onBack) {
        this.host = host;
        this.sessionId = sessionId;
        this.sessionTitle = title;
        this.sessionNames = sessionNames;
        this.initialShare = initialShare;
        this.onBack = onBack;
        this.browser = new WorkspaceBrowser(host, sessionId);
    }

    public void show() {
        View root = host.activity().getLayoutInflater().inflate(R.layout.activity_chat, null);

        TextView back = root.findViewById(R.id.chat_back);
        TextView titleView = root.findViewById(R.id.chat_title);
        chatStatus = root.findViewById(R.id.chat_status);
        chatList = root.findViewById(R.id.chat_list);
        pendingRow = root.findViewById(R.id.pending_row);
        pendingLabel = root.findViewById(R.id.pending_label);
        Button clearAttachment = root.findViewById(R.id.pending_clear);
        attachButton = root.findViewById(R.id.chat_attach);
        composer = root.findViewById(R.id.chat_composer);
        Button send = root.findViewById(R.id.chat_send);
        Button files = root.findViewById(R.id.chat_files);
        Button stop = root.findViewById(R.id.chat_stop);

        tray = new PendingTray(host, root, sessionId, sessionNames);

        titleView.setText(sessionTitle == null ? "" : sessionTitle);
        chatStatus.setText(host.link().connected() ? "已连接" : "连接中…");
        back.setOnClickListener(v -> onBack.run());
        clearAttachment.setOnClickListener(v -> clearPending());

        chatAdapter = new ChatAdapter();
        chatList.setAdapter(chatAdapter);

        messages.clear();
        streamingMessage = null;
        streamBuffer = null;

        stop.setOnClickListener(v -> host.io().execute(() -> {
            try {
                host.api().cancel(sessionId);
            } catch (Exception exception) {
                host.main().post(() -> host.toast(host.friendly(exception)));
            }
        }));
        send.setOnClickListener(v -> sendMessage());
        attachButton.setOnClickListener(v -> host.startFilePicker());
        files.setOnClickListener(v -> browser.show());

        host.showView(root);
        tray.showAll();
        if (initialShare != null) {
            stageAttachment(initialShare);
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
        host.io().execute(() -> {
            try {
                JSONArray array = host.api().messages(target);
                List<JSONObject> rows = new ArrayList<>();
                long maxSeq = 0;
                for (int i = 0; i < (array == null ? 0 : array.length()); i++) {
                    JSONObject row = array.getJSONObject(i);
                    rows.add(row);
                    maxSeq = Math.max(maxSeq, row.optLong("seq", 0));
                }
                final long throughSeq = maxSeq;
                host.main().post(() -> {
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
                host.main().post(() -> Toast.makeText(host.activity(),
                        host.friendly(exception), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Pull only what was missed while the stream was disconnected. */
    private void syncMessages() {
        if (host.api() == null || sessionId == null || chatAdapter == null) {
            return;
        }
        final String target = sessionId;
        final long since = lastSeq;
        host.io().execute(() -> {
            try {
                JSONArray array = host.api().messages(target, since);
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
                host.main().post(() -> {
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
        host.io().execute(() -> {
            try {
                host.api().prompt(target, text);
            } catch (Exception exception) {
                host.main().post(() -> Toast.makeText(host.activity(),
                        host.friendly(exception), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Picking files only stages them; the user still writes the instruction before sending. */
    public void stageAttachment(Uri uri) {
        final Activity activity = host.activity();
        String name = "file";
        long size = -1;
        try (Cursor cursor = activity.getContentResolver().query(uri, null, null, null, null)) {
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

    /** Stages what the system document picker returned. */
    public void stagePickedFiles(Intent data) {
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
                    + (file.size > 0 ? "（" + UiKit.formatSize(file.size) + "）" : ""));
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
            pendingLabel.setText(pendingFiles.size() + " 个文件（" + UiKit.formatSize(total) + "）：" + names);
        }
        pendingRow.setVisibility(View.VISIBLE);
    }

    /**
     * Uploads every staged file as one message: each file is staged with its own receipt,
     * then a single prompt binds them all together with the typed text.
     */
    private void sendAttachment(String caption) {
        if (host.api() == null || sessionId == null || uploading || pendingFiles.isEmpty()) {
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

        host.io().execute(() -> {
            try {
                JSONArray receipts = new JSONArray();
                for (int index = 0; index < files.size(); index++) {
                    PendingFile file = files.get(index);
                    if (chatStatus != null && files.size() > 1) {
                        final int current = index + 1;
                        host.main().post(() -> chatStatus.setText("正在上传 " + current + "/" + files.size() + "…"));
                    }
                    try (InputStream stream = host.activity().getContentResolver().openInputStream(file.uri)) {
                        if (stream == null) {
                            throw new Exception("无法读取 " + file.name);
                        }
                        JSONObject staged = host.api().upload(target, file.name, stream, file.size, "", false);
                        String receiptId = staged.optString("receiptId", "");
                        if (receiptId.isEmpty()) {
                            throw new Exception("服务端没有返回回执");
                        }
                        receipts.put(receiptId);
                    }
                }

                host.api().sendBatch(target, caption, receipts);
                host.main().post(() -> {
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
                host.main().post(() -> {
                    uploading = false;
                    if (attachButton != null) {
                        attachButton.setEnabled(true);
                        attachButton.setAlpha(1f);
                    }
                    String message = host.friendly(exception);
                    if (chatStatus != null) {
                        chatStatus.setText("上传失败：" + message);
                    }
                    Toast.makeText(host.activity(), "上传失败：" + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ---------- 实时流 ----------

    /** Handles the frames the router forwards for this conversation. */
    public void onFrame(JSONObject frame) {
        String kind = frame.optString("kind");
        if ("stream-start".equals(kind)) {
            beginStream();
            return;
        }
        if ("stream".equals(kind)) {
            appendStream(frame.optString("text", ""));
            return;
        }
        if ("stream-end".equals(kind)) {
            endStream();
            return;
        }
        if ("message".equals(kind)) {
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
        }
    }

    public void onConnection(boolean connected) {
        if (chatStatus != null && sessionId != null) {
            chatStatus.setText(connected ? "已连接" : "连接中断，正在重连…");
            if (connected) {
                syncMessages();
            }
        }
    }

    public void addApproval(JSONObject frame) {
        tray.addApproval(frame);
    }

    public void addQuestion(JSONObject frame) {
        tray.addQuestion(frame);
    }

    public void removeApproval(String id) {
        tray.removeApproval(id);
    }

    public void removeQuestion(String id) {
        tray.removeQuestion(id);
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

    public void scrollToBottom() {
        if (chatList != null) {
            chatList.post(() -> {
                if (chatList.getCount() > 0) {
                    chatList.setSelection(chatList.getCount() - 1);
                }
            });
        }
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
                (int) (host.activity().getResources().getDisplayMetrics().widthPixels * 0.76f);

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
                view = host.activity().getLayoutInflater().inflate(layout, parent, false);
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
}
