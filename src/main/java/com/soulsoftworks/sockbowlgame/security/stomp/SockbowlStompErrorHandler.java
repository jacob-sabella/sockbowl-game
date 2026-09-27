package com.soulsoftworks.sockbowlgame.security.stomp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a failure while processing a client frame into a typed STOMP ERROR frame
 * (plan m2-auth section 2.5, AUTH-16). The server closes the socket after it.
 *
 * <p>Wire format (frozen for ng and M4):
 * <pre>
 * ERROR
 * message:&lt;CODE&gt;
 * x-sockbowl-error:&lt;CODE&gt;
 * content-type:application/json
 *
 * {"code":"&lt;CODE&gt;","message":"&lt;detail&gt;","retryAfterSeconds":null}
 * </pre>
 * The {@link StompRejectedException} is found anywhere in the cause chain (the
 * channel wraps interceptor exceptions). Anything else becomes {@code INTERNAL}
 * with a generic message: no exception text or stack trace reaches the wire. The
 * body may gain optional fields later (M4 adds {@code policy}).
 */
public class SockbowlStompErrorHandler extends StompSubProtocolErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(SockbowlStompErrorHandler.class);

    public static final String ERROR_CODE_HEADER = "x-sockbowl-error";

    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        StompRejectedException rejected = findRejection(ex);
        StompErrorCode code;
        String detail;
        Integer retryAfterSeconds;
        if (rejected != null) {
            code = rejected.getCode();
            detail = rejected.getDetail() != null ? rejected.getDetail() : code.name();
            retryAfterSeconds = rejected.getRetryAfterSeconds();
        } else {
            log.error("Unexpected error processing a client STOMP frame", ex);
            code = StompErrorCode.INTERNAL;
            detail = "Internal server error";
            retryAfterSeconds = null;
        }

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(code.name());
        accessor.setNativeHeader(ERROR_CODE_HEADER, code.name());
        accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
        accessor.setLeaveMutable(true);

        StompHeaderAccessor clientAccessor = clientMessage == null ? null
                : MessageHeaderAccessor.getAccessor(clientMessage, StompHeaderAccessor.class);
        if (clientAccessor != null && clientAccessor.getReceipt() != null) {
            accessor.setReceiptId(clientAccessor.getReceipt());
        }
        return handleInternal(accessor, body(code, detail, retryAfterSeconds), ex, clientAccessor);
    }

    static byte[] body(StompErrorCode code, String detail, Integer retryAfterSeconds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.name());
        body.put("message", detail);
        body.put("retryAfterSeconds", retryAfterSeconds);
        return GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
    }

    static StompRejectedException findRejection(Throwable ex) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (current instanceof StompRejectedException rejected) {
                return rejected;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }
}
