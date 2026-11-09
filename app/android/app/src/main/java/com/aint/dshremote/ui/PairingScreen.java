package com.aint.dshremote.ui;

import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.aint.dshremote.Api;
import com.aint.dshremote.Discovery;
import com.aint.dshremote.R;

import org.json.JSONObject;

import java.util.List;

/** 连接页面：服务地址、配对码与同网段自动发现。 */
public final class PairingScreen {

    private final ScreenHost host;
    private final Runnable onPaired;

    public PairingScreen(ScreenHost host, Runnable onPaired) {
        this.host = host;
        this.onPaired = onPaired;
    }

    public void show() {
        View root = host.activity().getLayoutInflater().inflate(R.layout.activity_pairing, null);
        final EditText server = root.findViewById(R.id.pairing_server);
        final EditText code = root.findViewById(R.id.pairing_code);
        final EditText device = root.findViewById(R.id.pairing_device);
        Button pair = root.findViewById(R.id.pairing_submit);
        Button discover = root.findViewById(R.id.pairing_discover);
        final TextView error = root.findViewById(R.id.pairing_error);

        server.setText(host.prefs().server());
        server.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        device.setInputType(InputType.TYPE_CLASS_TEXT);
        device.setText(Build.MODEL);

        discover.setOnClickListener(v -> {
            discover.setEnabled(false);
            discover.setAlpha(0.5f);
            discover.setText("正在扫描同一网络…");
            error.setText("");
            host.io().execute(() -> {
                List<String> found = Discovery.scan(7317, 3);
                host.main().post(() -> {
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
            host.io().execute(() -> {
                try {
                    JSONObject result = Api.pair(targetServer, targetCode,
                            deviceName.isEmpty() ? "Android" : deviceName, host.prefs().deviceId());
                    String token = result.optString("token", "");
                    if (token.isEmpty()) throw new Exception("服务端没有返回令牌");
                    host.prefs().save(targetServer, token);
                    host.main().post(onPaired);
                } catch (Exception exception) {
                    host.main().post(() -> {
                        pair.setEnabled(true);
                        pair.setAlpha(1f);
                        error.setText(host.friendly(exception));
                    });
                }
            });
        });

        host.showView(root);
    }
}
