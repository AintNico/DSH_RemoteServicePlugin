package com.aint.dshremote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Minimal client for the DSH remote-service plugin. */
public final class Api {

    public static final class ApiException extends IOException {
        public final int status;
        public final String body;

        ApiException(int status, String body) {
            super("HTTP " + status);
            this.status = status;
            this.body = body;
        }

        public String code() {
            try {
                return new JSONObject(body).optString("error", "http-" + status);
            } catch (JSONException ignored) {
                return "http-" + status;
            }
        }
    }

    public interface FrameListener {
        void onFrame(JSONObject frame);
    }

    private final String base;
    private final String token;

    public Api(String base, String token) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.token = token;
    }

    public String base() {
        return base;
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Content-Type", "application/json");
        if (token != null && !token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        return connection;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private JSONObject request(String path, String method, JSONObject body) throws IOException {
        HttpURLConnection connection = open(path, method);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            String text = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status >= 400) {
                throw new ApiException(status, text);
            }
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (JSONException error) {
            throw new IOException("bad json: " + error.getMessage(), error);
        } finally {
            connection.disconnect();
        }
    }

    static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception error) {
            return value;
        }
    }

    public static JSONObject pair(String server, String code, String device) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("code", code);
            body.put("deviceName", device);
            return new Api(server, null).request("/api/pair", "POST", body);
        } catch (JSONException error) {
            throw new IOException(error);
        }
    }

    public JSONArray sessions() throws IOException {
        return request("/api/sessions", "GET", null).optJSONArray("sessions");
    }

    /** Sessions grouped exactly like the DSH sidebar: workspace sections plus ungrouped ones. */
    public JSONObject workspaces() throws IOException {
        return request("/api/workspaces", "GET", null);
    }

    public JSONArray messages(String sessionId) throws IOException {
        return messages(sessionId, 0);
    }

    /** Fetches the conversation surface, optionally only events newer than {@code since}. */
    public JSONArray messages(String sessionId, long since) throws IOException {
        String query = since > 0 ? "?since=" + since : "";
        return request("/api/sessions/" + enc(sessionId) + "/messages" + query, "GET", null)
                .optJSONArray("messages");
    }

    public void prompt(String sessionId, String text) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("text", text);
            request("/api/sessions/" + enc(sessionId) + "/prompt", "POST", body);
        } catch (JSONException error) {
            throw new IOException(error);
        }
    }

    public void cancel(String sessionId) throws IOException {
        request("/api/sessions/" + enc(sessionId) + "/cancel", "POST", null);
    }

    /** Streams one file as an attachment prompt. Returns the server's JSON receipt. */
    /**
     * Stages one file. With {@code withPrompt=false} the server keeps the upload and only
     * returns a receipt, so several files can be committed together by {@link #sendBatch}.
     */
    public JSONObject upload(String sessionId, String name, java.io.InputStream data, long size,
                             String caption, boolean withPrompt) throws IOException {
        String path = "/api/sessions/" + enc(sessionId) + "/upload?name=" + enc(name);
        if (caption != null && !caption.isEmpty()) {
            path += "&text=" + enc(caption);
        }
        if (!withPrompt) {
            path += "&prompt=false";
        }

        HttpURLConnection connection = open(path, "POST");
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            if (size > 0) {
                connection.setFixedLengthStreamingMode(size);
            } else {
                connection.setChunkedStreamingMode(64 * 1024);
            }

            try (java.io.OutputStream out = connection.getOutputStream()) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = data.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }

            int status = connection.getResponseCode();
            String text = readAll(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status >= 400) {
                throw new ApiException(status, text);
            }
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (JSONException error) {
            throw new IOException("bad json: " + error.getMessage(), error);
        } finally {
            connection.disconnect();
        }
    }

    public JSONObject upload(String sessionId, String name, java.io.InputStream data, long size, String caption)
            throws IOException {
        return upload(sessionId, name, data, size, caption, true);
    }

    /** Commits several staged uploads as one message with optional text. */
    public JSONObject sendBatch(String sessionId, String text, JSONArray receiptIds) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("text", text == null ? "" : text);
            body.put("receiptIds", receiptIds);
        } catch (JSONException error) {
            throw new IOException("bad batch body: " + error.getMessage(), error);
        }
        return request("/api/sessions/" + enc(sessionId) + "/send-batch", "POST", body);
    }

    public boolean answer(String approvalId, String outcome) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("outcome", outcome);
            request("/api/approvals/" + enc(approvalId) + "/answer", "POST", body);
            return true;
        } catch (ApiException error) {
            return error.status != 409;
        } catch (JSONException error) {
            throw new IOException(error);
        }
    }

    /** Lists one directory of the Session workspace. */
    public JSONObject workspace(String sessionId, String path) throws IOException {
        return request("/api/sessions/" + enc(sessionId) + "/workspace?path=" + enc(path), "GET", null);
    }

    /** Asks the server for a short-lived signed URL for one workspace file. */
    public JSONObject signFile(String sessionId, String path) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("path", path);
            return request("/api/sessions/" + enc(sessionId) + "/files/sign", "POST", body);
        } catch (JSONException error) {
            throw new IOException(error);
        }
    }

    /** Streams one signed download straight into {@code out}. */
    public void download(String urlPath, java.io.OutputStream out) throws IOException {
        HttpURLConnection connection = open(urlPath, "GET");
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new ApiException(status, readAll(connection.getErrorStream()));
            }
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Blocking SSE loop; call from a background thread. Reconnects until interrupted. */
    public void streamEvents(Runnable onOpen, FrameListener listener) {
        while (!Thread.currentThread().isInterrupted()) {
            HttpURLConnection connection = null;
            try {
                connection = open("/api/events", "GET");
                // The server pings every 20s; a stalled read means the link is half-open.
                connection.setReadTimeout(45000);
                if (connection.getResponseCode() != 200) {
                    Thread.sleep(3000);
                    continue;
                }
                onOpen.run();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) {
                        continue;
                    }
                    try {
                        listener.onFrame(new JSONObject(line.substring(6)));
                    } catch (JSONException ignored) {
                        // Skip malformed frames instead of dropping the stream.
                    }
                }
            } catch (IOException error) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException interrupted) {
                    return;
                }
            } catch (InterruptedException interrupted) {
                return;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }
}
