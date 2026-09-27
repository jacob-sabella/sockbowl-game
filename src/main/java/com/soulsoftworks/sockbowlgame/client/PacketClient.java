package com.soulsoftworks.sockbowlgame.client;

import com.soulsoftworks.sockbowlgame.client.QuestionsUnavailableException.Reason;
import com.soulsoftworks.sockbowlgame.config.SockbowlQuestionsConfig;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.ClientResponseField;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Reads packets from sockbowl-questions for the game (server to server).
 *
 * <p>Failures are typed (AUTH-18), so the SetMatchPacket handler can answer the
 * player instead of throwing out of the Kafka listener:
 * <ul>
 *   <li>{@link QuestionsUnavailableException} with {@code TOKEN} (no service
 *       token), {@code AUTH} (HTTP 401/403 or an UNAUTHORIZED/FORBIDDEN GraphQL
 *       error), {@code TIMEOUT} (past {@code sockbowl.questions.timeout}),
 *       {@code UNAVAILABLE} (unreachable, 5xx) or {@code BAD_RESPONSE};</li>
 *   <li>{@link PacketNotFoundException} when questions answers with no packet.</li>
 * </ul>
 *
 * <p>The packet's {@code visibility} and {@code owner.id} are mapped onto the
 * models {@link Packet} ({@code visibility}, {@code ownerId}) for the
 * SetMatchPacket draft check (D2/D15).
 */
@Component
public class PacketClient {

  private static final Logger log = LoggerFactory.getLogger(PacketClient.class);

