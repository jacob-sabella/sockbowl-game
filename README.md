# Sockbowl Game

Sockbowl Game is the backend service for the Sockbowl platform: it owns live game-session
state, real-time messaging over STOMP/WebSocket, and REST endpoints for hosting and joining
matches. It is one of four repos in the Sockbowl platform (`sockbowl-game`, `sockbowl-questions`,
`sockbowl-ng`, `sockbowl-docker`); this README covers building and testing this repo in
isolation. To run the full stack, see `sockbowl-docker`'s README.

## Stack

- **Java 25** (LTS), provisioned automatically via the [foojay resolver](https://github.com/gradle/foojay-toolchains)
  even if it isn't already installed — no manual JDK setup needed.
- **Spring Boot 4.1.x** / Spring Cloud 2025.1.x
- **Gradle 9.x** (via the wrapper; always use `./gradlew`, never a system-installed Gradle)
- **Redis** (game-session cache/document store, via Redis OM Spring)
- **Kafka** (game event bus)
- **PostgreSQL** (user records, only consulted when auth is enabled)
- **Keycloak** (OIDC token issuer/validator, only consulted when auth is enabled)

## Building and testing

```
./gradlew build      # compile + test + assemble
./gradlew test        # unit + integration tests only
./gradlew bootRun      # run locally (needs the env vars below; see sockbowl-docker for a full stack)
```

Integration tests use [Testcontainers](https://testcontainers.com) (Redis, PostgreSQL,
Keycloak) and need a working Docker daemon; nothing else needs to be running first.

### The `sockbowlquestions-models` dependency

This repo depends on `com.soulsoftworks:sockbowlquestions-models`, the packet/question domain
model published by `sockbowl-questions` (the single source of truth for that contract). By
default it resolves from GitHub Packages, which **returns 401 without a `read:packages`
token** — this is the only thing that keeps a plain `./gradlew build` from working out of the
box on a machine without that token configured.

For local development and CI without a Packages token, build `sockbowl-questions` first and
point this build at your local Maven cache instead:

```
# in sockbowl-questions:
./gradlew publishToMavenLocal

# in sockbowl-game:
./gradlew build -PsockbowlUseMavenLocal=true
# (or the equivalent env var: SOCKBOWL_USE_MAVEN_LOCAL=true)
```

Setting the property (or env var) is **sufficient by itself** — `build.gradle`'s own
repository logic already switches from GitHub Packages to `mavenLocal()` when it is set, so no
extra Gradle init script is needed to add `mavenLocal()` as a repository. (An init script that
only adds `mavenLocal()` as an extra repository, without setting this property, has no effect
here — `sockbowlUseMavenLocal`/`SOCKBOWL_USE_MAVEN_LOCAL` is what actually switches the
resolution.) CI's `test` job uses this same path (see `.github/workflows/gradle.yml`); the
`build-and-publish` job keeps the GitHub Packages resolution, since it needs the real published
coordinates for a release build.

`gradle.properties` pins the accepted `sockbowlquestions-models` range
(`sockbowlModelsVersion`); `configurations.all` never caches the resolved dynamic version, so
every build picks up the newest jar in that range instead of resolving once and going stale.

## Configuration

All configuration is environment-driven (see `src/main/resources/application.properties` for
the authoritative list; defaults shown are used when a variable is unset). The variables below
are consumed directly by this service. `sockbowl-docker/.env.example` is the canonical list for
running the full stack; when adding a new `${VAR}` here, add it there too.

### Core

| Variable | Default | Purpose |
|---|---|---|
| `SOCKBOWL_PORT` | *(required)* | HTTP/WebSocket listen port |
| `SOCKBOWL_REDIS_HOST` / `SOCKBOWL_REDIS_PORT` | *(required)* | Redis connection |
| `SOCKBOWL_DATABASE` | *(required)* | Redis logical DB index |
| `SOCKBOWL_KAFKA_BOOTSTRAP_SERVERS` / `SOCKBOWL_KAFKA_GAME_TOPIC` | *(required)* | Kafka connection and topic |
| `SOCKBOWL_QUESTIONS_URL` | *(required)* | Base URL of `sockbowl-questions` |
| `SOCKBOWL_QUESTIONS_TIMEOUT` | `10s` | Packet-fetch timeout (a Kafka listener thread blocks on this) |
| `SOCKBOWL_GAME_ALLOWED_ORIGINS` | `http://localhost:4200` | CORS/WebSocket allowed origin |

### Auth (see "Auth on vs. off" below)

| Variable | Default | Purpose |
|---|---|---|
| `SOCKBOWL_AUTH_ENABLED` | `false` | Master auth switch |
| `SOCKBOWL_AUTH_AUDIENCE` | `sockbowl-api` | Required JWT audience |
| `KEYCLOAK_GAME_BACKEND_CLIENT_ID` | `sockbowl-game-backend` | Service-account client id (marks a token as service-to-service, not a user) |
| `KEYCLOAK_ISSUER_URI` | `http://sockbowl.com:8080/realms/sockbowl` | OIDC issuer |
| `KEYCLOAK_JWK_SET_URI` | `<issuer>/protocol/openid-connect/certs` | JWK set (lazily fetched on first token, not at boot) |
| `KEYCLOAK_TOKEN_URI` | `<issuer>/protocol/openid-connect/token` | Token endpoint for the service-account client-credentials grant |
| `SOCKBOWL_GAME_BACKEND_SECRET` | *(empty)* | Client secret for the `questions-svc` service-account registration |
| `SOCKBOWL_DB_URL` / `SOCKBOWL_DB_USERNAME` / `SOCKBOWL_DB_PASSWORD` | `jdbc:postgresql://localhost:5432/sockbowl_users` / `postgres` / `123456789` | User-record Postgres DB (only consulted with auth on) |
| `SOCKBOWL_FORWARD_HEADERS_STRATEGY` | `none` | `none` or `native`; must pair with a trusted-proxy regex to honor forwarded IPs |
| `SOCKBOWL_TRUSTED_PROXIES_REGEX` | *(empty)* | Required, non-blank, when the strategy above is `native` |
| `SOCKBOWL_REMOTE_IP_HEADER` | `x-forwarded-for` | Header read for the client IP once a proxy is trusted (e.g. `CF-Connecting-IP` behind Cloudflare) |

### Rate limits and quotas (see `docs/limits.md` in `sockbowl-docker` for the full design)

Every rate-limit and quota policy has a `SOCKBOWL_RL_*` / `SOCKBOWL_QUOTA_*` env override; the
defaults live in `application.properties` under the "M4 limits, quotas and abuse controls"
block. Notable ones:

- `SOCKBOWL_RATELIMIT_ENABLED` (default `true`) — master rate-limit switch; fails open on Redis
  errors except for explicitly fail-closed policies.
- `SOCKBOWL_QUOTA_ENABLED` (default `true`) — master quota switch.
- `SOCKBOWL_QUOTA_{GUEST,PLAYER,AUTHOR,MODERATOR}_HOSTED_SESSIONS` — per-tier concurrent
  hosted-session caps (game enforces this one; the AI/imports/packets-owned quotas are
  enforced by `sockbowl-questions` and only *reported* here via the admin usage API).
- `SOCKBOWL_AI_SERVER_DAILY_BUDGET` (default `200`) — the shared server-key AI budget, enforced
  in `sockbowl-questions`, surfaced here for the admin UI.

## Health endpoint

`GET /actuator/health` is the only exposed actuator endpoint, unauthenticated by design (see
`SecurityConfig`/`NoSecurityConfig`), and reports no details (`show-details=never`). It stays
`DOWN` until the game-consumers Kafka listener has a non-empty partition assignment, so an
orchestrator's healthcheck never marks a freshly-started instance ready while its consumer
group is still rebalancing. `GET /actuator/health/readiness` exposes the same signal
(`readinessState` + `kafkaListenerReadiness`) as a dedicated readiness probe.

## Auth on vs. off

- **`SOCKBOWL_AUTH_ENABLED=false`** (the default here; `sockbowl-docker` defaults it to `true`
  for compose): `NoSecurityConfig` permits every request. No Postgres or Keycloak connection is
  needed. Guests get a `playerSecret`-based seat with no signed-in identity.
- **`SOCKBOWL_AUTH_ENABLED=true`**: `SecurityConfig` applies, deny-by-default (see
  `CLAUDE.md`). Postgres and Keycloak must be reachable. Bearer tokens are validated against
  `KEYCLOAK_ISSUER_URI`/`KEYCLOAK_JWK_SET_URI` and must carry `SOCKBOWL_AUTH_AUDIENCE`.

## How this fits the stack

`sockbowl-game` is one of three application images built and run by `sockbowl-docker`'s
compose files. It reads packet/question data from `sockbowl-questions` over HTTP (with a
client-credentials token when auth is on), shares one Redis instance and DB index scheme with
`sockbowl-questions` for cross-service rate-limit/usage/quota bookkeeping (see `CLAUDE.md`'s
"UsageKeys contract" section), and is the only backend `sockbowl-ng` (the frontend) talks to
directly for session/game traffic. See `sockbowl-docker/README.md` for bringing up the full
stack, including from a clean clone.

## License

MIT License. See `LICENSE` for details.

---

*Created by Jacob Sabella*
