package com.aint.dshremote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import org.json.JSONObject;

/** Keeps the link alive while the app is in the background and surfaces alerts. */
public class RemoteService extends Service {

    public static final String CHANNEL_STATUS = "dsh_status";
    public static final String CHANNEL_ALERTS = "dsh_alerts";

    private static final int STATUS_ID = 1;
    private static final int APPROVAL_ID = 2;
    private static final int MESSAGE_ID = 3;

    private final LiveLink.Listener listener = new LiveLink.Listener() {
        @Override
        public void onFrame(JSONObject frame) {
            String kind = frame.optString("kind");
            if ("approval".equals(kind)) {
                notifyAlert(APPROVAL_ID, "待审批：" + frame.optString("toolName", "工具调用"),
                        frame.optString("reason", "点开处理"));
            } else if ("message".equals(kind)
                    && "assistant".equals(frame.optString("role"))
                    && !LiveLink.get().appVisible()) {
                String text = frame.optString("text", "");
                if (!text.isEmpty()) {
                    notifyAlert(MESSAGE_ID, "DSH 有新回复", text.length() > 80 ? text.substring(0, 80) + "…" : text);
                }
            }
        }

        @Override
        public void onConnection(boolean connected) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(STATUS_ID, statusNotification(connected ? "已连接，正在接收消息" : "连接中断，正在重连"));
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        LiveLink.get().addListener(listener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(STATUS_ID, statusNotification("正在连接…"));
        LiveLink.get().start(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        LiveLink.get().removeListener(listener);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        NotificationChannel status = new NotificationChannel(
                CHANNEL_STATUS, "后台连接", NotificationManager.IMPORTANCE_LOW);
        status.setDescription("保持与 DSH 插件的连接");
        manager.createNotificationChannel(status);

        NotificationChannel alerts = new NotificationChannel(
                CHANNEL_ALERTS, "审批与回复", NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("待审批请求与新的助手回复");
        alerts.enableVibration(true);
        manager.createNotificationChannel(alerts);
    }

    private Notification statusNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_STATUS)
                .setContentTitle("DSH 远程")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private void notifyAlert(int id, String title, String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, CHANNEL_ALERTS)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        manager.notify(id, notification);
    }
}
