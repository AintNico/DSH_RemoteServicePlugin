package com.aint.dshremote;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds DSH plugin hosts on the current Wi-Fi network.
 *
 * There is no mDNS dependency here on purpose: a plain TCP sweep of the local /24 for the
 * plugin's port, verified with a cheap HTTP probe, is fast enough (seconds) and works on
 * routers that block multicast. The result is a list of ready-to-use base URLs.
 */
final class Discovery {

    private static final int PROBE_TIMEOUT_MS = 350;
    private static final int HTTP_TIMEOUT_MS = 900;
    private static final int THREADS = 32;

    private Discovery() {
    }

    /** Local IPv4 /24 prefixes, e.g. "192.168.37.". */
    static List<String> localPrefixes() {
        Set<String> prefixes = new LinkedHashSet<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                    InetAddress ip = address.getAddress();
                    if (!(ip instanceof Inet4Address) || ip.isLoopbackAddress()) {
                        continue;
                    }
                    String host = ip.getHostAddress();
                    int cut = host.lastIndexOf('.');
                    if (cut > 0) {
                        prefixes.add(host.substring(0, cut + 1));
                    }
                }
            }
        } catch (Exception ignored) {
            // No interfaces readable: the caller simply gets no candidates.
        }
        return new ArrayList<>(prefixes);
    }

    /**
     * Sweeps each local /24 for hosts that answer like the plugin. Blocking; call it off the
     * main thread. Returns at most {@code maxResults} base URLs, fastest responses first.
     */
    static List<String> scan(int port, int maxResults) {
        List<String> prefixes = localPrefixes();
        if (prefixes.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> hosts = new ArrayList<>();
        String own = null;
        for (String prefix : prefixes) {
            for (int i = 1; i <= 254; i++) {
                String host = prefix + i;
                if (host.equals(localIp())) {
                    own = host;
                    continue;
                }
                hosts.add(host);
            }
        }

        Set<String> found = Collections.synchronizedSet(new LinkedHashSet<>());
        AtomicBoolean done = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<Void>> tasks = new ArrayList<>(hosts.size());
            for (String host : hosts) {
                tasks.add(() -> {
                    if (done.get() && found.size() >= maxResults) {
                        return null;
                    }
                    if (probe(host, port)) {
                        found.add("http://" + host + ":" + port);
                        if (found.size() >= maxResults) {
                            done.set(true);
                        }
                    }
                    return null;
                });
            }

            List<Future<Void>> futures = pool.invokeAll(tasks, 12, TimeUnit.SECONDS);
            for (Future<Void> future : futures) {
                if (future.isCancelled()) {
                    continue;
                }
                try {
                    future.get();
                } catch (Exception ignored) {
                    // A failed probe is just a host that is not the plugin.
                }
            }
        } catch (Exception ignored) {
            // Return whatever was found before the interruption.
        } finally {
            pool.shutdownNow();
        }

        return new ArrayList<>(found);
    }

    private static String localIp() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                    InetAddress ip = address.getAddress();
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress()) {
                        return ip.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through.
        }
        return null;
    }

    /** Open the port, then confirm the body really is this plugin's health payload. */
    private static boolean probe(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            socket.setSoTimeout(HTTP_TIMEOUT_MS);
            socket.getOutputStream().write(
                    ("GET /api/health HTTP/1.0\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                    if (body.length() > 2048) {
                        break;
                    }
                }
            }
            String text = body.toString();
            if (!text.contains("200")) {
                return false;
            }
            int brace = text.indexOf('{');
            if (brace < 0) {
                return false;
            }
            JSONObject payload = new JSONObject(text.substring(brace));
            return payload.optBoolean("ok", false)
                    && payload.optString("service", "dsh-remote").equals("dsh-remote");
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Extracts host:port from a base URL, for quick reachability checks. */
    static String hostOf(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            return uri.getHost();
        } catch (Exception exception) {
            return null;
        }
    }
}
