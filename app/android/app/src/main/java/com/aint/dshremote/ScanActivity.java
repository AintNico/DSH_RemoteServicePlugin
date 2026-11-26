package com.aint.dshremote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * In-app QR scanner. Camera frames are decoded with ZXing on a worker thread and the first
 * successful result is handed back as an {@code EXTRA_RESULT} string.
 *
 * The deprecated {@link Camera} API is deliberate: it needs no AndroidX and no camera
 * preview library, which keeps the APK small.
 */
public class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    public static final String EXTRA_RESULT = "scan-result";

    private static final long DECODE_INTERVAL_MS = 250;
    private static final int REQ_CAMERA = 2001;

    private SurfaceView preview;
    private TextView hint;

    private Camera camera;
    private HandlerThread decodeThread;
    private Handler decodeHandler;
    private MultiFormatReader reader;

    private volatile boolean decoding;
    private volatile long lastAttempt;
    private int previewWidth;
    private int previewHeight;
    private boolean permissionRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);
        preview = findViewById(R.id.scan_preview);
        hint = findViewById(R.id.scan_hint);
        findViewById(R.id.scan_close).setOnClickListener(v -> finish());

        preview.getHolder().addCallback(this);

        reader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> formats = new ArrayList<>();
        formats.add(BarcodeFormat.QR_CODE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);

        decodeThread = new HandlerThread("qr-decode");
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionRequested) {
                permissionRequested = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            } else {
                hint.setText("没有相机权限，无法扫码");
            }
            return;
        }
        if (preview.getHolder().getSurface() != null && preview.getHolder().getSurface().isValid()) {
            openCamera(preview.getHolder());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                recreate();
            } else {
                hint.setText("没有相机权限，无法扫码");
            }
        }
    }

    @Override
    protected void onPause() {
        releaseCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releaseCamera();
        if (decodeThread != null) {
            decodeThread.quitSafely();
            decodeThread = null;
        }
        decodeHandler = null;
        super.onDestroy();
    }

    // ---------- 相机 ----------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera(holder);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // The preview is full-screen already; nothing to reflow.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    private void openCamera(SurfaceHolder holder) {
        if (camera != null) {
            return;
        }
        try {
            camera = Camera.open(0);
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size size = pickPreviewSize(parameters);
            if (size != null) {
                parameters.setPreviewSize(size.width, size.height);
            }
            if (parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            camera.setParameters(parameters);

            previewWidth = camera.getParameters().getPreviewSize().width;
            previewHeight = camera.getParameters().getPreviewSize().height;
            camera.setDisplayOrientation(90);
            fitPreviewToFrame();
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Exception error) {
            Log.w("ScanActivity", "cannot open camera", error);
            hint.setText("打不开相机：" + error.getMessage());
            releaseCamera();
        }
    }

    /** A mid-size preview decodes fast enough and still resolves a screen-sized QR. */
    /**
     * The sensor frame is landscape (e.g. 1280x720) and is rotated 90° for display, so the
     * on-screen shape must be 720:1280 — not the screen's own shape. Sizing the SurfaceView to
     * that ratio and letting it overflow ("cover") keeps the picture undistorted.
     */
    private void fitPreviewToFrame() {
        if (previewWidth <= 0 || previewHeight <= 0 || preview == null) {
            return;
        }
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;

        // Rotated: width:height == previewHeight:previewWidth.
        float ratio = (float) previewHeight / (float) previewWidth;
        int width = screenWidth;
        int height = Math.round(width / ratio);
        if (height < screenHeight) {
            height = screenHeight;
            width = Math.round(height * ratio);
        }

        ViewGroup.LayoutParams params = preview.getLayoutParams();
        params.width = width;
        params.height = height;
        preview.setLayoutParams(params);
    }

    private static Camera.Size pickPreviewSize(Camera.Parameters parameters) {
        List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        Camera.Size best = sizes.get(0);
        int target = 1024;
        int bestDelta = Integer.MAX_VALUE;
        for (Camera.Size size : sizes) {
            int delta = Math.abs(Math.max(size.width, size.height) - target);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = size;
            }
        }
        return best;
    }

    private void releaseCamera() {
        if (camera == null) {
            return;
        }
        try {
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
        } catch (Exception ignored) {
            // The camera may already be gone.
        }
        camera = null;
    }

    // ---------- 解码 ----------

    @Override
    public void onPreviewFrame(byte[] data, Camera source) {
        if (data == null || decoding || decodeHandler == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAttempt < DECODE_INTERVAL_MS) {
            return;
        }
        lastAttempt = now;
        decoding = true;

        final int width = previewWidth;
        final int height = previewHeight;
        decodeHandler.post(() -> {
            String text = decode(data, width, height);
            decoding = false;
            if (text != null) {
                runOnUiThread(() -> deliver(text));
            }
        });
    }

    private String decode(byte[] data, int width, int height) {
        try {
            String text = decodeExact(data, width, height);
            if (text != null) {
                return text;
            }
            // The QR may be sideways relative to the sensor buffer.
            return decodeExact(rotate90(data, width, height), height, width);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String decodeExact(byte[] luminance, int width, int height) {
        try {
            PlanarYUVLuminanceSource source =
                    new PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = reader.decodeWithState(bitmap);
            return result == null ? null : result.getText();
        } catch (Exception notFound) {
            return null;
        } finally {
            reader.reset();
        }
    }

    private static byte[] rotate90(byte[] data, int width, int height) {
        byte[] rotated = new byte[data.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                rotated[x * height + (height - y - 1)] = data[y * width + x];
            }
        }
        return rotated;
    }

    private void deliver(String text) {
        if (isFinishing()) {
            return;
        }
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT, text));
        finish();
    }
}
