package com.soulsoftworks.sockbowlgame.client;

import com.soulsoftworks.sockbowlgame.config.SockbowlQuestionsConfig;
import com.soulsoftworks.sockbowlgame.model.packet.nodes.Packet;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class PacketClient {

  private final GraphQlClient graphQlClient;

  public PacketClient(SockbowlQuestionsConfig sockbowlQuestionsConfig) {
    this.graphQlClient = HttpGraphQlClient.builder()
        .url(sockbowlQuestionsConfig.getUrl() + "graphql")
        .build();
  }

  /**
   * Fetches a packet by its ID
   *
   * @param packetId The packet ID
   * @return A Mono emitting the Packet object
   */
  public Mono<Packet> getPacketById(String packetId) {
    String query = "query($id: ID!) { getPacketById(id: $id) { id name } }";

    return graphQlClient.document(query)
        .variable("id", packetId)
        .retrieve("getPacketById")
        .toEntity(Packet.class);
  }
}
