package com.soulsoftworks.sockbowlgame.client;

import com.soulsoftworks.sockbowlgame.config.SockbowlQuestionsConfig;
import com.soulsoftworks.sockbowlgame.generated.packet.types.Packet;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class PacketClient {

  private final HttpGraphQlClient graphQlClient;
  private final QuestionsTokenProvider tokenProvider;

  public PacketClient(SockbowlQuestionsConfig sockbowlQuestionsConfig, QuestionsTokenProvider tokenProvider) {
    this.graphQlClient = HttpGraphQlClient.builder()
        .url(sockbowlQuestionsConfig.getUrl() + "graphql")
        .build();
    this.tokenProvider = tokenProvider;
  }

  /**
   * Fetches a packet by its ID with all related fields.
   *
   * <p>When authentication is enabled, the request is authorized with a
   * client-credentials bearer token obtained from {@link QuestionsTokenProvider}
   * since this call happens server-to-server, deep inside an async WebSocket
   * message flow with no end-user token available. In guest mode (auth
   * disabled), the request is sent unauthenticated as before.
   *
   * @param packetId The packet ID
   * @return A Mono emitting the Packet object with all nested fields
   */
  public Mono<Packet> getPacketById(String packetId) {
    String token = tokenProvider.getTokenOrNull();
    HttpGraphQlClient client = (token == null)
        ? graphQlClient
        : graphQlClient.mutate().header("Authorization", "Bearer " + token).build();

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

    return client.document(query)
        .variable("id", packetId)
        .retrieve("getPacketById")
        .toEntity(Packet.class);
  }
}
