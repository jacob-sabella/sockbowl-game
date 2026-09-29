# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

## Project overview

`sockbowl-game` is the Spring Boot backend that owns live game-session state and real-time
match traffic for the Sockbowl quizbowl platform: hosting/joining sessions over REST, and
gameplay (buzzing, judging, round progression) over STOMP/WebSocket. It is one of four Sockbowl
repos: `sockbowl-questions` owns the packet/question domain and AI features, `sockbowl-ng` is
the Angular frontend, and `sockbowl-docker` composes all three plus infra. See `README.md` for
build/run/test commands and the environment variable reference; this file is about the code.

## Package map (`src/main/java/com/soulsoftworks/sockbowlgame/`)

- **`controller/`** — REST controllers (`controller/api`: session, user, admin bans/usage,
  auth-status) and the STOMP message controllers (`controller/websocket`). Auth/limit
  enforcement lives in filters/guards, not the controllers themselves.
- **`security/`** — `SecurityConfig` (auth-on) / `NoSecurityConfig` (auth-off), deny-by-default
  rules; `security/stomp/` is the inbound STOMP guard chain (see "STOMP inbound guard order"
  below).
- **`ratelimit/`** — token-bucket rate limiting (`RateLimitService`, Redis-backed buckets via
  bucket4j/Lettuce for cross-instance policies, `LocalBucketRegistry` for per-connection STOMP
  policies), `UsageKeys` (the shared Redis key contract with `sockbowl-questions`),
  `RateLimitProperties`.
- **`quota/`** — hosted-session and other per-tier quota enforcement (`QuotaService`), quota
  override resync from Redis mirrors.
- **`usage/`** — per-subject usage tracking (`UsageTracker`): touch intervals, recent-IPs list,
  consumed by the admin usage API.
- **`service/`** — core game logic: `SessionService` (create/join), `MessageService` (Kafka
  listener that drives in-match STOMP broadcasts), `GameTimerService` (round/buzz timers),
  `GameSessionLocks` (see below). `service/ban`, `service/processor`, `service/packet`,
  `service/authorization` split out ban enforcement, per-message-type processing, the
  `sockbowl-questions` client wiring, and authorization checks respectively.
- **`model/state/`** — `GameSession` and friends (the Redis JSON document), and
  `GameSanitizer`/`GameSessionSanitizer` (see "Never put answers in broadcast payloads" below).
