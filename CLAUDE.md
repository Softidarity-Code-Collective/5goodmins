# 5 Good Minutes — working rules

A Bulgarian-language directory of small activism actions. Users browse by
category and region, subscribe, and receive a daily email digest. Every action
links out to the platform that hosts it; this site hosts none of them.

`docs/ARCHITECTURE.md` is the source of truth for every decision below. It is a
living document: if a rule here looks wrong, say so and we change the ADR — do
not quietly work around it. `docs/INTERVIEW-QA.md` holds the reasoning in
answer form.

**The constraint behind every rule: I have to be able to explain every line in
an interview.** Code I cannot defend is worse than no code. Prefer the boring
option I can justify over the clever one I would have to apologise for.

## Stack — exact versions, because the ecosystem lags

Java 21 · Spring Boot **4.1.1** · Maven · PostgreSQL **18** · Thymeleaf + htmx ·
Flyway · Spring Security **7** · Hibernate **7** · Testcontainers **2.x** ·
JUnit **6**

Most material online targets Boot 3. Treat any example using
`spring-boot-starter-web`, `WebSecurityConfigurerAdapter`, `.and()`-chained
security config, `-Djarmode=layertools`, or `PostgreSQLContainer<?>` as out of
date. Specifics that have already cost time:

- Starters are modular: `spring-boot-starter-webmvc`, `-flyway`, and per-slice
  test starters (`-data-jpa-test`, `-webmvc-test`, `-security-test`). There is no
  umbrella `spring-boot-starter-test`.
- `@DataJpaTest` lives in `org.springframework.boot.data.jpa.test.autoconfigure`.
- `PostgreSQLContainer` is **not generic** in Testcontainers 2.x.
- Bean Validation needs `spring-boot-starter-validation` explicitly.

Prefer official documentation over blog posts for security and persistence.

## Non-negotiable architecture rules

- **Package-by-feature**, one modular monolith: `action/`, `user/`,
  `subscription/`, `digest/`, `moderation/`, `shared/`. Not by layer.
- **Flyway owns the schema.** `ddl-auto=validate`. Never generate DDL from
  entities. `@Column(length = …)`, `unique = true` and `@Index` are DDL-generation
  metadata that `validate` ignores — they are documentation, and the migration
  is the truth. Change both together, by hand.
- **Entities never leave the service layer.** Controllers, templates and the JSON
  API get DTOs. List views use DTO projections, not entities.
- `spring.jpa.open-in-view=false`. Load what the view needs inside the
  transaction; a `LazyInitializationException` in development is the feature.
- **`@Enumerated(EnumType.STRING)`**, never ORDINAL.
- **Cascade deletes live in the database** (`ON DELETE CASCADE` in the migration).
  Never `cascade = CascadeType.REMOVE` or `orphanRemoval` — two systems owning one
  rule can disagree, and JPA would load every child into memory to delete it.
- **`timestamptz` and `Instant` everywhere.** Never `timestamp`, never
  `LocalDateTime`, never `java.util.Date`.
- Every list query carries a tiebreaker: `ORDER BY published_at DESC, id DESC`.
- Pass the current `Instant` (or a `Clock`) into anything time-dependent. No
  `Instant.now()` buried inside an entity or a service method (ADR-019).
- **State changes go through named methods**, never setters. `Action` has no
  `setStatus`, `setPublishedAt`, `setId`, `setCreatedAt` or `setUpdatedAt`, and
  `publish()` writes `published_at` exactly once because the digest watermark
  treats it as immutable. Do not add those setters back.
- **No hardcoded user-facing strings.** Every label, placeholder, validation
  message, `aria-label`, page title and email subject comes from
  `messages_bg.properties` / `messages_en.properties`. From the first template,
  not the second.

## Deliberately rejected — do not suggest these

Microservices · Kafka or any message broker · Redis · Elasticsearch · CQRS ·
event sourcing · an SPA framework or a JavaScript build pipeline · WebFlux ·
JWT for the browser client · a service worker · native Postgres `ENUM` types ·
lookup tables for regions, categories or statuses · soft deletes or tombstones ·
account lockout (rate limiting instead) · an `Organization` entity · a
`HIDDEN` action status · hashtags · bilingual content.

Each was considered and rejected with a reason in `docs/ARCHITECTURE.md`. If one
genuinely becomes necessary, the answer is a new ADR, not a quiet dependency.

## Scope — MVP only

Deferred to v2 and **not** to be built, designed for, or snuck in: ranking or
trending, AI duplicate detection, "I did it" completion tracking, an articles
hub, an activism calendar, push notifications, a mobile app, avatars,
poster-facing stats, a two-facet taxonomy.

If I ask for one of these, push back and name it as v2 before writing anything.

## How to work

- **Small diffs over rewrites** once a file exists.
- **Test as you go.** Unit tests with Mockito for service logic; `@DataJpaTest`
  with Testcontainers for repository queries; `@SpringBootTest` + MockMvc for a
  handful of end-to-end paths. Test the logic that would embarrass me if it
  broke, not a coverage percentage.
- **Verification gate: run `./mvnw verify` before claiming anything works, and
  quote the test count.** "Should work" is not a result. A green suite that
  never executed the code under discussion is not evidence — say so instead.
- Be brutally honest. Challenge over-engineering *and* under-engineering. Name
  what you rejected and why. No flattery.
- Flag the interview-relevant parts as they come up, and quiz me when a decision
  has a stock question attached to it.

## Conventions

- Code, comments, commit messages, Javadoc and documentation in **English**.
  User-facing content is in **Bulgarian**.
- Database: singular `snake_case`, `app_user` not `user`, every constraint
  explicitly named (`pk_`, `fk_`, `uq_`, `ck_`, `idx_`) because the application
  tells violations apart by name.
- Enum constants are English identifiers in code and in the database
  (`STARA_ZAGORA`, `HUMAN_RIGHTS`); display text lives in the message bundles.
- **Never edit an applied Flyway migration.** `V1__` is editable only while it
  has run nowhere but my laptop; once Railway has it, changes are a new file.
