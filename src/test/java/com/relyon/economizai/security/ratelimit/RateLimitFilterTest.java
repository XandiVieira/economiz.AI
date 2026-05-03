package com.relyon.economizai.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.relyon.economizai.service.LocalizedMessageService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private FilterChain chain;
    private LocalizedMessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = mock(LocalizedMessageService.class);
        when(messageService.translate("rate.limit.exceeded")).thenReturn("Too many requests.");
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        filter = new RateLimitFilter(new RateLimitRegistry(), messageService, mapper);
        chain = mock(FilterChain.class);
    }

    @Test
    void allowsRequestsBelowAuthLimit() throws Exception {
        for (var i = 0; i < 5; i++) {
            var response = invokeAuthRequest("1.2.3.4");
            assertEquals(200, response.getStatus());
        }
        verify(chain, times(5)).doFilter(any(), any());
    }

    @Test
    void blocksSixthAuthRequestFromSameIp() throws Exception {
        for (var i = 0; i < 5; i++) invokeAuthRequest("5.6.7.8");
        var blocked = invokeAuthRequest("5.6.7.8");
        assertEquals(429, blocked.getStatus());
        assertNotNull(blocked.getHeader("Retry-After"));
    }

    @Test
    void differentIpsHaveSeparateBuckets() throws Exception {
        for (var i = 0; i < 5; i++) invokeAuthRequest("9.9.9.9");
        var fromOtherIp = invokeAuthRequest("8.8.8.8");
        assertEquals(200, fromOtherIp.getStatus(),
                "second IP should not be throttled by the first IP's exhausted bucket");
    }

    @Test
    void doesNotApplyToUnmatchedRoute() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/products");
        request.setRemoteAddr("1.1.1.1");
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);
        assertEquals(200, response.getStatus());
        verify(chain).doFilter(request, response);
    }

    @Test
    void honorsXForwardedForHeader() throws Exception {
        // Simulate Render's proxy: same RemoteAddr (the LB), but two different
        // X-Forwarded-For values should map to two buckets.
        for (var i = 0; i < 5; i++) {
            var req = authRequest("10.0.0.1", "203.0.113.10");
            filter.doFilterInternal(req, new MockHttpServletResponse(), chain);
        }
        var differentClient = authRequest("10.0.0.1", "203.0.113.99");
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(differentClient, response, chain);
        assertEquals(200, response.getStatus());
    }

    private MockHttpServletResponse invokeAuthRequest(String ip) throws Exception {
        var request = authRequest(ip, null);
        var response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);
        return response;
    }

    private MockHttpServletRequest authRequest(String remoteAddr, String forwardedFor) {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }
}
