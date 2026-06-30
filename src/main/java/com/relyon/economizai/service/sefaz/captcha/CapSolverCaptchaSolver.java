package com.relyon.economizai.service.sefaz.captcha;

import com.relyon.economizai.exception.CaptchaSolveFailedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * {@link CaptchaSolver} backed by CapSolver (https://capsolver.com).
 * Submits a {@code ReCaptchaV2TaskProxyless} task, polls until ready, and
 * returns the {@code g-recaptcha-response} token.
 *
 * <p>Charged only on success — failed/timeout tasks cost nothing. We retry
 * up to {@value #MAX_RETRIES} times before giving up and throwing
 * {@link CaptchaSolveFailedException}.
 *
 * <p>Active when {@code economizai.captcha.provider=capsolver}. Requires
 * {@code economizai.captcha.capsolver.api-key} to be set.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "economizai.captcha", name = "provider", havingValue = "capsolver")
public class CapSolverCaptchaSolver implements CaptchaSolver {

    private static final String API_BASE = "https://api.capsolver.com";
    private static final int POLL_INTERVAL_MS = 3_000;
    private static final int MAX_POLL_ATTEMPTS = 40; // 40 × 3 s = 2 min max per attempt
    private static final int MAX_RETRIES = 3;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final String apiKey;
    private final RestClient restClient;

    public CapSolverCaptchaSolver(
            RestClient.Builder builder,
            @Value("${economizai.captcha.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = builder.baseUrl(API_BASE).build();
        log.info("captcha.solver provider=capsolver configured=true");
    }

    @Override
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String solveRecaptchaV2(String siteKey, String pageUrl) {
        for (var attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                var taskId = createTask(siteKey, pageUrl);
                log.info("captcha.task.created attempt={}/{} taskId={}", attempt, MAX_RETRIES, taskId);
                var token = pollForToken(taskId);
                log.info("captcha.task.solved attempt={}/{}", attempt, MAX_RETRIES);
                return token;
            } catch (CaptchaSolveFailedException ex) {
                if (attempt >= MAX_RETRIES) throw ex;
                log.warn("captcha.task.failed attempt={}/{} reason={} retrying", attempt, MAX_RETRIES, ex.getMessage());
            }
        }
        throw new CaptchaSolveFailedException("all-retries-exhausted");
    }

    private String createTask(String siteKey, String pageUrl) {
        var body = Map.of(
                "clientKey", apiKey,
                "task", Map.of(
                        "type", "ReCaptchaV2TaskProxyless",
                        "websiteURL", pageUrl,
                        "websiteKey", siteKey
                )
        );
        var response = post("/createTask", body);
        assertNoError(response, "create-task");
        return (String) response.get("taskId");
    }

    private String pollForToken(String taskId) {
        var body = Map.of("clientKey", apiKey, "taskId", taskId);
        for (var poll = 1; poll <= MAX_POLL_ATTEMPTS; poll++) {
            sleep(POLL_INTERVAL_MS);
            var response = post("/getTaskResult", body);
            assertNoError(response, "get-result");
            var status = (String) response.get("status");
            if ("ready".equals(status)) {
                @SuppressWarnings("unchecked")
                var solution = (Map<String, Object>) response.get("solution");
                return (String) solution.get("gRecaptchaResponse");
            }
            if (!"processing".equals(status)) {
                throw new CaptchaSolveFailedException("unexpected-status=" + status);
            }
        }
        throw new CaptchaSolveFailedException("poll-timeout");
    }

    private Map<String, Object> post(String path, Object body) {
        return restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(MAP_TYPE);
    }

    private void assertNoError(Map<String, Object> response, String context) {
        var errorId = ((Number) response.get("errorId")).intValue();
        if (errorId != 0) {
            var errorCode = response.getOrDefault("errorCode", "unknown").toString();
            throw new CaptchaSolveFailedException(context + ":" + errorCode);
        }
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CaptchaSolveFailedException("interrupted");
        }
    }
}
