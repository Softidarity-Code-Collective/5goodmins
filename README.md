# 5 Good Minutes

A Bulgarian-language directory of small activism actions — petitions, emails to
officials, surveys — that users browse by cause and region, subscribe to, and
receive as a daily email digest.

Five minutes is the budget. Every action links out to the platform that hosts it;
this site hosts none of them.

Work in progress.

---

## Stack

- Java 21
- Spring Boot 4.1 (Web MVC, Data JPA, Security, Validation)
- Thymeleaf + htmx for the pages
- PostgreSQL 18 — pinned to match the deployed major version
- Flyway for migrations
- Maven
- JUnit 6 (what Spring Boot 4.1 ships), Mockito, AssertJ, Testcontainers
- Docker / Docker Compose

---

## Current state

Done:

- Spring Boot skeleton, package-by-feature layout
- Docker Compose for everything: PostgreSQL, Mailpit and the application
- GitHub Actions pipeline; deployed to Railway behind the real domain
- Flyway `V1__` migration — the user and action schema
- `AppUser` and `Action` entities with repository tests on a real PostgreSQL
- Registration, login, email verification, and the two transactional emails
- The frontend groundwork: base layout, static assets with a pinned htmx,
  Content-Security-Policy, error pages, the Bulgarian and English labels for
  every enum, and a dev-only style guide at `/dev/styleguide`

Next, in order:

- Templates and the design system (a frontend contributor — see
  `docs/FRONTEND-BRIEF.md`)
- Submitting and listing actions, with filters and search
- Subscriptions and the daily digest email
- Moderation and the admin pages

---

## Running it locally

**Everything in one command — needs Docker, and nothing else:**

```bash
docker compose --profile app up --build
```

That starts PostgreSQL, Mailpit and the application. The first build fetches
the dependency tree and takes a few minutes; later starts reuse it.

**With a local Java toolchain**, which is faster to restart and what you want
if you are changing Java rather than templates:

```bash
./mvnw spring-boot:run
```

Compose is started for you — `spring-boot-docker-compose` reads `compose.yaml`
and brings up PostgreSQL and Mailpit, and stops them again when the
application exits. It leaves the `app` service alone, because that service
sits behind a Compose profile; without that, a local run would start a second
copy of the application in a container and fight it for port 8080.

- Application — http://localhost:8080
- Style guide (dev profile only) — http://localhost:8080/dev/styleguide
- Mailpit (catches outgoing mail) — http://localhost:8025
- PostgreSQL — localhost:5432, database `fivegoodmins`, user/password `app`

Flyway applies the migrations on startup, including
`db/seed/V100__seed_demo_data.sql` — 31 actions in realistic Bulgarian under
two demo authors. That one runs everywhere, deployed site included, because an
aggregator with no content demos as a blank page (ADR-021). The test suite is
the only configuration that leaves it out.

**Neither demo author can log in**: their password hashes are BCrypt over
random bytes that were never recorded, because this file runs in production.
To get an account, register at http://localhost:8080/register and click the
link Mailpit catches — the real flow, and the fastest way to check it still
works. To make that account an admin:

```bash
docker compose exec db psql -U app -d fivegoodmins -c "update app_user set role = 'ADMIN' where email = 'you@example.com';"
```

Re-seeding after editing `V100__` means starting the database over —
`docker compose down -v` — because a versioned migration that has already run
is frozen by its checksum.

The demo catalogue counts down from the moment Flyway applies it: 27 published
actions are live on deploy day, 24 of them are still live six months later,
and all of them have expired by twelve. Refreshing it is a `V101`, due when this drops below ~20:

```bash
docker compose exec db psql -U app -d fivegoodmins -c "select count(*) from action where status = 'PUBLISHED' and ends_at > now();"
```

**Templates, CSS and JavaScript reload on save** — no restart, no rebuild. The
`dev` profile points Thymeleaf and the static resource handler at
`src/main/resources` instead of the compiled copy under `target/`, and the
Compose `app` service bind-mounts the same directory. Changing Java code still
needs a restart.

Tests:

```bash
./mvnw test
```

Every test needs Docker: the repository tests run against a real PostgreSQL via
Testcontainers, because the full-text search this project depends on does not
exist in an in-memory database. One container is started for the whole suite.

---

## Scheduled jobs