- **`model/socket/{in,out}/`** — inbound/outbound STOMP message DTOs, grouped by area
  (`game`, `progression`, `config`, `error`). These are the types DGS-generated TypeScript in
  `sockbowl-ng` is built from (`build.gradle`'s `generateTypeScript` task); packet/question DTOs
  are deliberately excluded from that generation (they come from `sockbowlquestions-models`
  instead — see "Models-jar version bump rules" below).
- **`judge/`** — single-player auto-proctor answer judging (fuzzy string + phonetic match).
- **`client/`** — the Feign client to `sockbowl-questions`, plus the client-credentials token
  provider used when auth is on.
- **`config/`** — Spring config beans, including `config/health` (the Kafka-listener readiness
  indicator behind `/actuator/health`).
- **`repository/`** — Redis OM repositories for `GameSession`.

## Security rules to keep

- **Deny by default.** `SecurityConfig` (auth on) lists every allowed route explicitly and ends
  with `.anyRequest().denyAll()`; a new endpoint that needs to be reachable must be added to
  that explicit list, not left to fall through. `NoSecurityConfig` (auth off,
  `SOCKBOWL_AUTH_ENABLED=false`) permits everything and is for local dev / auth-off e2e only —
  never the default in a production compose profile.
- **`GameSessionLocks.withLock` around every read-modify-write of a `GameSession`.** A session
  is one Redis JSON document loaded, mutated in memory and saved back whole by three different
  writers: REST join (`SessionService`), the Kafka listener (`MessageService`), and timer ticks
  (`GameTimerService`). Any load-mutate-save outside `withLock` risks a lost update (the fix for
  M2R2-LIVE-01: a REST join landing mid-processing of another message silently erased the new
  player). The load must happen *inside* the lock — a copy taken before acquiring it is already
  stale by the time the lock is held. Locks are striped and reentrant but a writer must never
  hold one session's lock while taking another's, and a join-code search must never run while
  holding a session lock (R3-G-LOCK) — search once outside the lock, then re-read the session by
  id inside it. See the class Javadoc in `GameSessionLocks` for the full reasoning, including the
  separate global shared/exclusive lock that keeps join-code RediSearch queries from racing a
  concurrent session save.
- **`StompInboundGuard` order matters.** Every bean implementing this interface runs, in
  ascending `order()`, inside the single `StompInboundInterceptor`. Guards with `order() < 0`
  (pre-auth) run before CONNECT authentication and see a `null` principal on CONNECT — this is
  where `StompRateLimitGuard` sits (`-100`), so floods are rejected before spending a JWT decode.
  Guards with `order() >= 0` (post-auth) run after CONNECT authentication;
  `StompDestinationGuard` is `100`, `StompUsageTouchGuard` is `300`. A new guard must be placed
  deliberately relative to these, not just appended. `PASS` continues the chain, `DROP` silently
  drops the frame (socket stays open), and throwing `StompRejectedException` is fatal (ERROR
  frame, socket closed) — pick the right outcome, don't default to fatal for a soft rejection.
- **Sanitizers strip answers before anything goes out.** `GameSanitizer` builds the
  audience-appropriate view of a `GameSession` (`publicMatchView` for spectators/non-proctors:
  no packet questions or answers at all; per-round sanitization that reveals a tossup's question
  but not its answer until it's decided, and never a bonus part's answer until that part is
  judged). Any new outbound message or state view must go through (or extend) these sanitizers,
  never serialize the raw session/question/answer fields directly.
- **`UsageKeys` contract parity between game and questions.** `ratelimit/UsageKeys` is the
  single source of every Redis key the limits/usage/quota code reads or writes, and it has
  **identical contents** in `sockbowl-game` and `sockbowl-questions` (both services share one
  Redis DB for this bookkeeping). `UsageKeysContractTest` pins the exact strings in each repo —
  if you change a key name/format here, you must change it identically in `sockbowl-questions`
  in the same change, or that test fails in one repo and the two services silently stop agreeing
  on where usage/quota data lives.
- **Models-jar version bump rules.** `gradle.properties`' `sockbowlModelsVersion` is an
  open-ended range (currently `[1.0.2,1.1)`), and `configurations.all` disables the dynamic-
  version cache so every build re-resolves to the newest jar in that range — this repo is
  expected to pick up new `sockbowlquestions-models` patch releases automatically, not pin a
  single version. Bump the floor of the range only when a feature here actually needs a
  post-floor addition (the current floor cites the specific commit/feature that raised it —
  follow that pattern), and never widen past `1.1` without confirming `sockbowl-questions` isn't
  about to publish a breaking `1.1.0`.
- **Never put answers in broadcast payloads.** This is the concrete consequence of the
  sanitizer rule above, worth restating because it is the single most sensitive invariant in
  this repo: a bug here is a live, exploitable "read the answer before buzzing" cheat, not just
  a data-shape bug. When adding a new outbound STOMP message or REST response that touches
  `GameSession`, ask "could this leak a hidden answer to a player who isn't allowed to see it
  yet" before shipping it, and add/extend a sanitizer test for it.

## Testing

```
./gradlew test -PsockbowlUseMavenLocal=true    # everything (unit + integration)
```

Integration tests use Testcontainers and need a working Docker daemon:

- **Redis** (`com.redis:testcontainers-redis`) — session storage, rate-limit buckets, usage/ban
  mirrors.
- **PostgreSQL** (`org.testcontainers:testcontainers-postgresql`) — the JPA side of the ban/
  IP-ban and quota-override mirror ITs.
- **Keycloak** (`com.github.dasniko:testcontainers-keycloak`) — auth-on ITs that mint real
  service tokens against a test realm (e.g. `QuestionsTokenProviderIT`).

No external services need to be started by hand first; Testcontainers manages its own
containers per test class. See `README.md` for the `sockbowlquestions-models`/mavenLocal
resolution this build needs before it can compile at all.

## Never

- Never serialize `GameSession` (or a packet/question) directly onto an outbound message or
  REST response without going through the sanitizers.
- Never mutate a `GameSession` outside `GameSessionLocks.withLock`.
- Never add a reachable route to `SecurityConfig` by relying on fallthrough — list it
  explicitly.
- Never change a `UsageKeys` string in this repo without making the identical change in
  `sockbowl-questions` in the same change.
