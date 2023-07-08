package com.soulsoftworks.sockbowlgame.model.socket.out;

import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Singular;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@SuperBuilder(toBuilder = true)
public abstract class SockbowlOutMessage {

    @Singular("recipient")
    private transient List<String> recipients = new ArrayList<>();

    public abstract MessageTypes getMessageType();
}
