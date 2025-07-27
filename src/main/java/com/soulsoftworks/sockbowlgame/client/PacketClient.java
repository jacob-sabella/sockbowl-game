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
   * Fetches a packet by its ID with all related fields.
   *
   * @param packetId The packet ID
   * @return A Mono emitting the Packet object with all nested fields
   */
  public Mono<Packet> getPacketById(String packetId) {
    String query = """
        query($id: ID!) {
          getPacketById(id: $id) {
            id
            name
            difficulty {
              id
              name
            }
            tossups {
              id
              order
              tossup {
                id
                question
                answer
                subcategory {
                  id
                  name
                  category {
                    id
                    name
                  }
                }
              }
            }
            bonuses {
              id
              order
              bonus {
                id
                preamble
                subcategory {
                  id
                  name
                  category {
                    id
                    name
                  }
                }
                bonusParts {
                  id
                  order
                  bonusPart {
                    id
                    question
                    answer
                  }
                }
              }
            }
          }
        }""";

    return graphQlClient.document(query)
        .variable("id", packetId)
        .retrieve("getPacketById")
        .toEntity(Packet.class);
  }
}
