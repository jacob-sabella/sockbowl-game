package com.soulsoftworks.sockbowlgame.client;

import com.soulsoftworks.sockbowlgame.model.packet.nodes.Packet;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.List;

/**
 * Client for interacting with the sockbowl-questions service to deal with Packets
 */
@FeignClient(value="PacketClient",
             url = "http://docker1.lan:7002/api/v1/packets",
             configuration = ClientConfiguration.class)
public interface PacketClient {

    /**
     * For a given packet ID, retrieve the packet id
     * @param id Packet ID
     * @return Response de-serialized into Packet object
     */
    @RequestMapping(method = RequestMethod.GET, value = "/get/{id}")
    Packet getPacketById(@PathVariable String id);

}
