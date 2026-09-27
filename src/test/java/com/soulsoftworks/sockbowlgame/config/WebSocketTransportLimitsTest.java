package com.soulsoftworks.sockbowlgame.config;

import com.soulsoftworks.sockbowlgame.controller.resolver.GameSessionInjectionResolver;
import com.soulsoftworks.sockbowlgame.security.stomp.StompInboundInterceptor;
import com.soulsoftworks.sockbowlgame.websocket.ClientIpHandshakeInterceptor;
import jakarta.websocket.server.ServerContainer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;
import org.springframework.util.unit.DataSize;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * WebSocket transport limits (plan m4-limits section 2.5): 16 KiB messages,
 * 512 KiB send buffer, 15s send time, 30s to the first frame, and the servlet
 * container's WebSocket buffers sized to the message limit.
 */
class WebSocketTransportLimitsTest {

    private static WebSocketConfig config(WebSocketLimitsProperties limits) {
        return new WebSocketConfig(mock(GameSessionInjectionResolver.class), mock(StompInboundInterceptor.class),
                mock(ClientIpHandshakeInterceptor.class), limits);
    }

    @Test
    void defaultsMatchThePlan() {
        WebSocketTransportRegistration registration = mock(WebSocketTransportRegistration.class);
        config(new WebSocketLimitsProperties()).configureWebSocketTransport(registration);

        verify(registration).setMessageSizeLimit(16 * 1024);
        verify(registration).setSendBufferSizeLimit(512 * 1024);
        verify(registration).setSendTimeLimit(15_000);
        verify(registration).setTimeToFirstMessage(30_000);
    }

    @Test
    void limitsAreConfigurable() {
        WebSocketLimitsProperties limits = new WebSocketLimitsProperties();
        limits.setMessageSizeLimit(DataSize.ofKilobytes(8));
        limits.setSendTimeLimit(java.time.Duration.ofSeconds(5));
        WebSocketTransportRegistration registration = mock(WebSocketTransportRegistration.class);
        config(limits).configureWebSocketTransport(registration);
        verify(registration).setMessageSizeLimit(8 * 1024);
        verify(registration).setSendTimeLimit(5_000);
    }

    @Test
    void containerBuffersFollowTheMessageLimit() {
        ServerContainer container = mock(ServerContainer.class);
        MockServletContext context = new MockServletContext();
        context.setAttribute(ServerContainer.class.getName(), container);

        config(new WebSocketLimitsProperties()).setServletContext(context);

        verify(container).setDefaultMaxTextMessageBufferSize(16 * 1024);
        verify(container).setDefaultMaxBinaryMessageBufferSize(16 * 1024);
    }

    @Test
    void mockServletContextsWithoutAContainerAreLeftAlone() {
        ServerContainer unrelated = mock(ServerContainer.class);
        config(new WebSocketLimitsProperties()).setServletContext(new MockServletContext());
        verifyNoInteractions(unrelated);
    }
}
