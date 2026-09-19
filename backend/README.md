# backend/ — Spring Boot modular monolith (Phase 5: ingestion)

Java 21, Spring Boot 4.1.1, Maven. PostgreSQL 16 via Flyway-managed schema.

## What Phase 2 contains

- `pom.xml` — `spring-boot-starter-webmvc`, `spring-boot-starter-data-jpa`,
  `postgresql` (runtime), `spring-boot-starter-flyway` +
  `flyway-database-postgresql` (runtime), `spring-boot-starter-webmvc-test`,
  `testcontainers-postgresql` (test). Nothing else — no Security/JWT,
  MapStruct, OpenAPI, Redis, Kafka, or AI deps.
- `db/migration/V1__initial_schema.sql` — hand-written schema (14 tables,
  FKs, unique + check constraints, indexes). `V2__seed_roles.sql` — USER/ADMIN.
- Domain modules per `docs/ARCHITECTURE.md` §3: `common` (BaseEntity),
  `config` (JPA auditing), `user`, `project`, `review`, `fix`,
  `verification`, `agent`, `audit` — entities, enums, minimal repositories.
  No services, no controllers (except Phase 1 health), no DTOs/MapStruct yet.
- Tests run against real PostgreSQL 16 via a shared Testcontainers
  singleton (`AbstractPersistenceTest`). No H2.

## Run (needs PostgreSQL 16)

```powershell
# Start the database (Phase 0 compose; needs host port 5432 free):
docker compose up -d db
# With the default local credentials from .env.example:
cd backend
.\mvnw.cmd spring-boot:run
```

Datasource is env-configured (`SPRING_DATASOURCE_URL/USERNAME/PASSWORD`,
defaults match the compose `db` service). Flyway migrates on startup;
Hibernate only validates (`ddl-auto: validate`, never create/drop).

Health check: `GET http://localhost:8080/api/v1/health` → `{"status":"UP"}`.

## What Phase 3 contains

- Auth deps only: `spring-boot-starter-security`, `spring-boot-starter-validation`,
  `spring-boot-starter-json`, `io.jsonwebtoken:jjwt` 0.12.6 (single JWT lib).
  Still no MapStruct, OpenAPI, Redis, Kafka, or AI deps.
- `security/` — HS256 `JwtService` (15-min access, 7-day refresh), in-memory
  `RefreshTokenStore` with rotation + reuse detection (ADR-009), `JwtAuthFilter`,
  `SecurityConfig` (public: health + register/login/refresh; ADMIN-only:
  `/users/**`, `/admin/**`; 401 unauthenticated / 403 forbidden), BCrypt(12).
- `user/` — `AuthService` (register/login/refresh/logout, min-12-char +
  breached-password policy), `UserService` (own-profile + 404-on-cross-user
  ownership predicate, ADMIN paged list), `AuthController`, `UserController`,
  validated `*Request`/`*Response` DTOs (entities never serialized).
- `common/AuthRateLimitFilter` — in-memory fixed window on `/api/v1/auth/*`,
  `429 + Retry-After` on exceed. `audit/AuditService` — append-only auth trail.
- Secret: `JWT_SIGNING_KEY` env only (min 256 bits); startup fails fast when
  missing/short. Tests use a fixture key via `@DynamicPropertySource`.

## Test (needs Docker for Testcontainers)

```powershell
cd backend
.\mvnw.cmd -B verify
```

## Environment notes

- `pom.xml` targets Java 21 (`release 21`). Any JDK 21+ builds it; CI
  (Phase 18) will pin JDK 21.
- Run/test JVMs should use a valid IANA timezone (tests pin
  `-Duser.timezone=UTC`): PostgreSQL rejects legacy aliases such as
  `Asia/Calcutta` as a connection startup parameter.
