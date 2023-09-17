package com.soulsoftworks.sockbowlgame.model.socket.out;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    @JsonIgnore
    private List<String> recipients = new ArrayList<>();

    private String messageContentType;
    @JsonProperty
    public String getMessageContentType() {
        if (messageContentType == null) {
            messageContentType = this.getClass().getSimpleName();
        }
        return messageContentType;
    }

    public abstract MessageTypes getMessageType();
}
