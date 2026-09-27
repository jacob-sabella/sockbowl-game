package com.soulsoftworks.sockbowlgame.websocket;

import com.soulsoftworks.sockbowlgame.ratelimit.ClientIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The handshake stores the servlet remote address (never a forwarded header)
 * for the STOMP limiter and IP-ban check (plan m4-limits section 2.1).
 */
class ClientIpHandshakeInterceptorTest {

    private final ClientIpHandshakeInterceptor interceptor = new ClientIpHandshakeInterceptor(new ClientIpResolver());

    private Map<String, Object> handshake(MockHttpServletRequest request) {
        Map<String, Object> attributes = new HashMap<>();
        boolean proceed = interceptor.beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()), mock(WebSocketHandler.class),
                attributes);
        assertThat(proceed).isTrue();
        return attributes;
    }

    @Test
    void storesTheRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sockbowl-game");
        request.setRemoteAddr("203.0.113.9");
        Map<String, Object> attributes = handshake(request);
        assertThat(attributes).containsEntry(ClientIpHandshakeInterceptor.CLIENT_IP, "203.0.113.9")
                .containsEntry(ClientIpHandshakeInterceptor.CLIENT_IP_RAW, "203.0.113.9");
        assertThat(ClientIpHandshakeInterceptor.clientIp(attributes)).isEqualTo("203.0.113.9");
    }

    @Test
    void spoofedForwardedHeadersAreIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sockbowl-game");
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.addHeader("X-Real-IP", "5.6.7.8");
        assertThat(handshake(request)).containsEntry(ClientIpHandshakeInterceptor.CLIENT_IP, "203.0.113.9");
    }

    @Test
    void ipv6IsKeyedByItsSlash64ButBanMatchedOnTheFullAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sockbowl-game");
        request.setRemoteAddr("2001:db8:1:2:aaaa:bbbb:cccc:dddd");
        Map<String, Object> attributes = handshake(request);
        assertThat(attributes).containsEntry(ClientIpHandshakeInterceptor.CLIENT_IP, "2001:db8:1:2::/64")
                .containsEntry(ClientIpHandshakeInterceptor.CLIENT_IP_RAW, "2001:db8:1:2:aaaa:bbbb:cccc:dddd");
    }

    @Test
    void missingAttributesFallBackToUnknown() {
        assertThat(ClientIpHandshakeInterceptor.clientIp(null)).isEqualTo(ClientIpResolver.UNKNOWN);
        assertThat(ClientIpHandshakeInterceptor.rawClientIp(Map.of())).isEqualTo(ClientIpResolver.UNKNOWN);
    }
}
