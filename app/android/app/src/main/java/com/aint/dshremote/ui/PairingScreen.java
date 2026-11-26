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

/** 连接页面：服务地址、配对码、扫码与同网段自动发现。 */
public final class PairingScreen {

    private final ScreenHost host;
    private final Runnable onPaired;

    private EditText serverField;
    private EditText codeField;
    private EditText deviceField;
    private Button pairButton;
    private TextView errorView;

    public PairingScreen(ScreenHost host, Runnable onPaired) {
        this.host = host;
        this.onPaired = onPaired;
    }

    public void show() {
        View root = host.activity().getLayoutInflater().inflate(R.layout.activity_pairing, null);
        serverField = root.findViewById(R.id.pairing_server);
        codeField = root.findViewById(R.id.pairing_code);
        deviceField = root.findViewById(R.id.pairing_device);
        pairButton = root.findViewById(R.id.pairing_submit);
        Button scan = root.findViewById(R.id.pairing_scan);
        Button discover = root.findViewById(R.id.pairing_discover);
        errorView = root.findViewById(R.id.pairing_error);

        serverField.setText(host.prefs().server());
        serverField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        codeField.setInputType(InputType.TYPE_CLASS_NUMBER);
        deviceField.setInputType(InputType.TYPE_CLASS_TEXT);
        deviceField.setText(Build.MODEL);

        scan.setOnClickListener(v -> host.scanPairingCode());

        discover.setOnClickListener(v -> {
            discover.setEnabled(false);
            discover.setAlpha(0.5f);
            discover.setText("正在扫描同一网络…");
            errorView.setText("");
            host.io().execute(() -> {
                List<String> found = Discovery.scan(7317, 3);
                host.main().post(() -> {
                    discover.setEnabled(true);
                    discover.setAlpha(1f);
                    discover.setText("自动发现电脑");
                    if (found.isEmpty()) {
                        errorView.setText("没找到运行插件的电脑：确认手机和电脑连的是同一个 Wi-Fi");
                        return;
                    }
                    serverField.setText(found.get(0));
                    errorView.setText(found.size() > 1
                            ? "找到 " + found.size() + " 台，已填入第一台：" + found.get(0)
                            : "已找到 " + found.get(0));
                });
            });
        });

        pairButton.setOnClickListener(v -> submit());
        host.showView(root);
        focusPairingCodeIfReady();
    }

    /**
     * Called with a link from the camera, the in-app scanner, or an external intent. The
     * fields are filled first so a failure leaves something the user can correct by hand.
     */
    public void applyLink(PairLink link) {
        if (link == null) {
            return;
        }
        if (serverField == null || codeField == null || pairButton == null) {
            return;
        }
        serverField.setText(link.url);
        codeField.setText(link.code);
        submit();
    }

    private void focusPairingCodeIfReady() {
        if (!serverField.getText().toString().isEmpty()) {
            codeField.requestFocus();
        } else {
            serverField.requestFocus();
        }
    }

    private void submit() {
        if (pairButton == null) {
            return;
        }
        String serverUrl = serverField.getText().toString().trim();
        String pairingCode = codeField.getText().toString().trim();
        String deviceName = deviceField.getText().toString().trim();
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrl = "http://" + serverUrl;
        }
        if (serverUrl.isEmpty() || pairingCode.length() != 6) {
            errorView.setText("请填写服务地址和 6 位配对码");
            return;
        }

        final String targetServer = serverUrl;
        final String targetCode = pairingCode;
        pairButton.setEnabled(false);
        pairButton.setAlpha(0.5f);
        errorView.setText("");
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
                    pairButton.setEnabled(true);
                    pairButton.setAlpha(1f);
                    errorView.setText(host.friendly(exception));
                });
            }
        });
    }
}
