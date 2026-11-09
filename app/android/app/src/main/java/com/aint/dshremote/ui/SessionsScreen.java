package com.aint.dshremote.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.aint.dshremote.Api;
import com.aint.dshremote.Discovery;
import com.aint.dshremote.R;
import com.aint.dshremote.RemoteService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 主页面：按工作区分组的会话列表。 */
public final class SessionsScreen {

    private static final int DOT_IDLE = 0xFFC4C8CE;

    private final ScreenHost host;

    private final List<ListRow> sessionRows = new ArrayList<>();
    private SessionAdapter sessionAdapter;
    private Button refreshButton;
    private TextView sessionStatus;
    private long lastSessionRefresh;
    private long refreshStartedAt;
    private int sessionCount;
    private boolean rediscovering;
    private boolean visible = true;

    public SessionsScreen(ScreenHost host) {
        this.host = host;
    }

    public void show() {
        visible = true;
        View root = host.activity().getLayoutInflater().inflate(R.layout.activity_sessions, null);

        refreshButton = root.findViewById(R.id.sessions_refresh);
        sessionStatus = root.findViewById(R.id.sessions_status);
        ListView list = root.findViewById(R.id.sessions_list);
        Button unpair = root.findViewById(R.id.sessions_unpair);

        sessionAdapter = new SessionAdapter();
        list.setAdapter(sessionAdapter);
        refreshButton.setOnClickListener(v -> loadSessions(true));

        unpair.setOnClickListener(v -> confirmUnpair());

        host.showView(root);
        loadSessions();
    }

    /** The router is leaving this screen; late responses must not touch shared state anymore. */
    public void detach() {
        visible = false;
    }

    public boolean visible() {
        return visible;
    }

    /** Frame-driven refresh guard: a burst of messages must not reload the list every time. */
    public boolean shouldReloadOnActivity() {
        return System.currentTimeMillis() - lastSessionRefresh > 2000;
    }

    public void loadSessions() {
        loadSessions(false);
    }

    /**
     * Loads the workspace-grouped session list. The manual flag drives the refresh button so a
     * tap always has visible feedback, even when the payload is unchanged.
     */
    public void loadSessions(boolean manual) {
        if (host.api() == null) {
            return;
        }
        lastSessionRefresh = System.currentTimeMillis();
        if (manual) {
            startRefresh();
        }
        host.io().execute(() -> {
            try {
                JSONObject payload = host.api().workspaces();
                List<ListRow> rows = buildSessionRows(payload);
                int sections = 0;
                for (ListRow row : rows) {
                    if (row.section) {
                        sections++;
                    }
                }
                int count = rows.size() - sections;
                final int groups = sections;
                host.main().post(() -> {
                    finishRefresh();
                    if (!visible) {
                        return;
                    }
                    sessionRows.clear();
                    sessionRows.addAll(rows);
                    sessionCount = count;
                    sessionAdapter.notifyDataSetChanged();
                    sessionStatus.setText(describeList(count, groups));
                });
            } catch (Exception exception) {
                host.main().post(() -> {
                    finishRefresh();
                    if (sessionStatus != null) {
                        sessionStatus.setText(host.friendly(exception));
                    }
                    // A moved host (new DHCP lease) is the common failure: look for it again
                    // instead of making the user retype an IP.
                    if (isNetworkFailure(exception)) {
                        maybeRediscover();
                    } else if (isUnauthorized(exception)) {
                        // The token expired (24h of inactivity) or was revoked on the desktop.
                        host.prefs().clear();
                        host.link().stop();
                        Toast.makeText(host.activity(), "授权已过期，请重新配对", Toast.LENGTH_LONG).show();
                        host.showPairing();
                    }
                });
            }
        });
    }

    /** Unpairing drops the token, so it asks first instead of taking one stray tap. */
    private void confirmUnpair() {
        Activity activity = host.activity();
        new AlertDialog.Builder(activity)
                .setTitle("解除配对？")
                .setMessage("解除后这台手机会删除本地令牌，必须重新输入配对码才能连回来。\n\n"
                        + "只是想切换电脑的话，可以先用「自动发现电脑」。")
                .setPositiveButton("解除配对", (dialog, which) -> {
                    host.prefs().clear();
                    host.link().stop();
                    activity.stopService(new Intent(activity, RemoteService.class));
                    host.setApi(null);
                    sessionRows.clear();
                    if (sessionAdapter != null) {
                        sessionAdapter.notifyDataSetChanged();
                    }
                    host.showPairing();
                })
                .setNegativeButton("取消", null)
                .show();
    }

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
        if (rediscovering || host.prefs().token().isEmpty()) {
            return;
        }
        rediscovering = true;
        final String previous = host.prefs().server();
        if (sessionStatus != null) {
            sessionStatus.setText("连接不上，正在同一网络里搜索电脑…");
        }
        host.io().execute(() -> {
            List<String> found = Discovery.scan(7317, 3);
            host.main().post(() -> {
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
                host.prefs().setServer(best);
                host.setApi(new Api(best, host.prefs().token()));
                host.link().restart(host.activity());
                if (sessionStatus != null) {
                    sessionStatus.setText("已自动切换到 " + best);
                }
                loadSessions();
            });
        });
    }

    private String describeList(int count, int groups) {
        if (count == 0 && groups == 0) {
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
        host.main().postDelayed(() -> {
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
                    + UiKit.shortenPath(group.optString("path", ""));
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

    /** Short label for a session id, using the cached workspace listing. */
    public String titleForSession(String id) {
        for (ListRow row : sessionRows) {
            if (!row.section && row.session != null && id.equals(row.session.optString("id"))) {
                return UiKit.titleOf(row.session);
            }
        }
        return null;
    }

    public void onConnection(boolean connected) {
        if (sessionStatus == null) {
            return;
        }
        sessionStatus.setText(connected ? (sessionCount == 0 ? "还没有会话" : sessionCount + " 个会话")
                : "连接中断，正在重连…");
        if (connected) {
            loadSessions();
        }
    }

    /** One workspace (or the ungrouped bucket) while the list is being assembled. */
    private static final class Group {
        String title;
        String detail;
        boolean running;
        final List<JSONObject> sessions = new ArrayList<>();
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
                    : host.activity().getLayoutInflater().inflate(R.layout.item_session_section, parent, false);
            TextView title = view.findViewById(R.id.session_section_title);
            TextView detail = view.findViewById(R.id.session_section_detail);
            title.setText(item.title);
            detail.setText(item.subtitle);
            return view;
        }

        private View sessionView(JSONObject session, View convertView, ViewGroup parent) {
            View view = convertView != null
                    ? convertView
                    : host.activity().getLayoutInflater().inflate(R.layout.item_session, parent, false);

            TextView title = view.findViewById(R.id.session_title);
            TextView subtitle = view.findViewById(R.id.session_subtitle);
            View dot = view.findViewById(R.id.session_dot);
            View row = view.findViewById(R.id.session_row);

            boolean running = session.optBoolean("running");
            title.setText(UiKit.titleOf(session));
            subtitle.setText((running ? "运行中" : "空闲") + " · " + session.optString("cwd", ""));

            GradientDrawable dotShape = new GradientDrawable();
            dotShape.setShape(GradientDrawable.OVAL);
            dotShape.setColor(running ? UiKit.GREEN : DOT_IDLE);
            dot.setBackground(dotShape);

            final String id = session.optString("id");
            final String titleText = UiKit.titleOf(session);
            row.setOnClickListener(v -> host.showChat(id, titleText));
            return view;
        }
    }
}
