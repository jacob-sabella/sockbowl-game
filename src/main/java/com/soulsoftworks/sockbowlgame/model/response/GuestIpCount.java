package com.soulsoftworks.sockbowlgame.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One entry of {@link GlobalUsage#getTopGuestIps()}: a guest IP and its currently-active hosted sessions. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestIpCount {

    private String ip;
    private long sessions;
}
