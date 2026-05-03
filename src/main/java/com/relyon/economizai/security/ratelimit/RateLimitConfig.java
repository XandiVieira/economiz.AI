package com.relyon.economizai.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.relyon.economizai.service.LocalizedMessageService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Production wiring for the rate-limit machinery. Kept as an explicit
 * @Configuration (vs @Component on each class) so @WebMvcTest slices can
 * import {@link com.relyon.economizai.config.SecurityConfig} without
 * dragging in the filter — see SecurityConfig's ObjectProvider injection.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public RateLimitRegistry rateLimitRegistry() {
        return new RateLimitRegistry();
    }

    /**
     * Uses a self-owned {@link ObjectMapper} (the response body is a single
     * small record) so the filter doesn't depend on the Spring-wired one —
     * keeps it usable in test contexts that don't load JacksonAutoConfig.
     * JavaTimeModule registered because the 429 ErrorResponse carries a
     * {@link java.time.LocalDateTime} timestamp.
     */
    @Bean
    public RateLimitFilter rateLimitFilter(RateLimitRegistry registry,
                                           LocalizedMessageService messageService) {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new RateLimitFilter(registry, messageService, mapper);
    }
}
