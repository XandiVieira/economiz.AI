package com.relyon.economizai.security.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the originating client IP for rate-limit keying. Render and
 * most PaaS platforms put us behind a proxy, so the immediate
 * {@code remoteAddr} is the load balancer's IP — useless for limiting.
 * We honor {@code X-Forwarded-For} (first hop), falling back to the raw
 * remote address only when no proxy header is present.
 */
public final class ClientIpResolver {

    private ClientIpResolver() {}

    public static String resolve(HttpServletRequest request) {
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // First entry = the original client; subsequent entries are the
            // chain of intermediate proxies.
            var firstHop = forwarded.split(",")[0].trim();
            if (!firstHop.isEmpty()) return firstHop;
        }
        var addr = request.getRemoteAddr();
        return addr != null ? addr : "unknown";
    }
}
