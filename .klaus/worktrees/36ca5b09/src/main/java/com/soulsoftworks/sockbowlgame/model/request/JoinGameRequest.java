package com.soulsoftworks.sockbowlgame.model.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JoinGameRequest {
    String playerSessionId;
    String joinCode;
    @NotBlank(message = "Name cannot be blank")
    String name;
}
