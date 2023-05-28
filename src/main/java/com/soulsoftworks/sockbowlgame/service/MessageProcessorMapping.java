package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.game.socket.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.SockbowlOutMessage;

import java.util.HashMap;
import java.util.function.Function;

public class MessageProcessorMapping {

    private final HashMap<Class<? extends SockbowlInMessage>, Function<SockbowlInMessage, SockbowlOutMessage>> typeToFunctionMap = new HashMap<>();

    public void registerProcessor(Class<? extends SockbowlInMessage> type, Function<SockbowlInMessage, SockbowlOutMessage> function) {
        typeToFunctionMap.put(type, function);
    }

    public Function<SockbowlInMessage, SockbowlOutMessage> getProcessor(SockbowlInMessage message) {
        return typeToFunctionMap.get(message.getClass());
    }
}
