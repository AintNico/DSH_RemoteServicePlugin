package com.aint.dshremote;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.aint.dshremote.ui.ChatScreen;
import com.aint.dshremote.ui.PairingScreen;
import com.aint.dshremote.ui.PendingStore;
import com.aint.dshremote.ui.ScreenHost;
import com.aint.dshremote.ui.SessionsScreen;
import com.aint.dshremote.ui.UiKit;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Router for the three screens. It owns the process-wide pieces (Prefs, Api, the io thread, the
 * LiveLink listener, the foreground service and the inset-aware container) and hands the shared
 * state to {@link PairingScreen}, {@link SessionsScreen} and {@link ChatScreen}.
 */
public class MainActivity extends Activity implements ScreenHost, LiveLink.Listener {

    private static final int REQ_PICK_FILE = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private Api api;
    private String sessionId;
    private Uri sharedUri;

    private SessionsScreen sessions;
    private ChatScreen chat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        // Pending cards belong to the activity run, exactly like the old fields: a recreated
        // activity starts with an empty tray and only live frames fill it again.
        PendingStore.get().approvals().clear();
        PendingStore.get().questions().clear();
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

        if (sessionId != null && chat != null) {
            for (Uri item : shared) {
                chat.stageAttachment(item);
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
        if (sessionsVisible() && api != null) sessions.loadSessions();
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
        if (sessionId == null) {
            super.onBackPressed();
            return;
        }
        sessionId = null;
        showSessions();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFICATIONS);
    }

    private void connect() {
        api = new Api(prefs.server(), prefs.token());
        startRemoteService();
        showSessions();
    }

    private void startRemoteService() {
        Intent intent = new Intent(this, RemoteService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
    }

    // ---------- screen switching ----------

    @Override
    public void showPairing() {
        sessionId = null;
        detachScreens();
        new PairingScreen(this, this::connect).show();
    }

    @Override
    public void showSessions() {
        sessionId = null;
        detachScreens();
        // The instance is kept for the activity's lifetime: the list cache (rows, count,
        // refresh timestamp) must survive a detour through a chat, exactly as before.
        if (sessions == null) {
            sessions = new SessionsScreen(this);
        }
        sessions.show();
    }

    @Override
    public void showChat(String sessionId, String title) {
        Uri initialShare = sharedUri;
        sharedUri = null;
        detachScreens();
        Function<String, String> sessionNames =
                sessions != null ? sessions::titleForSession : id -> null;
        this.sessionId = sessionId;
        chat = new ChatScreen(this, sessionId, title, sessionNames, initialShare, this::onBackPressed);
        chat.show();
    }

    /** Lets the screen that is being replaced stop touching shared state on late responses. */
    private void detachScreens() {
        if (sessions != null) {
            sessions.detach();
        }
        chat = null;
    }

    /** The session list is only live while it is the installed screen. */
    private boolean sessionsVisible() {
        return sessions != null && sessions.visible();
    }

    /**
     * Hosts one screen in an inset-aware container. targetSdk 35 enforces edge-to-edge,
     * so the IME inset must be applied here or the composer hides under the keyboard.
     */
    @Override
    public void showView(View content) {
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
                if (ime.bottom > 0 && chat != null) chat.scrollToBottom();
                return windowInsets;
            });
            container.requestApplyInsets();
        }

        setContentView(container);
    }

    // ---------- 实时流 ----------

    @Override
    public void onFrame(JSONObject frame) {
        String kind = frame.optString("kind");
        if ("stream-start".equals(kind) || "stream".equals(kind) || "stream-end".equals(kind)) {
            if (sessionId != null && sessionId.equals(frame.optString("sessionId")) && chat != null) {
                chat.onFrame(frame);
            }
            return;
        }

        if ("message".equals(kind)) {
            if (sessionId != null && sessionId.equals(frame.optString("sessionId"))) {
                if (chat != null) chat.onFrame(frame);
            } else if (sessionsVisible() && sessions.shouldReloadOnActivity()) {
                sessions.loadSessions();
            }
        } else if ("approval".equals(kind)) {
            String approvalId = frame.optString("id");
            if (!approvalId.isEmpty()) PendingStore.get().approvals().put(approvalId, frame);
            if (chat != null) chat.addApproval(frame);
        } else if ("approval-resolved".equals(kind)) {
            String resolvedId = frame.optString("id");
            PendingStore.get().approvals().remove(resolvedId);
            if (chat != null) chat.removeApproval(resolvedId);
        } else if ("question".equals(kind)) {
            String questionId = frame.optString("id");
            if (!questionId.isEmpty()) PendingStore.get().questions().put(questionId, frame);
            if (chat != null) chat.addQuestion(frame);
        } else if ("question-resolved".equals(kind)) {
            String questionId = frame.optString("id");
            PendingStore.get().questions().remove(questionId);
            if (chat != null) chat.removeQuestion(questionId);
        } else if ("sessions-changed".equals(kind)) {
            // The agent created, removed or started/stopped a session; re-read the list.
            if (sessionsVisible()) sessions.loadSessions();
        }
    }

    @Override
    public void onConnection(boolean connected) {
        if (chat != null) chat.onConnection(connected);
        else if (sessionsVisible()) sessions.onConnection(connected);
    }

    // ---------- ScreenHost ----------

    @Override public Activity activity() { return this; }
    @Override public ExecutorService io() { return io; }
    @Override public Handler main() { return main; }
    @Override public Prefs prefs() { return prefs; }
    @Override public Api api() { return api; }
    @Override public void setApi(Api api) { this.api = api; }
    @Override public LiveLink link() { return LiveLink.get(); }
    @Override public int dp(int value) { return UiKit.dp(this, value); }
    @Override public void toast(String message) { UiKit.toast(this, message); }
    @Override public String friendly(Exception error) { return UiKit.friendly(error); }

    @Override
    public void startFilePicker() {
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
        if (chat != null) {
            chat.stagePickedFiles(data);
        }
    }
}
