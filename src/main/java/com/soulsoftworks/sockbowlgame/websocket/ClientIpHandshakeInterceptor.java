package com.soulsoftworks.sockbowlgame.websocket;

import com.soulsoftworks.sockbowlgame.ratelimit.ClientIpResolver;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.InetSocketAddress;
import java.util.Map;

/**
 * Copies the client address of the WebSocket handshake into the session
 * attributes (plan m4-limits section 2.1, WP-G3), so every STOMP frame on the
 * connection can be limited and IP-ban checked without a request object.
 *
 * <ul>
 *   <li>{@value #CLIENT_IP}: the normalized limiter key
 *       ({@link ClientIpResolver#normalize}, IPv6 truncated to its /64);</li>
 *   <li>{@value #CLIENT_IP_RAW}: the raw remote address, for CIDR ban matching.</li>
 * </ul>
 * The address is the servlet remote address only (never a forwarded header;
 * see {@link ClientIpResolver}).
 */
@Component
public class ClientIpHandshakeInterceptor implements HandshakeInterceptor {

    public static final String CLIENT_IP = "sockbowl.clientIp";
    public static final String CLIENT_IP_RAW = "sockbowl.clientIpRaw";

    private final ClientIpResolver clientIpResolver;

    public ClientIpHandshakeInterceptor(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String raw;
        String normalized;
        if (request instanceof ServletServerHttpRequest servlet) {
            raw = clientIpResolver.rawAddress(servlet.getServletRequest());
            normalized = clientIpResolver.resolve(servlet.getServletRequest());
        } else {
            InetSocketAddress remote = request.getRemoteAddress();
            raw = remote == null || remote.getAddress() == null
                    ? ClientIpResolver.UNKNOWN : remote.getAddress().getHostAddress();
            normalized = ClientIpResolver.normalize(raw);
        }
        attributes.put(CLIENT_IP_RAW, raw);
        attributes.put(CLIENT_IP, normalized);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // nothing to do
    }

    /** The normalized address stored at handshake, or {@link ClientIpResolver#UNKNOWN}. */
    public static String clientIp(Map<String, Object> sessionAttributes) {
        Object value = sessionAttributes == null ? null : sessionAttributes.get(CLIENT_IP);
        return value instanceof String s ? s : ClientIpResolver.UNKNOWN;
    }

    /** The raw address stored at handshake, or {@link ClientIpResolver#UNKNOWN}. */
    public static String rawClientIp(Map<String, Object> sessionAttributes) {
        Object value = sessionAttributes == null ? null : sessionAttributes.get(CLIENT_IP_RAW);
        return value instanceof String s ? s : ClientIpResolver.UNKNOWN;
    }
}
