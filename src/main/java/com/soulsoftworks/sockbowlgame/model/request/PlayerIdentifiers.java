package com.soulsoftworks.sockbowlgame.model.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlayerIdentifiers {
    private String simpSessionId;
    private String playerSecret;
}
