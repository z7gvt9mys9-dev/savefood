package kz.savefood.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Port of backend/proxy_service.py — a local SOCKS5 tunnel via xray-core for
 * reaching the Telegram API from networks where it is blocked.
 *
 * <p>The Python version uses {@code python_v2ray} to auto-download the xray
 * binary and parse the VLESS URI. There is no equivalent library on the JVM, so
 * this port keeps the same contract but needs the xray binary supplied out of
 * band: set {@code XRAY_BINARY} to its path (defaults to {@code ./vendor/xray}).
 * With no {@code VLESS_URL} the service is a no-op and {@link #getProxyUrl()}
 * returns {@code null} — exactly like the Python module, so Telegram traffic
 * goes out directly. The local inbound is SOCKS5 on 127.0.0.1:10808, matching
 * the Python port.
 */
@Service
public class ProxyService {

    private static final Logger log = Logger.getLogger(ProxyService.class.getName());
    private static final String SOCKS_HOST = "127.0.0.1";
    private static final int SOCKS_PORT = 10808;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String vlessUrl;
    private final String xrayBinary;

    private Process xray;

    public ProxyService(@Value("${savefood.proxy.vless-url:}") String vlessUrl,
                        @Value("${savefood.proxy.xray-binary:./vendor/xray}") String xrayBinary) {
        this.vlessUrl = vlessUrl == null ? "" : vlessUrl.strip();
        this.xrayBinary = xrayBinary;
    }

    @PostConstruct
    public void start() {
        if (vlessUrl.isEmpty()) {
            return;  // proxy disabled — Telegram goes out directly
        }
        File bin = new File(xrayBinary);
        if (!bin.isFile()) {
            log.severe("[proxy] xray binary not found at " + xrayBinary + " — proxy disabled");
            return;
        }
        try {
            Map<String, Object> config = buildConfig(vlessUrl);
            if (config == null) {
                log.severe("[proxy] could not parse VLESS_URL — proxy disabled");
                return;
            }
            Path cfg = Files.createTempFile("xray-", ".json");
            cfg.toFile().deleteOnExit();
            Files.write(cfg, mapper.writeValueAsBytes(config));

            xray = new ProcessBuilder(bin.getAbsolutePath(), "run", "-c", cfg.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();

            // Wait up to ~6s for the SOCKS5 port to open (parity with the Python loop).
            boolean up = false;
            for (int i = 0; i < 30 && xray.isAlive(); i++) {
                Thread.sleep(200);
                try (var sock = new java.net.Socket()) {
                    sock.connect(new java.net.InetSocketAddress(SOCKS_HOST, SOCKS_PORT), 200);
                    up = true;
                    break;
                } catch (Exception ignore) {
                    // not listening yet
                }
            }
            if (!xray.isAlive()) {
                log.severe("[proxy] xray exited immediately — check VLESS_URL");
                xray = null;
                return;
            }
            if (!up) {
                log.warning("[proxy] SOCKS5 port did not open in time — proxy may still be starting");
            }
            log.info("[proxy] VLESS proxy ready: " + getProxyUrl());
        } catch (Exception e) {
            log.severe("[proxy] failed to start xray: " + e.getMessage());
            stop();
        }
    }

    /** SOCKS5 URL if xray is running, else null (mirrors get_proxy_url). */
    public String getProxyUrl() {
        return (xray != null && xray.isAlive()) ? "socks5://" + SOCKS_HOST + ":" + SOCKS_PORT : null;
    }

    @PreDestroy
    public void stop() {
        if (xray != null) {
            xray.destroy();
            xray = null;
            log.info("[proxy] xray stopped");
        }
    }

    /**
     * Parse a {@code vless://uuid@host:port?params#name} URI into a minimal xray
     * config with a local SOCKS5 inbound and a VLESS outbound. Covers the common
     * transports (tcp/ws/grpc) and TLS/Reality security; advanced knobs fall back
     * to xray defaults.
     */
    Map<String, Object> buildConfig(String uri) {
        if (!uri.startsWith("vless://")) {
            return null;
        }
        URI u;
        try {
            u = new URI(uri);
        } catch (Exception e) {
            return null;
        }
        String userInfo = u.getUserInfo();
        String host = u.getHost();
        int port = u.getPort();
        if (userInfo == null || host == null || port < 0) {
            return null;
        }
        Map<String, String> q = parseQuery(u.getRawQuery());
        String network = q.getOrDefault("type", "tcp");
        String security = q.getOrDefault("security", "none");

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", userInfo);
        user.put("encryption", q.getOrDefault("encryption", "none"));
        if (q.containsKey("flow")) {
            user.put("flow", q.get("flow"));
        }

        Map<String, Object> vnext = new LinkedHashMap<>();
        vnext.put("address", host);
        vnext.put("port", port);
        vnext.put("users", List.of(user));

        Map<String, Object> streamSettings = new LinkedHashMap<>();
        streamSettings.put("network", network);
        streamSettings.put("security", "reality".equals(security) ? "reality" : security);

        if ("tls".equals(security)) {
            Map<String, Object> tls = new LinkedHashMap<>();
            if (q.containsKey("sni")) tls.put("serverName", q.get("sni"));
            if (q.containsKey("fp")) tls.put("fingerprint", q.get("fp"));
            streamSettings.put("tlsSettings", tls);
        } else if ("reality".equals(security)) {
            Map<String, Object> reality = new LinkedHashMap<>();
            if (q.containsKey("sni")) reality.put("serverName", q.get("sni"));
            if (q.containsKey("fp")) reality.put("fingerprint", q.get("fp"));
            if (q.containsKey("pbk")) reality.put("publicKey", q.get("pbk"));
            if (q.containsKey("sid")) reality.put("shortId", q.get("sid"));
            if (q.containsKey("spx")) reality.put("spiderX", q.get("spx"));
            streamSettings.put("realitySettings", reality);
        }
        if ("ws".equals(network)) {
            Map<String, Object> ws = new LinkedHashMap<>();
            ws.put("path", q.getOrDefault("path", "/"));
            if (q.containsKey("host")) ws.put("headers", Map.of("Host", q.get("host")));
            streamSettings.put("wsSettings", ws);
        } else if ("grpc".equals(network)) {
            streamSettings.put("grpcSettings", Map.of("serviceName", q.getOrDefault("serviceName", "")));
        }

        Map<String, Object> outbound = new LinkedHashMap<>();
        outbound.put("tag", "proxy");
        outbound.put("protocol", "vless");
        outbound.put("settings", Map.of("vnext", List.of(vnext)));
        outbound.put("streamSettings", streamSettings);

        Map<String, Object> inbound = new LinkedHashMap<>();
        inbound.put("tag", "socks-in");
        inbound.put("listen", SOCKS_HOST);
        inbound.put("port", SOCKS_PORT);
        inbound.put("protocol", "socks");
        inbound.put("settings", Map.of("auth", "noauth", "udp", true));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("inbounds", List.of(inbound));
        config.put("outbounds", List.of(outbound));
        config.put("routing", Map.of("rules", List.of(
            Map.of("type", "field", "outboundTag", "proxy", "network", "tcp,udp"))));
        return config;
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }
}
