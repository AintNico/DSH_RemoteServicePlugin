package com.aint.dshremote.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aint.dshremote.MainActivity;
import com.aint.dshremote.R;
import com.aint.dshremote.ShareProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The 文件 dialog: browse the session workspace, multi-select, share or save. */
public final class WorkspaceBrowser {

    private final ScreenHost host;
    private final String sessionId;
    /** One download at a time: the buttons act on the same staging directory. */
    private boolean fileDownloadInFlight;

    public WorkspaceBrowser(ScreenHost host, String sessionId) {
        this.host = host;
        this.sessionId = sessionId;
    }

    public void show() {
        if (host.api() == null || sessionId == null) {
            return;
        }
        final String target = sessionId;
        final Activity activity = host.activity();

        Dialog dialog = new Dialog(activity);
        LinearLayout box = (LinearLayout) activity.getLayoutInflater().inflate(R.layout.dialog_workspace, null);
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
        scrollParams.height = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.52f);
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
                TextView empty = new TextView(activity);
                empty.setText("（空目录）");
                empty.setTextSize(13);
                empty.setTextColor(UiKit.color(activity, R.color.muted));
                rows.addView(empty);
            }

            for (JSONObject entry : items) {
                final String name = entry.optString("name");
                final boolean directory = entry.optBoolean("directory");
                final long size = entry.optLong("size", -1);
                final String fullPath = ".".equals(current[0]) ? name : current[0] + "/" + name;

                View rowView = activity.getLayoutInflater().inflate(R.layout.item_workspace_entry, rows, false);
                TextView check = rowView.findViewById(R.id.entry_check);
                TextView icon = rowView.findViewById(R.id.entry_icon);
                TextView nameView = rowView.findViewById(R.id.entry_name);
                TextView meta = rowView.findViewById(R.id.entry_meta);

                boolean picked = selected.contains(fullPath);
                if (selectionMode[0] && !directory) {
                    check.setVisibility(View.VISIBLE);
                    check.setText(picked ? "☑" : "☐");
                    check.setTextColor(UiKit.color(activity, picked ? R.color.accent : R.color.muted));
                } else {
                    check.setVisibility(View.GONE);
                }

                icon.setText(directory ? "📁" : "📄");
                nameView.setText(name);
                nameView.setTypeface(null, directory ? Typeface.BOLD : Typeface.NORMAL);
                if (!directory && size > 0) {
                    meta.setText(UiKit.formatSize(size));
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
            host.io().execute(() -> {
                try {
                    JSONObject result = host.api().workspace(target, path);
                    JSONArray entries = result.optJSONArray("entries");
                    List<JSONObject> fetched = new ArrayList<>();
                    for (int i = 0; i < (entries == null ? 0 : entries.length()); i++) {
                        fetched.add(entries.getJSONObject(i));
                    }
                    host.main().post(() -> {
                        items.clear();
                        items.addAll(fetched);
                        selected.clear();
                        selectionMode[0] = false;
                        paint[0].run();
                    });
                } catch (Exception exception) {
                    host.main().post(() -> status.setText(host.friendly(exception)));
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
                Toast.makeText(activity, "已经在工作区根目录", Toast.LENGTH_SHORT).show();
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
        final Activity activity = host.activity();
        new AlertDialog.Builder(activity)
                .setTitle(name)
                .setItems(new String[] { "分享到其他应用", "保存到下载" }, (dialog, which) -> {
                    if (fileDownloadInFlight) {
                        Toast.makeText(activity, "上一个文件还在处理…", Toast.LENGTH_SHORT).show();
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
        host.io().execute(() -> {
            try {
                File file = downloadToCache(target, fullPath);
                host.main().post(() -> {
                    fileDownloadInFlight = false;
                    status.setText("已下载 " + file.getName() + "（" + UiKit.formatSize(file.length()) + "）");
                    shareFile(file);
                });
            } catch (Exception exception) {
                host.main().post(() -> {
                    fileDownloadInFlight = false;
                    status.setText("下载失败：" + host.friendly(exception));
                });
            }
        });
    }

    /** Downloads every selected file, then hands them to one share sheet as a group. */
    private void shareWorkspaceFiles(String target, List<String> paths, TextView status) {
        fileDownloadInFlight = true;
        status.setText("正在准备 " + paths.size() + " 个文件…");
        host.io().execute(() -> {
            List<Uri> uris = new ArrayList<>();
            String failure = null;
            for (int index = 0; index < paths.size(); index++) {
                final int current = index + 1;
                host.main().post(() -> status.setText("正在下载 " + current + "/" + paths.size() + "…"));
                try {
                    File file = downloadToCache(target, paths.get(index));
                    uris.add(ShareProvider.uriFor(host.activity(), file));
                } catch (Exception exception) {
                    failure = host.friendly(exception);
                    break;
                }
            }
            final String error = failure;
            final List<Uri> ready = uris;
            host.main().post(() -> {
                fileDownloadInFlight = false;
                if (!ready.isEmpty()) {
                    status.setText("已准备 " + ready.size() + " 个文件");
                    shareFiles(ready);
                }
                if (error != null) {
                    Toast.makeText(host.activity(), "下载失败：" + error, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /** Only 分享 needs a local copy; 保存到下载 streams straight into MediaStore. */
    private File downloadToCache(String target, String fullPath) throws Exception {
        JSONObject signed = host.api().signFile(target, fullPath);
        String url = signed.optString("url", "");
        if (url.isEmpty()) {
            throw new Exception("服务端没有返回下载地址");
        }
        String fileName = UiKit.safeFileName(signed.optString("name", "file"));
        File file = new File(ShareProvider.shareDirectory(host.activity()), fileName);
        try (OutputStream out = new FileOutputStream(file)) {
            host.api().download(url, out);
        }
        return file;
    }

    private void saveWorkspaceFile(String target, String fullPath, String fallbackName, TextView status) {
        fileDownloadInFlight = true;
        status.setText("正在保存到下载…");
        host.io().execute(() -> {
            try {
                String saved = saveOneToDownloads(target, fullPath, fallbackName);
                host.main().post(() -> {
                    fileDownloadInFlight = false;
                    status.setText("已保存到 下载/DSH/" + saved);
                });
            } catch (Exception exception) {
                host.main().post(() -> {
                    fileDownloadInFlight = false;
                    status.setText("保存失败：" + host.friendly(exception));
                });
            }
        });
    }

    private void saveWorkspaceFiles(String target, List<String> paths, TextView status) {
        fileDownloadInFlight = true;
        status.setText("正在保存 " + paths.size() + " 个文件…");
        host.io().execute(() -> {
            int saved = 0;
            String failure = null;
            for (int index = 0; index < paths.size(); index++) {
                final int current = index + 1;
                host.main().post(() -> status.setText("正在保存 " + current + "/" + paths.size() + "…"));
                try {
                    saveOneToDownloads(target, paths.get(index), "file");
                    saved++;
                } catch (Exception exception) {
                    failure = host.friendly(exception);
                    break;
                }
            }
            final int done = saved;
            final String error = failure;
            host.main().post(() -> {
                fileDownloadInFlight = false;
                status.setText(error == null
                        ? "已保存 " + done + " 个文件到 下载/DSH/"
                        : "已保存 " + done + " 个，失败：" + error);
            });
        });
    }

    /** Streams one workspace file into MediaStore; returns the stored display name. */
    private String saveOneToDownloads(String target, String fullPath, String fallbackName) throws Exception {
        final Activity activity = host.activity();
        JSONObject signed = host.api().signFile(target, fullPath);
        String url = signed.optString("url", "");
        if (url.isEmpty()) {
            throw new Exception("服务端没有返回下载地址");
        }
        String fileName = UiKit.safeFileName(signed.optString("name", fallbackName));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DSH");
            Uri uri = activity.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new Exception("无法创建下载文件");
            }
            try (OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new Exception("无法写入下载文件");
                }
                host.api().download(url, out);
            }
        } else {
            File dir = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "DSH");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new Exception("无法创建下载目录");
            }
            File file = new File(dir, fileName);
            try (OutputStream out = new FileOutputStream(file)) {
                host.api().download(url, out);
            }
        }
        return fileName;
    }

    private void shareFile(File file) {
        shareFiles(Collections.singletonList(ShareProvider.uriFor(host.activity(), file)));
    }

    private void shareFiles(List<Uri> uris) {
        if (uris.isEmpty()) {
            return;
        }
        final Activity activity = host.activity();
        try {
            Intent send;
            if (uris.size() == 1) {
                Uri uri = uris.get(0);
                send = new Intent(Intent.ACTION_SEND);
                send.setType(activity.getContentResolver().getType(uri));
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
                    new ComponentName[] { new ComponentName(activity, MainActivity.class) });
            activity.startActivity(chooser);
        } catch (Exception exception) {
            Toast.makeText(activity, "分享失败：" + host.friendly(exception), Toast.LENGTH_LONG).show();
        }
    }
}
