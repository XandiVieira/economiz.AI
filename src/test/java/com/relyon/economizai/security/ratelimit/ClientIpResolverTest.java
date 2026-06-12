package com.relyon.economizai.security.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTest {

    @Test
    void usesXForwardedForWhenPresent() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.10");

        assertEquals("203.0.113.10", ClientIpResolver.resolve(request));
    }

    @Test
    void takesFirstHopFromMultiProxyChain() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.10, 198.51.100.5, 10.0.0.1");

        assertEquals("203.0.113.10", ClientIpResolver.resolve(request));
    }

    @Test
    void trimsWhitespaceAroundFirstHop() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "  203.0.113.10 , 198.51.100.5");

        assertEquals("203.0.113.10", ClientIpResolver.resolve(request));
    }

    @Test
    void fallsBackToRemoteAddrWithoutHeader() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.50");

        assertEquals("192.168.1.50", ClientIpResolver.resolve(request));
    }

    @Test
    void fallsBackToRemoteAddrWhenHeaderIsBlank() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.50");
        request.addHeader("X-Forwarded-For", "   ");

        assertEquals("192.168.1.50", ClientIpResolver.resolve(request));
    }

    @Test
    void fallsBackToRemoteAddrWhenFirstHopIsEmpty() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.50");
        request.addHeader("X-Forwarded-For", " , 198.51.100.5");

        assertEquals("192.168.1.50", ClientIpResolver.resolve(request));
    }

    @Test
    void returnsUnknownWhenNothingResolvable() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(null);

        assertEquals("unknown", ClientIpResolver.resolve(request));
    }
}
