package org.springframework.boot.web.context;

import org.springframework.boot.web.server.WebServer;

/**
 * Spring Boot 4.0 compatibility bridge.
 * Delegates to org.springframework.boot.web.server.context.WebServerInitializedEvent
 */
@Deprecated
public abstract class WebServerInitializedEvent extends org.springframework.boot.web.server.context.WebServerInitializedEvent {
    public WebServerInitializedEvent(WebServer webServer) {
        super(webServer);
    }
}
