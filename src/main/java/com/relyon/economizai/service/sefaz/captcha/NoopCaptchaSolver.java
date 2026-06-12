package com.relyon.economizai.service.sefaz.captcha;

import com.relyon.economizai.exception.CaptchaUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default solver when no provider is configured (the current state). It never
 * solves — {@link #isConfigured()} is false and any solve attempt throws
 * {@link CaptchaUnavailableException}, so a captcha-gated receipt (MS) fails
 * fast with a clear "not enabled yet" 503 instead of a confusing parse error.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "economizai.captcha", name = "provider", havingValue = "none", matchIfMissing = true)
public class NoopCaptchaSolver implements CaptchaSolver {

    public NoopCaptchaSolver() {
        log.info("captcha.solver provider=none (captcha-gated states will fail fast)");
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public String solveRecaptchaV2(String siteKey, String pageUrl) {
        throw new CaptchaUnavailableException("captcha");
    }
}
