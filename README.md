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
- Dockerfile, GitHub Actions pipeline, deployed on Railway behind Cloudflare
- Registration, login and email verification, end to end, with their pages
- Email: one `EmailSender` interface, Mailpit in development and Brevo's HTTP
  API in production; verification, "you already have an account" and welcome
  emails, each with a plain-text twin
- Rate limiting on `/login` and `/register` (see below)
- A placeholder action listing at `/actions` — no filters, search or paging;
  it exists because the welcome email links to it
- The frontend groundwork: base layout, static assets with a pinned htmx,
  Content-Security-Policy, error pages, the Bulgarian and English labels for
  every enum, and a dev-only style guide at `/dev/styleguide`
- Demo seed data (`V100__`), applied everywhere, so the deployed site is not
  an empty page

Next, in order:

- Page 1 proper: filters, search, paging and the htmx swapping, over the
  placeholder listing that is there now
- Templates and the design system (a frontend contributor — see
  `docs/FRONTEND-BRIEF.md`)
- Subscriptions and preferences, then the daily digest email and unsubscribe
- Submitting actions and the admin approval queue

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
and all of them have expired by twelve. Refreshing it is a `V101`, due when
this count drops below ~20:

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

Four values have **no default and no fallback**, and the application refuses to
start without them. That is deliberate in each case: a committed default would
either be a published secret or a plausible wrong value that fails days later
in somebody's inbox rather than at startup.

| Variable | Purpose | Required |
|---|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | Database connection | yes |
| `APP_TOKEN_SECRET` | HMAC key for email-verification and unsubscribe links, 32+ chars | yes |
| `APP_MAIL_FROM` | Sender address, on a domain whose SPF and DKIM name the provider | yes |
| `APP_MAIL_BASE_URL` | Public origin used to build the links emails carry | yes |
| `APP_MAIL_FROM_NAME` | Display name shown instead of the address | no |
| `APP_MAIL_BREVO_API_KEY` | Brevo transactional API key; absent means no mail is sent | no |
| `APP_RATE_LIMIT_REFUSE_REQUESTS` | `false` stops the limiter refusing requests, during an incident | no |
| `SPRING_PROFILES_ACTIVE` | `dev` locally; unset in the container | no |

Rotating `APP_TOKEN_SECRET` invalidates every verification and unsubscribe link
already sitting in a mailbox.

**`app.rate-limit.*` is bound with `ignoreUnknownFields = false`**, so a stale
variable under that prefix fails startup naming itself rather than binding to
nothing. If an environment still sets `APP_RATE_LIMIT_ENABLED` — the name this
flag had before it was narrowed to request refusal — the next deploy will not
start, and will say `The elements [app.rate-limit.enabled] were left unbound`.
Delete the variable. Railway only drains the old container once the new one is
healthy, so a refused startup costs a deploy rather than the site.

Mail goes to Mailpit in development and to Brevo's HTTP API in production,
behind one `EmailSender` interface. With neither configured the application
starts normally and *refuses loudly* on a send rather than discarding silently,
which is the state a deploy with no API key is in.

---

## Rate limiting

Bucket4j on `POST /login` and `POST /register` only, keyed on the client address
that Tomcat's `RemoteIpValve` resolves — never on a forwarded header, which a
request straight to the origin could forge.

| Endpoint | Limit | Keyed on | Refuses |
|---|---|---|---|
| `POST /login` | 20 per 15 minutes | client address | the request |
| `POST /register` | 10 per hour | client address | the request |
| registration email | 3 per 24 hours | the recipient address | **the send** |

The first two are sized for a *shared* address rather than for one person —
carrier-grade NAT puts a whole mobile network behind a handful of them.

**The third one refuses the send and not the request, and that distinction is
the whole of it.** It protects somebody else's inbox rather than this server:
registering over an unconfirmed address emails its owner, so without a cap the
form is a mail cannon. But keyed on an address anyone can type, a cap that
refused the *request* would let three POSTs lock the real owner of that address
out of registering — account lockout, which this project rejects, rebuilt on
the registration form. So when the allowance is spent the email is suppressed
and the request still gets the ordinary "check your inbox" page. The general
rule: a bucket that can refuse a request is only ever keyed on something the
requester cannot choose on somebody else's behalf.

Two consequences of that, both load-bearing:

- **`APP_RATE_LIMIT_REFUSE_REQUESTS=false` does not disarm the mail cap.** It
  cannot: the flag exists to recover from the limiter refusing real users, and
  that is the exact moment you must not also uncap outbound mail. The cap has
  no switch — change the number if you need it looser.
- **A spent token is refunded if the send fails.** The cap counts mail that
  reached an inbox, not attempts to hand it to a provider, so a provider outage
  does not leave an address capped having received nothing. Same rule as the
  digest watermark, which only moves after a successful send.

IPv6 clients are keyed on the `/64` prefix, because a `/128` would give one
household 2^64 buckets. Buckets live in memory, so **every deploy resets
them** — accepted for one instance, and the thing to fix alongside the
scheduler if a second ever exists.

A refusal is `429` with `Retry-After`, through the error dispatch, so it
inherits `templates/error/429.html` like any other error page. The page names
no interval on purpose: see ADR-002.

**If login breaks for everybody, look here first.** The key depends on Tomcat's
`RemoteIpValve` resolving past Cloudflare's edge to the real client. If it ever
stops short, every request shares one bucket and the site gets 20 login
attempts per 15 minutes in total. The refusal log line prints the resolved key,
so a Cloudflare edge address there is the diagnosis;
`APP_RATE_LIMIT_REFUSE_REQUESTS=false` is the stopgap while the proxy
configuration is fixed.

### Taking the key-resolution reading

**This has not been done yet, and it should be, before trusting the limiter in
production.** Nothing else in the application reports what `RemoteIpValve`
resolved, so the refusal log line is the instrument. From a machine whose
public IP you know:

```bash
for i in $(seq 1 21); do curl -s -o /dev/null -X POST https://5goodmins.com/login -d 'username=a&password=b'; done
```

Then read the application log. The 21st request produces:

```
WARN  RateLimitFilter : rate limit refused LOGIN_ATTEMPTS: bucket key [X], remote address [Y], ...
```

Three possible readings. `X` is **your own public address** — correct, the valve
resolved through both hops, nothing to do. `X` is a **Cloudflare address**
(`104.16.0.0/13` and the rest of the list in `application.yaml`) — the walk
stopped at the edge, so every visitor shares one bucket; fix `trusted-proxies`.
`X` is a **private address** — the valve is not reading `X-Forwarded-For` at
all; check `forward-headers-strategy`.

Repeat against the `*.up.railway.app` origin to learn whether Railway's proxy
appends to `X-Forwarded-For` or passes it through — if a forged header reaches
`getRemoteAddr()` there, the fix is at the edge (restrict the origin to
Cloudflare), not in the application.

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