  static final String QUERY = """
      query($id: ID!) {
        getPacketById(id: $id) {
          id
          name
          visibility
          owner {
            id
          }
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

  private static final String ROOT = "getPacketById";

  private final HttpGraphQlClient graphQlClient;
  private final QuestionsTokenProvider tokenProvider;
  private final Duration timeout;

  public PacketClient(SockbowlQuestionsConfig sockbowlQuestionsConfig, QuestionsTokenProvider tokenProvider) {
    this.graphQlClient = HttpGraphQlClient.builder()
        .url(sockbowlQuestionsConfig.getUrl() + "graphql")
        .build();
    this.tokenProvider = tokenProvider;
    this.timeout = sockbowlQuestionsConfig.getTimeout() != null
        ? sockbowlQuestionsConfig.getTimeout() : Duration.ofSeconds(10);
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
   * @return A Mono emitting the Packet with all nested fields, or failing with
   * {@link QuestionsUnavailableException} / {@link PacketNotFoundException}
   */
  public Mono<Packet> getPacketById(String packetId) {
    return Mono.defer(() -> {
          String token = tokenProvider.getTokenOrNull();
          HttpGraphQlClient client = (token == null)
              ? graphQlClient
              : graphQlClient.mutate().header("Authorization", "Bearer " + token).build();
          return client.document(QUERY)
              .variable("id", packetId)
              .execute();
        })
        .timeout(timeout)
        .map(response -> toPacket(packetId, response))
        .onErrorMap(e -> !(e instanceof QuestionsUnavailableException || e instanceof PacketNotFoundException),
            this::classify);
  }

  private Packet toPacket(String packetId, ClientGraphQlResponse response) {
    if (hasAuthError(response.getErrors())) {
      tokenProvider.invalidate();
      throw new QuestionsUnavailableException(Reason.AUTH,
          "sockbowl-questions refused the service token: " + summarize(response.getErrors()));
    }
    ClientResponseField root = response.field(ROOT);
    if (!response.isValid() || (!root.getErrors().isEmpty() && root.getValue() == null)) {
      throw new QuestionsUnavailableException(Reason.BAD_RESPONSE,
          "getPacketById failed: " + summarize(response.getErrors()));
    }
    if (root.getValue() == null) {
      throw new PacketNotFoundException(packetId);
    }

    try {
      Packet packet = new Packet();
      packet.setId(response.field(ROOT + ".id").getValue());
      packet.setName(response.field(ROOT + ".name").getValue());
      packet.setVisibility(visibilityOf(response.field(ROOT + ".visibility").getValue()));
      packet.setOwnerId(response.field(ROOT + ".owner.id").getValue());
      ClientResponseField difficulty = response.field(ROOT + ".difficulty");
      packet.setDifficulty(difficulty.getValue() == null ? null : difficulty.toEntity(Difficulty.class));
      packet.setTossups(listOf(response.field(ROOT + ".tossups"), ContainsTossup.class));
      packet.setBonuses(listOf(response.field(ROOT + ".bonuses"), ContainsBonus.class));
      return packet;
    } catch (RuntimeException e) {
      throw new QuestionsUnavailableException(Reason.BAD_RESPONSE,
          "could not read packet " + packetId + ": " + e.getClass().getSimpleName(), e);
    }
  }

  /** A mutable list (SetMatchPacket sorts it in place); empty when the field is null. */
  private static <T> List<T> listOf(ClientResponseField field, Class<T> type) {
    return field.getValue() == null ? new ArrayList<>() : new ArrayList<>(field.toEntityList(type));
  }

  /**
   * Map the wire value onto this build's {@link PacketVisibility}. A value this
   * models jar doesn't know (a newer questions) is read as {@code DRAFT}, the
   * most restrictive visibility, so an unknown value can never open a packet
   * up; null stays null (legacy packet, read as PUBLISHED).
   */
  static PacketVisibility visibilityOf(Object wire) {
    if (wire == null) {
      return null;
    }
    String name = wire.toString();
    for (PacketVisibility v : PacketVisibility.values()) {
      if (v.name().equals(name)) {
        return v;
      }
    }
    log.warn("Unknown packet visibility '{}' from sockbowl-questions; treating it as DRAFT", name);
    return PacketVisibility.DRAFT;
  }

  private static boolean hasAuthError(List<ResponseError> errors) {
    return errors.stream().anyMatch(e -> {
      Object classification = e.getErrorType() != null ? e.getErrorType().toString() : null;
      if (classification == null || "INTERNAL_ERROR".equals(classification)) {
        Map<String, Object> ext = e.getExtensions();
        classification = ext == null ? null : ext.get("classification");
      }
      return ErrorType.UNAUTHORIZED.name().equals(String.valueOf(classification))
          || ErrorType.FORBIDDEN.name().equals(String.valueOf(classification));
    });
  }

  private static String summarize(List<ResponseError> errors) {
    if (errors.isEmpty()) {
      return "no data";
    }
    ResponseError first = errors.get(0);
    return first.getErrorType() + " " + first.getMessage()
        + (errors.size() > 1 ? " (+" + (errors.size() - 1) + " more)" : "");
  }

  /** The first cause in {@code e}'s chain (itself included) of the given type, or null. */
  private static <T extends Throwable> T causeOf(Throwable e, Class<T> type) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (type.isInstance(t)) {
        return type.cast(t);
      }
    }
    return null;
  }

  private Throwable classify(Throwable e) {
    if (causeOf(e, TimeoutException.class) != null) {
      return new QuestionsUnavailableException(Reason.TIMEOUT,
          "no answer from sockbowl-questions within " + timeout, e);
    }
    WebClientResponseException http = causeOf(e, WebClientResponseException.class);
    if (http != null) {
      int status = http.getStatusCode().value();
      if (status == 401 || status == 403) {
        tokenProvider.invalidate();
        return new QuestionsUnavailableException(Reason.AUTH,
            "sockbowl-questions answered HTTP " + status, e);
      }
      return new QuestionsUnavailableException(status >= 500 ? Reason.UNAVAILABLE : Reason.BAD_RESPONSE,
          "sockbowl-questions answered HTTP " + status, e);
    }
    WebClientRequestException io = causeOf(e, WebClientRequestException.class);
    if (io != null) {
      return new QuestionsUnavailableException(Reason.UNAVAILABLE,
          "sockbowl-questions unreachable: " + io.getMostSpecificCause().getClass().getSimpleName(), e);
    }
    return new QuestionsUnavailableException(Reason.BAD_RESPONSE,
        "packet fetch failed: " + e.getClass().getSimpleName(), e);
  }
}
