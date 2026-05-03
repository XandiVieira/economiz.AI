package com.relyon.economizai.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizai.exception.GlobalExceptionHandler.ErrorResponse;
import com.relyon.economizai.service.LocalizedMessageService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Predicate;

/**
 * Per-request token-bucket throttle. Matches each request against an
 * ordered list of {@link Rule}s — the first match wins, the rest are
 * skipped. Returns 429 with {@code Retry-After} when the matched bucket
 * is empty.
 *
 * <p>Runs after {@link com.relyon.economizai.security.JwtAuthenticationFilter}
 * (Ordered.HIGHEST_PRECEDENCE + 50) so authenticated rules can read the
 * principal. /auth/* rules use IP since requests on those routes are
 * unauthenticated by definition.
 *
 * <p>Single responsibility: decide-and-enforce per request. Bucket
 * storage lives in {@link RateLimitRegistry}; bandwidth math lives in
 * Bucket4j; IP extraction lives in {@link ClientIpResolver}.
 */
@Slf4j
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * 5 attempts per minute per IP on the password / verification surface.
     * Generous enough for legitimate retries (typo on login, forgot the
     * code), tight enough that brute-forcing 1M password combinations
     * would take ~14 years.
     */
    private static final RateLimitPolicy AUTH_POLICY =
            new RateLimitPolicy("auth", 5, Duration.ofMinutes(1));

    /**
     * 30 receipt submissions per hour per authenticated user. A normal
     * grocery run is 1–3 receipts; this caps an attacker who has
     * exfiltrated a user's token from DoSing the SEFAZ adapter (which
     * does outbound HTTP per submit).
     */
    private static final RateLimitPolicy SUBMIT_POLICY =
            new RateLimitPolicy("submit", 30, Duration.ofHours(1));

    private final RateLimitRegistry registry;
    private final LocalizedMessageService messageService;
    private final ObjectMapper objectMapper;

    private final List<Rule> rules = List.of(
            new Rule(
                    AUTH_POLICY,
                    req -> "POST".equals(req.getMethod()) && req.getRequestURI().startsWith("/api/v1/auth/"),
                    KeyStrategy.IP),
            new Rule(
                    SUBMIT_POLICY,
                    req -> "POST".equals(req.getMethod()) && "/api/v1/receipts".equals(req.getRequestURI()),
                    KeyStrategy.USER_OR_IP)
    );

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        var rule = rules.stream().filter(r -> r.matcher.test(request)).findFirst().orElse(null);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        var key = rule.keyStrategy.keyFor(request);
        var bucket = registry.bucketFor(rule.policy, key);
        var probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            filterChain.doFilter(request, response);
            return;
        }

        var retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        log.warn("rate_limit.blocked policy={} key={} path={} retry_after_s={}",
                rule.policy.name(), key, request.getRequestURI(), retryAfterSeconds);
        writeTooManyRequestsResponse(response, retryAfterSeconds);
    }

    private void writeTooManyRequestsResponse(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        var message = messageService.translate("rate.limit.exceeded");
        var body = new ErrorResponse(HttpStatus.TOO_MANY_REQUESTS.value(), message, LocalDateTime.now());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private record Rule(RateLimitPolicy policy, Predicate<HttpServletRequest> matcher, KeyStrategy keyStrategy) {}

    private enum KeyStrategy {
        IP {
            @Override
            String keyFor(HttpServletRequest request) {
                return "ip:" + ClientIpResolver.resolve(request);
            }
        },
        USER_OR_IP {
            @Override
            String keyFor(HttpServletRequest request) {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.isAuthenticated() && auth.getPrincipal() != null
                        && !"anonymousUser".equals(auth.getPrincipal())) {
                    return "user:" + auth.getName();
                }
                return "ip:" + ClientIpResolver.resolve(request);
            }
        };

        abstract String keyFor(HttpServletRequest request);
    }
}
