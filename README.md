# Sockbowl Game

Sockbowl Game is the backend service for the Sockbowl platform, providing the core game session management, real-time messaging, and state handling for multiplayer quizbowl matches. It is built with Java and Spring Boot, using Redis for caching game sessions and Kafka for event-driven communication.

## Features

- **Game Session Management:** Create, join, and manage game sessions using unique join codes.
- **Real-Time Messaging:** Utilizes Kafka to process and distribute game events and messages to players.
- **Stateful Gameplay:** Maintains player roster, match state, rounds, and in-game progression.
- **WebSocket Support:** Designed for integration with interactive web applications via WebSocket.
- **Redis Integration:** Efficient caching and retrieval of game state data.

## Technologies

- **Java** (Spring Boot)
- **Redis** (document repository for game state)
- **Kafka** (real-time messaging)
- **Gradle** (build system)

## Getting Started

See `HELP.md` for guides on setup and running locally. For reference:
- [Spring Boot Gradle Plugin Reference Guide](https://docs.spring.io/spring-boot/docs/2.7.3/gradle-plugin/reference/html/)
- [WebSocket Guide](https://spring.io/guides/gs/messaging-stomp-websocket/)

## License

MIT License. See `LICENSE` for details.

---

*Created by Jacob Sabella*