`@Scheduled` runs in the application process (`shared/SchedulingConfig`). Today
there is one job: the nightly purge of accounts that never confirmed their
address, at 03:30 UTC. The daily digest joins it.

**Known constraint: this assumes exactly one instance is running.** The
scheduler knows nothing about its peers, so a second replica runs every job a
second time — harmless for the purge, but two digest emails per subscriber. It
is the single thing in this design that blocks horizontal scaling, and it is
accepted knowingly for the MVP. The fix when it matters is ShedLock or a
PostgreSQL advisory lock around each job, or moving the scheduled work to a
separate single-replica deployment.

**That assumption is false during a deploy.** Railway starts the new container,
waits for its health check, and only then drains the old one — so two processes
briefly run the same schedule, on every deploy rather than only when someone
scales up. **Do not deploy within a few minutes of 03:00 or 03:30 UTC.** The
purge tolerates it (running it twice deletes nothing the first pass left); the
daily digest will not, so the lock above stops being optional on the day the
digest ships.

---

## Configuration

`application.yaml` holds local-development defaults. Every value can be
overridden by an environment variable through Spring's relaxed binding, so the
application moves hosts without code changes.

| Variable | Purpose |
|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | Database connection |
| `APP_BASE_URL` | Base URL used in email links |
| `SPRING_PROFILES_ACTIVE` | `dev` or `prod` |

Mail goes to Mailpit in development and to a transactional provider's HTTP API
in production, behind one `EmailSender` interface.

---

## The schema so far

Flyway owns the schema; Hibernate only validates it at startup
(`ddl-auto=validate`).

`V1__` creates:

- **`app_user`** — email, password hash, display name, role, profile fields,
  email verification timestamp, locale. Named `app_user` because `user` is a
  reserved word in PostgreSQL.
- **`action`** — the activism item: title, description, type, external link,
  status, publish date, end date, and a generated `tsvector` column for search.
- **`action_category`** and **`action_region`** — an action can carry up to three
  of each, so they are join tables rather than numbered columns.

Primary keys are `BIGINT GENERATED ALWAYS AS IDENTITY`. Timestamps are
`timestamptz`. Enum values are stored as strings with a `CHECK` constraint rather
than a PostgreSQL enum type or a lookup table.

---

## Domain vocabulary

**Action** — one activism item, with one type, one to three categories, one to
three regions, and a mandatory end date.

**Types (5):** `PETITION`, `LETTER_OR_EMAIL`, `SURVEY`, `SOCIAL_MEDIA`, `OTHER`

**Categories (10):** `NATURE`, `ANIMALS`, `CHILDREN`, `HEALTHCARE`, `DISABILITY`,
`LOCAL_COMMUNITIES`, `SOCIAL`, `HUMAN_RIGHTS`, `LABOUR_RIGHTS`, `POLITICAL`

**Regions (30):** Bulgaria's 28 oblasti plus `NATIONAL` and `INTERNATIONAL`.

**Statuses (4):** `PENDING`, `PUBLISHED`, `REJECTED`, `WITHDRAWN`. Only
`PUBLISHED` actions are publicly visible. An admin moves an action between the
first three in any direction; `WITHDRAWN` is the poster's own reversible choice.

**Roles (3):** `USER`, `POSTER`, `ADMIN`.

Enum constants are English in code and in the database. Everything a user sees is
translated through `messages_bg.properties` and `messages_en.properties`.

---

## Conventions

- Code, comments, commit messages and documentation in English. User-facing
  content is in Bulgarian.
- No hardcoded user-facing strings — every label comes from the message bundles.
  Bulgarian is the default, English is selectable.
- Flyway owns the schema. No automatic DDL generation.
- Entities stay in the service layer; controllers and templates get DTOs.

---

## Roadmap

**Now:** browsing with filters and search, action detail pages, accounts and
email verification, submitting actions with admin approval, subscriptions, the
daily digest, reporting an action, a small admin area, Bulgarian/English
switching, responsive layout.

**Later:** password reset, completion tracking, ranking, an articles section, push
notifications, a mobile app.

---

## License

Source code is licensed under the
[PolyForm Noncommercial License 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0).
See [`LICENSE`](LICENSE).

In short: free to use, change and share for any noncommercial purpose, including
work by NGOs and other nonprofits. **Commercial use requires written permission.**
If you want to use it commercially, open an issue.

The project name and the content published on 5goodmins.com are not covered by
this license.
