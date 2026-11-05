# Architecture Decisions — 5 Good Minutes (Activism Action Platform)

**Revision 10** · 2026-10-15 · Status: living document
Supersedes Rev 6. Flyway `V1__` is now written, applied against PostgreSQL 18
and covered by repository tests; it is the source of truth for exact DDL — this
document records the decisions and the reasons. Rev 7 corrects the places where
Rev 6 disagreed with what the code actually does.

Companion file: `docs/INTERVIEW-QA.md` — the interview answers these decisions
produce.

---

## 0. Honest read on Revision 1

Rev 1 was a good product plan and a decent stack list. It was **not** an architecture document. It named technologies but left the decisions that actually cause pain undecided:

- Where do uploaded images physically live? (The answer in Rev 1 implies local disk. That silently breaks on every host you named.)
- How does email actually leave the server? (Render's free tier blocks the SMTP ports outright.)
- What language is the content in? (This determines whether Postgres full-text search works at all for you.)
- What is a "region"? (An action can be national or international. A plain FK to a region row can't express that.)
- What's the difference between a `User` with role `POSTER` and an `Organization`? Rev 1 listed both. That's a duplicated concept.

Rev 1 also included a 4-week timeline. That's optimistic for someone rebuilding after three years away while job-hunting in parallel. Revised below.

Everything Rev 1 got right is kept and marked **Accepted**. The rest is marked **Revised**, **Superseded**, or **Open**.

---

## 1. Decision summary

| # | Decision | Status |
|---|---|---|
| — | Schema conventions: identity PKs, `timestamptz`, `app_user`, named constraints | New (Rev 4) |
| 1 | Server-rendered Thymeleaf + htmx, **plus a thin read-only JSON API** | Revised |
| 2 | Session-based auth via Spring Security; mandatory email verification; rate limiting, no lockout | Accepted, extended; limits set and built (Rev 21) |
| 3 | Single deployable modular monolith, package-by-feature | Accepted, made explicit |
| 4 | Hosting: **Railway Hobby (~$5/mo)** + Cloudflare, domain `5goodmins.com` | Resolved |
| 5 | Email via an HTTP-API provider; digest keyed on `published_at` with a lag window; welcome email | Revised; provider **Brevo** and welcome email built (Rev 21) |
| 6 | Images in Postgres `bytea` behind an `ImageStorage` interface — **never local disk** | Resolved |
| 7 | Postgres full-text search with the `simple` config + `pg_trgm` (no `unaccent`) | Revised |
| 8 | Bulgarian-first UI (`bg`/`en` via `messages.properties`); content single-language | Resolved |
| 9 | Region: enum of 28 oblasti + `NATIONAL` + `INTERNATIONAL`; one to three per action | Revised |
| 10 | No `Organization` entity; `User` + role; the role is the single source of truth | Revised |
| 11 | Duplicate detection: unique index on normalized external URL; the URL itself is optional | Revised (Rev 6) |
| 12 | Scheduled jobs assume a single instance; no ShedLock in MVP | New |
| 13 | `open-in-view=false`, DTO projections for list views | New |
| 14 | JUnit 5 + Mockito + Testcontainers, singleton container | Accepted, specified |
| 15 | Account deletion: hard delete, DB-owned `ON DELETE CASCADE`, 404 afterwards | Resolved |
| 16 | Action lifecycle, poster approval, abuse controls | Revised (Rev 6) |
| 17 | Categories and action types | New (Rev 4) |
| 18 | Build and runtime: Maven, Spring Boot 4.1.x, Java 21, YAML config | New (Rev 5) |
| 19 | Time enters through one injected `Clock`; no `Instant.now()` in place | New (Rev 13) |
| 5b | Dev mail: Mailpit behind an `EmailSender` interface; PostgreSQL pinned to 18 | Resolved (Rev 9) |

---

## 2. Schema conventions

These apply to every table, so they don't belong inside any one ADR.

**Primary keys.** `BIGINT GENERATED ALWAYS AS IDENTITY`.

- Not `BIGSERIAL`: that is the pre-Postgres-10 shorthand with a separately owned sequence. Identity is the SQL-standard replacement; the column owns its sequence.
- Not UUID: 16 bytes in every PK and FK index, random insert positions causing B-tree page splits, and no offline or distributed writers to justify it. Published actions are public anyway; non-public ones are protected by authorization checks, not by unguessable ids.
- `ALWAYS` rejects hand-supplied ids, so a manual insert can never collide with the sequence later.
- **Known consequence:** Hibernate cannot batch inserts with identity generation, because the id exists only after the INSERT runs. Irrelevant here — bulk inserts happen only in Flyway SQL. Seed migrations therefore reference rows by natural-key subselect, never by hardcoded id.
- `BIGINT` rather than `INT` because converting later means rewriting the whole table.

**Timestamps.** `timestamptz` everywhere, never `timestamp`.

- Java side is `Instant`; `spring.jpa.properties.hibernate.jdbc.time_zone=UTC` so the JVM zone never matters. Convert to `ZonedDateTime` in Europe/Sofia only when rendering.
- `created_at` on every table that has its own lifecycle. **Pure join tables owned
  by a parent are exempt** (`action_category`, `action_region`): their rows are
  part of the parent's state, they are created and destroyed with it, and
  `@ElementCollection` has nowhere natural to map the column — it would exist and
  nothing would ever read it. `updated_at` only on tables whose rows are edited.
- Both carry `NOT NULL DEFAULT now()` as a safety net for SQL-level writes; in normal operation Hibernate's `@CreationTimestamp` / `@UpdateTimestamp` set them.
- **No `updated_at` triggers.** Rejected: the behaviour would be invisible to a Java reader, and stale audit metadata after a manual fix harms nothing. Contrast with cascade deletes (ADR-015), which *are* in the database because breaking them corrupts data.
- **Known gap:** `@UpdateTimestamp` does not fire on JPQL bulk updates (`@Modifying @Query`). Single-row state changes load the entity and set the field instead.
- Tests truncate `Instant` to microseconds (Postgres precision) or inject a fixed `Clock`.

**Naming.**

- Singular, snake_case. Consistency matters more than the choice.
- The user table is `app_user`: `user` is reserved in Postgres, and an unquoted reference silently resolves to the current role instead of failing.
- Every constraint is explicitly named — `pk_`, `fk_`, `uq_`, `ck_`, `idx_` — because the application distinguishes constraint violations by name (ADR-011).

**Constraints vs Bean Validation.**

- The database enforces invariants that must hold whatever code runs — the app, the seed migration, a script, `psql`: `NOT NULL`, `UNIQUE`, foreign keys, and `CHECK` for allowed enum values and length caps.
- Bean Validation produces friendly localized messages before the database is reached.
- The duplication is accepted; the Java side keeps its limits in one constants class.
- Rules that depend on other rows, on the current time, or on product policy stay in the service layer. A `CHECK` can only see the row being written.
- Enums are `varchar` + `CHECK`, never a native Postgres `ENUM` type (painful to alter, extra Hibernate mapping).
- Bounded strings are `varchar(n)`; long free text is `text` with a `CHECK (char_length(...) <= n)`.
- Emails are lowercased in the application and verified by `CHECK (email = lower(email))` plus a plain `UNIQUE`. Rejected: `citext` (extension, awkward mapping) and a unique index on `lower(email)` (every query must remember `lower()` or silently skip the index).

**Indexes.**

- Postgres does not index foreign keys automatically. Every FK gets an explicit index.
- Every list query carries a tiebreaker — `ORDER BY published_at DESC, id DESC` — because `now()` is transaction start time and rows written in one transaction share a timestamp. Without it, pagination can repeat or skip rows.

**Derived values are not stored.** If a value can be computed from the row at query time (expiry from `ends_at`), it is computed, not kept in sync by a job.

---

## 3. The decisions

### ADR-001 — Thymeleaf + htmx, with a thin read-only JSON API

**Status:** Revised

**Context.** Rev 1 correctly rejected an SPA. But it also produced an app with zero HTTP APIs, and a large share of Java job descriptions test REST API design: resource naming, status codes, DTO mapping, pagination, versioning. A pure MVC app gives you nothing to point at when an interviewer asks "show me an API you designed."

**Decision.** Keep Thymeleaf + htmx for all user-facing pages. Additionally expose a small read-only API: `GET /api/v1/actions` (paginated, filterable) and `GET /api/v1/actions/{id}`. DTOs only — never expose entities. Public, no auth, rate-limited.

**Why this is nearly free.** Your service layer already returns DTOs for the templates. The API is a second controller over the same service. Half a day of work, plus a `springdoc-openapi` dependency that gives you Swagger UI for the demo.

**Consequences.** You now have two presentation layers over one service layer, which is exactly the argument for keeping business logic out of controllers — and it makes that argument demonstrable rather than theoretical.

**Mobile.** The site must be fully responsive — most visitors will arrive from a phone via a shared link or a digest email. Progressive-web-app scope is open; see Section 5.

**Interview point.** "Why not build the whole thing as an API + SPA?" → Server rendering for an SEO-relevant, content-heavy, low-interactivity site; htmx for the handful of partial updates; an API for future mobile clients and third parties. You chose per-use-case, not by fashion.

---

### ADR-002 — Session-based authentication

**Status:** Accepted (supersedes Rev 1's JWT), extended in Rev 4

Form login, `SecurityFilterChain`, BCrypt via `DelegatingPasswordEncoder`, roles as `ROLE_USER` / `ROLE_POSTER` / `ROLE_ADMIN` with a `RoleHierarchy` (ADMIN > POSTER > USER). One role per user, stored in a single `role` column. Session in memory for MVP.

**Specifics:**

- **CSRF with htmx.** Spring Security's CSRF protection is on by default and will silently 403 your htmx POSTs. Put the token in a `<meta>` tag and set `hx-headers` on `<body>` once. Budget an hour for this; everyone loses one to it.
- **Session storage.** In-memory means every deploy logs everyone out and horizontal scaling breaks. For MVP that is *correct* — accept it and document it. Spring Session + JDBC is a two-line change later.
- **Email verification is mandatory.** Registration creates an account that cannot log in and receives no email until `email_verified_at` is set. Implemented as a signed, expiring token in the link (HMAC over user id and expiry) — no token table. Same mechanism as the unsubscribe link in ADR-005: built once, used twice. Unverified accounts are purged after seven days, and re-registration over an unverified row is allowed so nobody can squat another person's address.
  - Why: the address is the digest destination. Unverified or mistyped addresses produce bounces and spam complaints, which damage sender reputation for every future email. It also raises the cost of fake accounts.
  - It is **not** a DDoS defence — a flood of traffic never completes a registration. DDoS is Cloudflare's job.
  - **No resend-verification form in the MVP** (cut in Rev 14; Rev 8 required one, with a per-address and per-IP rate limit, a cooldown and a daily cap, because a form that must answer identically for every address will also accept an address belonging to someone else, repeatedly, unless it is capped). What removed the need for it is that **registering again over an unverified row already is a resend**: it replaces the row and issues a fresh token, and `/register` is rate-limited anyway. So the form would be a second entry point to the same operation, needing a second set of abuse controls, in order to save the user retyping a password and a display name. **The cost, stated plainly:** someone whose first email never arrives has to fill the registration form again rather than press one button, and the password they type the second time is the one the account keeps — a password manager that saved the first will be out of date. That is friction for an uncommon case, against a whole mechanism and its abuse surface. The form goes in if real users actually hit it.
  - **The cost that actually mattered was not the effort, it was the silence** (Rev 16). The page said "check your inbox", nothing arrived, and nothing told the user what to do next — so the recovery this ADR leans on was real but invisible, which makes it no recovery at all. Fixed where it belongs, in the copy: page 8 of the frontend brief now has to carry "check spam, or register again with the same address and we will send a fresh link", with the strings already in both bundles. Zero code. It is worth noticing that the decision to cut the resend form was only *sound* because of a page that did not exist yet, and nothing was tracking that.
  - **When this is actually reached,** because it is narrower than it sounds: only when the send itself fails — the provider is down or refuses. The queue-full case needs the provider down long enough for fifty registrations to pile up, which at this traffic does not happen. The ordinary reasons an email does not arrive are the spam folder and a mistyped address, and for a typo, registering again with the correct address is not a workaround — it is the right fix.
  - **Next step if it is ever not enough: resend on a proved login attempt, not a form.** The user enters the correct password, and the answer is "your address is not confirmed yet, we have sent a new link". No new endpoint, no new abuse surface — it costs a password to trigger, and `/login` is rate-limited anyway. **The trap, and the reason this is not free:** the unverified gate currently lives in `AppUserDetailsService.loadUserByUsername`, which runs *before* any password comparison, precisely so that an unverified account is indistinguishable from an unknown one. Resending requires knowing the password was right, so the gate has to move to `AbstractUserDetailsAuthenticationProvider.setPostAuthenticationChecks(...)` — verified against Spring Security 7.1.1, where `postAuthenticationChecks.check(user)` runs after `additionalAuthenticationChecks`, which is the password. Then a wrong password on an unverified account still answers "wrong credentials", and only someone who has proved they own the account learns anything. Done the naive way — mapping unverified onto `isEnabled() == false` — the pre-authentication checks run first and the login form becomes the enumeration oracle `AppUserDetailsService` exists to close. Build it with the login page, not before.
  - **And the proper answer at scale, which is not this:** a transactional outbox. The pending email is written to a table in the same transaction as the user, a job sends it and retries with backoff, and the row is marked done — so a provider outage is invisible to the user and survives a restart. It costs a table, a job, a retry policy and a cleanup policy, and ADR-005 rejected a retry table for exactly that reason at this volume. It is the right thing to *name* when asked what comes next, and the wrong thing to build now.
  - **The token signs its purpose, not only the user and the expiry.** Written in Rev 10 as `shared/token/SignedTokenService`. One key signs every link the application emails, so without the purpose in the signed material an unsubscribe token would verify as an email-verification token for the same account — same id, same key, same format. The purpose is supplied by the endpoint doing the verifying rather than read out of the token: a purpose read from the token is a field the holder controls before it is a field that was checked.
  - **The lifetime belongs to the purpose.** Verification is seven days, to match the purge of unverified rows; unsubscribe is ten years, because a digest found in a two-year-old mailbox must still unsubscribe and that is the ADR-005 legal requirement rather than a convenience. A single configurable TTL would have to be the longer of the two, which is the wrong answer for verification.
  - **Signature first, expiry second.** The expiry is only worth reading once it has been shown to be the one that was signed, and that ordering is also what makes "expired" a trustworthy answer: it is returned only for a token this application really issued. Verification returns three outcomes, not an `Optional` — valid, expired, invalid — because each wants different handling: an expired token still carries an authentic user id, which is what lets the endpoint answer "your address is already confirmed" rather than "that link is broken" (there is no resend form to offer it to; that was cut in Rev 14), and an invalid one is the only case worth a log line.
  - **`app.token.secret` is required, with no default, and startup fails without it.** Same reasoning as the absent datasource default: a committed key would be a published key, so every link in production would be forgeable by anyone who can read the repository, silently. Generating a random key at startup is the other wrong answer — every restart would kill every link already in a mailbox, and two instances would reject each other's tokens. Rotating the key invalidates outstanding links, which is asserted in the tests rather than assumed.
  - **Password reset cannot share this as it stands.** Verification and unsubscribe are both idempotent, which is the specific reason a stateless token with no revocation is acceptable for them — replay changes nothing either time. A reset link is not: left valid for its full lifetime after the password has changed, it lets whoever else can read that mailbox change the password back. The fix needs no table and no "used" flag — sign something that changes when the password does, such as a digest of the current `password_hash`, so spending the token invalidates it as a side effect. That is one more field in the signed payload, and it goes in with the feature.
  - **The verification email is sent after the transaction commits, asynchronously, and both registration outcomes send one.** Built in Rev 16, as ADR-005's "Sending is an after-commit event" specified before the sender existed. Registration's timing-oracle defence depends on it: the two outcomes pay the same BCrypt cost, and an HTTP call to the mail provider on only one of them would reopen the gap several times wider than it was. `register` therefore publishes a `RegistrationEmailEvent` on **both** paths and calls no sender itself; that both paths publish is asserted in `RegistrationServiceTest`, and that nothing is sent before the commit or after a rollback is asserted in `RegistrationEmailListenerTest` against a real, committed transaction.
- **Rate limiting, not account lockout.** Bucket4j on `/login` and `/register` only. Don't build a general-purpose rate limiter.
  - Lockout after N failures is rejected: it turns the login form into a denial-of-service weapon against anyone whose email is known, and it needs a counter column, a reset path, an unlock email and a duration policy. No `failed_login_attempts` column.
  - If credential stuffing ever becomes real: exponential backoff per IP and per account, then Cloudflare Turnstile. Still not permanent lockout.
  - **The bucket key is `request.getRemoteAddr()`, with Cloudflare's published ranges added to `server.tomcat.remoteip.trusted-proxies`.** Settled in Rev 17; it was blocking the work and was recorded nowhere. Two parts, and the second is the one that matters:
    - **Why `trusted-proxies` and not `internal-proxies`.** `RemoteIpValve` walks `X-Forwarded-For` right to left, skipping entries that match either list, and stops at the first entry that matches neither — that entry becomes the remote address. The chain reaching Tomcat is `…, realClient, cloudflareEdge`, because Railway's proxy appends the peer it received the connection from. Cloudflare's edge is a *public* address matching neither list by default, so the walk stops there and **every request appears to come from a handful of Cloudflare IPs** — which would put the whole country in one bucket. Adding Cloudflare's ranges to `trusted-proxies` makes the walk skip that hop and land on the real client. It must be `trusted-proxies` rather than `internal-proxies` because Spring Boot gives `internal-proxies` a default value — the private ranges, including the one Railway's own proxy is in — and setting it **replaces** that default rather than extending it, so the obvious-looking fix breaks the hop it depends on. `trusted-proxies` is empty by default and adds.
    - **Why `getRemoteAddr()` and not `CF-Connecting-IP`,** which was the standing recommendation. `CF-Connecting-IP` is a single value Cloudflare overwrites, so it is both simpler and unforgeable *when the request really came through Cloudflare* — and that is the whole problem: a request straight to the `*.up.railway.app` origin carries whatever `CF-Connecting-IP` the client wrote, and every forged value is a fresh bucket, so the limit is bypassed by changing one header. Gating on "is the peer Cloudflare" is exactly what the valve already does for `X-Forwarded-For`, and after it has run the original peer is no longer available to read. `getRemoteAddr()` is sound on both paths: proxied, the valve resolves the client through the trusted hop; direct to the origin, Railway appends the real client to the right of anything forged and the right-to-left walk stops there. One number, one mechanism, and the rate limiter never parses a header.
    - **The assumption to verify, with one curl:** that Railway's proxy really does append rather than pass `X-Forwarded-For` through unchanged. If it passes it through, *both* keys are forgeable on the direct-origin path and the fix is at the edge — restrict the origin to Cloudflare — not in the application. Verify against `*.up.railway.app`, not `5goodmins.com`, so Cloudflare cannot mask the answer. This is the same unverified hop that `forward-headers-strategy: native` in application.yaml already depends on; one request answers both.
    - **Correction to that plan, found while trying to run it (Rev 18): one curl cannot settle the `X-Forwarded-For` half, because nothing reports the answer back.** The HSTS half is self-reporting — the header's presence on a response from the origin *is* the result. The forwarding half is not: the application has no endpoint that echoes `getRemoteAddr()`, Tomcat 11's `RemoteIpValve` logs nothing about the address it resolved (only `invalidRemoteAddress` and the two host/port warnings; the resolved value goes into request attributes for `AccessLogValve`, which is off), and the access log would land in the container filesystem rather than Railway's log stream. So the three real options are: read it back from the rate limiter's own log line, which has to compute the key anyway — the measurement comes free with the code this verification was meant to gate, which inverts the order this bullet assumed; a throwaway endpoint that echoes client IPs, rejected on sight; or Railway's documentation, which is reasoning rather than measurement.
    - **And the question may not need an answer.** Both branches of it only matter on the direct-origin path, which is also the path that bypasses Cloudflare's WAF and DDoS protection entirely — so detaching the `*.up.railway.app` domain in Railway, leaving the service reachable only as `5goodmins.com`, is worth doing whether the proxy appends or passes through. Do that and the chain always arrives through Cloudflare, the valve's trusted hop resolves the real client, and the forgeable case stops existing rather than being reasoned about. That is a dashboard change and no code.
    - **Known failure modes.** Cloudflare's ranges change, rarely and with notice; a stale list means the walk stops at the edge again and the limit becomes global rather than absent. **Rev 22 corrects the gloss that used to follow that sentence.** Calling it "over-restrictive, which is the right direction for a limiter to break in" was wrong: global means twenty login attempts per fifteen minutes *for the entire site*, which is a site-wide login outage whose symptom is "login is broken for everybody". It is still preferable to the limit being absent, and it is diagnosable in one line — `RateLimitFilter` logs the resolved key on every refusal, and a Cloudflare edge address there *is* the diagnosis — but the real mitigation is at the edge rather than here: restrict the origin to Cloudflare so the chain is always the one this configuration describes. And an IPv6 client gets a `/128`, so a determined attacker with a `/64` has 2^64 buckets: key IPv6 on the `/64` prefix, which is the unit an ISP actually hands out.
    - **`internal-proxies` is deliberately left at Boot's default, and that was checked rather than assumed.** Raised in review against [spring-boot#34119](https://github.com/spring-projects/spring-boot/issues/34119), which reported an older regex default missing `100.64.0.0/10` — the carrier-grade NAT range container platforms use. Read out of `spring-boot-tomcat` 4.1.1's own configuration metadata, the default is `192.168.0.0/16, 172.16.0.0/12, 169.254.0.0/16, fc00::/7, 10.0.0.0/8, 100.64.0.0/10, 127.0.0.0/8, fe80::/10, ::1/128` — a CIDR list, with that range in it. The issue is fixed in this version, Railway's hop is covered, and setting the property explicitly could only drop something.
    - **Why CIDR is the right form, corrected.** The reason first written down here — "Boot's own default is a CIDR list, which is the proof it goes through the same setter" — is circular and was removed in review. The real reason is documented: Tomcat 11 describes both attributes as "either a comma separated list of CIDR blocks or a single regular expression", and adds that "Support for configuration via regular expressions is deprecated and will be removed in Tomcat 12." CIDR is the form with a future; the IPv4-only regex most writing on this property still describes is the one on a clock.
    - `/register` gets a per-address bucket as well as a per-IP one, because `register` emails the owner of an unverified address once per request until something caps it. **The two live in different places, and Rev 22 is where that was learned.** The per-IP bucket refuses the request, in the filter. The per-address bucket refuses the *send*, in `RegistrationEmailListener` — see "the correction" below, which is the most important line in this ADR.
    - **The correction (Rev 22, from review): a bucket keyed on something a stranger can name for somebody else must never refuse that person's request.** As first built, the per-address bucket was spent in the filter, keyed on the submitted email. Three POSTs naming `victim@example.com` therefore locked the real owner of that address out of registering for twenty-four hours, and they got a bare 429. No session and no CSRF token was needed, because the filter sits in front of `CsrfFilter`. **That is account lockout, rebuilt on the registration form** — the thing this same ADR rejects three bullets up, in the same words: "it turns the login form into a denial-of-service weapon against anyone whose email is known." Attacker cost was their own 10/hour per-IP bucket, so three victims an hour per address, and an IPv6 `/64` is a fresh bucket.
      - **The fix follows from the bucket's own stated job**, which is the part worth remembering: it protects a stranger's inbox, not this server. So it belongs on the send. When the allowance is spent the email is suppressed and the request still returns the neutral "check your inbox" page — the inbox is bounded exactly as before, its owner is refused nothing, and `/register`'s single response for every address survives. A 429 had broken that too: it was a third answer the form could give, keyed on request history.
      - **What it still costs, because it is not zero.** A suppressed send is an email that never arrives, and page 8's recovery — register again — will not produce one either until the window passes. An attacker can therefore still deny a targeted address its *email* for a day. What they can no longer do is deny it the *account*. That is a strictly smaller weapon, and it is the smallest one on offer: no cap on mail to an address can both bound the mail and promise the next message gets through.
      - **The welcome email is exempt**, because it cannot be a cannon — reaching it needs a valid signed token for that account, which only the mailbox's reader has. Capping it would mean a user who confirms after an attacker spent the allowance is welcomed by silence.
      - **The rule left in the filter is narrow and worth stating:** a bucket that can refuse a request may only be keyed on something the requester cannot choose on somebody else's behalf. That leaves the client address. Two things fell out of it — the filter now reads no request parameter, so it never parses a request body and the multipart caveat is gone; and `POST /register` has exactly one response it can produce there.
      - **Asserted, not described.** `RateLimitWebTest` fires five registrations for one address from five clients against a cap of one and requires the ordinary redirect every time; the per-recipient cap has its own file.
    - **The numbers, settled in Rev 21 because ADR-002 had specified the key and the mechanism and no requests-per-window at all.** `/login`: **20 attempts per 15 minutes per client address**. `/register`: **10 per hour per client address** (refusing the request), and **3 per 24 hours per email address** (refusing the send). Defaults live in `shared/ratelimit/RateLimitProperties` and are asserted in a test, for the same reason the HSTS directive set is asserted rather than the presence of `max-age` — these are policy with a rationale, and a change to one should fail a build and be argued for.
      - **Why 20 and not 5 on `/login`.** A person who knows their password needs one attempt and a person who has forgotten it needs about five, so the number is not sized for one user — it is sized for a *shared address*, which in Bulgaria is most mobile traffic behind carrier-grade NAT. Twenty leaves room for four people each fumbling. For an attacker it caps a run at 80 guesses an hour from one address, which turns a thousand-password list into a half-day job and makes a stuffing list of thousands pointless. It is deliberately not tight enough to stop a slow distributed run; that is what Turnstile is named for above, and claiming otherwise would be the kind of thing a limiter gets credit for and does not do.
      - **Why the per-address registration limit is the loose one and the per-email limit is the tight one.** They defend different things. Ten registrations an hour from one IP exists only for a classroom or an office signing up after a talk — registration is a once-ever act, so any number above one is there for shared addresses. Three a day per *email* address is the one that matters, and it protects somebody else's inbox rather than this server: `register` mails the owner of an unverified address once per request, so without it the form is a mail cannon pointed at a stranger. Three is enough for a person who really is registering, failing and registering again, and far too few to be a nuisance. **That it protects an inbox rather than this server is also why it cannot refuse a request** — see the correction below.
      - **`refuse-requests` is an incident switch, and it is named for exactly what it switches off** (renamed from `enabled` in Rev 23, from review). **The rename needed one more thing to be safe, found in review of that review:** Spring ignores unknown configuration properties, so any environment still setting `APP_RATE_LIMIT_ENABLED` would bind it to nothing, say nothing, and bring the limiter back on at the next deploy. `app.rate-limit` is therefore bound with `ignoreUnknownFields = false`, which turns that into a refused startup naming the property — the same answer as the absent datasource and the absent signing key, for the same reason. Railway drains the old container only once the new one is healthy, so being wrong here costs a deploy rather than the site. A stale Cloudflare range list collapsing every request onto one bucket is the documented way for this to start refusing real users, and when it does the fix should be one environment variable and a restart rather than a revert and a deploy. It is also what the test suite sets: `RegistrationWebTest` alone posts the registration form more than ten times.
      - **The switch must not reach the per-recipient mail cap, and as `enabled` it did.** The flag's whole purpose is recovering from the limiter refusing real users — which only the two address-keyed buckets can do, because only those are keyed on something identifying the person making the request. The mail cap is keyed on the *recipient* and cannot misfire against a client. So the one situation the switch exists for was also the situation in which flipping it turned `/register` back into a mail cannon, with the per-address bucket going off at the same moment and nothing left bounding it at all. The mail cap now has no switch: to relax it you change the number. The suite raises that number rather than disarming it, and the shipped value is exercised against the real cap in a unit test. `RateLimitProperties` has a test asserting its own component list, so adding a flag that disarms the cap means changing that test first.
      - **The cap commits on success, never on attempt.** A token is spent before the send and refunded if the send throws, because the cap counts mail that reached an inbox rather than attempts to hand it to a provider — without the refund, a Brevo outage plus three retries by a user who can see no email leaves that address capped for a day having received nothing, which is the limiter punishing the person it exists to protect. Same rule as ADR-005's digest watermark, which "moves only after a successful send"; the two are instances of one idea and it is worth being able to name both. Consume-then-refund rather than peek-then-consume, because the mail pool is single-threaded *today* and ADR-005 says both pool numbers move together if the digest ever needs concurrency — on that day a peek becomes a race that over-sends while a consume stays atomic. The refund mirrors the welcome-email exemption exactly, or a failed welcome would mint a free token. All three of those properties are pinned by tests that were each checked against a mutation of the code they cover.
      - **A suppressed send logs at INFO, not WARN.** It is what the cap working looks like, it is unbounded under an attack because every request past the third produces one, and the signal an operator actually wants is the per-address refusal in the filter — which is a WARN and names the client. The first version logged it at WARN under a comment claiming it was "not logged as a failure", which is the sort of contradiction a reviewer reads back to you.
    - **Greedy refill, not intervally**, and the two are not interchangeable even though they permit the same sustained rate. Intervally hands the whole allowance back on a boundary, so a user who mistypes into an exhausted bucket waits most of fifteen minutes; greedy drips one token every forty-five seconds, so they wait forty-five. An attacker gains nothing from the difference, because what bounds a guessing run is the sustained rate and that is identical. Asserted as behaviour — one token back per slice of the window — rather than as a builder call.
    - **In-memory buckets, in Caffeine rather than a `ConcurrentHashMap`, and the eviction rule is derived rather than chosen.** the core module only (`bucket4j_jdk17-core` — the artifacts are split by target JDK from 8.11, and the obvious-looking `bucket4j-core` is a dead line that stops at 8.10.1): every other Bucket4j module exists to share buckets across a cluster through Hazelcast, Redis or JCache, and ADR-012 already states this runs as exactly one instance — a distributed bucket store would encode that assumption a second time, in a dependency this document rejected. The named consequence is that **every deploy resets every bucket**, which at one deploy a day against a 15-minute window is a rounding error and stops being one on the day a second instance exists, where the limiter and the scheduler then need the same fix. A plain map keyed on a client address is an out-of-memory error an attacker can schedule — one entry per address ever seen, never removed, and an IPv6 `/64` hands out 2^64 of them — so each scope's store is bounded two ways: `expireAfterAccess` equal to **the scope's own window**, because after a full window with no request a greedy bucket is back at full capacity and the entry holds no information; and a maximum size, whose cost is real (evicting a half-spent bucket resets it) and is made unattractive by Caffeine's W-TinyLFU admission policy, which keeps entries by frequency — so under an attack the entry the attacker wants evicted is the one the cache most wants to keep.
    - **The limiter's clock is the injected `Clock`** (ADR-019), handed to Bucket4j as a `TimeMeter` rather than letting it read `System.nanoTime()`. Not tidiness: a rate limiter *is* a stopwatch, and without this a test of "twenty attempts, then a refusal, then sixteen minutes later another twenty" would have to either wait sixteen minutes or assert nothing about the window.
    - **The filter sits before `CsrfFilter`, not behind it, and the cost is stated on both sides.** Before, a spray with no CSRF token is refused by the limiter and never reaches a session lookup, which is what a limiter is for — and an attacker is one GET away from a valid token anyway, so ordering it after buys no protection. The cost is that a client with an expired session, resubmitting a form it cannot succeed with, spends its own allowance on 403s; that is the less surprising of the two, because those really are failed attempts from that address. It is also constructed in `SecurityConfig` rather than declared as a bean: Spring Boot auto-registers a `Filter` bean with the servlet container *as well as* adding it to the security chain, so it would run twice and spend two tokens per request unless a second `FilterRegistrationBean` existed purely to switch the first registration off.
    - **A refusal is `429` through the error dispatch**, with `Retry-After` set first, so the day page 19 of the frontend brief lands this inherits a real page in the site layout along with 404, 403 and 500. Writing a body here instead would mean either a user-facing string in Java, which ADR-008 forbids, or a blank page, which tells a human nothing. Whether the header survives a real Tomcat's error dispatch is **measured, not assumed** — MockMvc's `MockHttpServletResponse` keeps it either way, so there is a `@SpringBootTest` on a real port that posts until it is refused and reads the header back off the socket.
    - **The bucket key is masked and sanitised before it is a key, and both halves close a bypass.** An IPv6 client is keyed on its `/64` and never its `/128`, because the `/128` is one address out of the block an ISP hands a single household — key on the whole thing and an attacker has 2^64 buckets for free. And a remote address that cannot be parsed goes to **one shared key**, not its own: `RemoteIpValve` does not validate the entry it takes out of `X-Forwarded-For` (its `invalidRemoteAddress` message is about reverse-resolving the host, not about rejecting the address), so a forged header can put arbitrary text in `getRemoteAddr()`, and a key per forgery is exactly the bypass. Nothing in that path may perform a DNS lookup — one lookup per login attempt against a name an attacker chooses is a far better weapon than the one being defended against — which is why the dotted-quad form is parsed by hand: `InetAddress.getByName` *resolves* numeric-looking hostnames, so `1.2.3.4.5` is a lookup rather than a parse failure. `InetAddress.ofLiteral` says all of this in one call and arrives in Java 22.
    - **And the `X-Forwarded-For` measurement Rev 18 could not run now exists as a by-product.** The limiter logs the resolved bucket key next to the raw remote address on every refusal, which is the only thing in the application that reports what the valve resolved — so twenty-one posts to `/login` on the deployed site answer whether Railway's proxy appends or passes through, with no echo endpoint to build and then remove. The cost is one WARN per refused request, which under a sustained attack is a log line per request; the fix, if that ever matters, is one line per key per window behind a second cache, and it is not worth the state today. Reading it is still an open task: **the measurement is in place, the reading has not been taken.** Note what the line does *not* carry — for the per-email bucket it logs the scope and the client address and never the address itself, because a log file that answers the question the login page refuses to answer is the same leak written down.
- **`Strict-Transport-Security` is `max-age=31536000 ; includeSubDomains`** — Spring Security's default, kept deliberately, and asserted as a **directive set** — split on `;`, trimmed, compared whole — rather than by the presence of `max-age`, so an upgrade that changes the policy fails the build instead of changing the site quietly. Not as the raw header string: the space before Spring's semicolon is a formatting quirk rather than policy, and a red build for a cosmetic upstream tidy-up is what teaches people to loosen the assertion back to `contains("max-age")`. `HstsHeaderWriter(long)` passes `includeSubDomains = true` and `preload = false`; neither is accidental.
  - **`includeSubDomains` stays.** The protection it adds is not "the apex is also HTTPS" — the apex is covered either way. It is that without it an active attacker can serve `http://anything.5goodmins.com` and *set* a cookie with `Domain=.5goodmins.com`, which the apex then receives on the next request. `Secure` on the session cookie stops a cookie leaking outward; it does nothing to stop one being planted inward. There is no subdomain of `5goodmins.com` that is meant to be plain HTTP, so the directive costs nothing and closes that door.
  - **`preload` is not added, and is not an oversight.** It is the one-way door in this area: submission to `hstspreload.org` bakes the domain into browser binaries, removal travels at the speed of release trains, and from then on *every* subdomain must be HTTPS or be unreachable with no fix the site can serve. A year of `max-age` already buys the protection that matters for a site whose users arrive by typing the name or following a link. Revisit if there is ever a reason beyond a scanner's green tick.
  - **The direct-origin path is not an argument against any of this.** HSTS pins the host in the request and its subdomains; serving the header from `*.up.railway.app` pins that one Railway hostname, not `up.railway.app`, so it cannot reach anyone else's app on the shared suffix.
  - **What is still open is whether the header reaches a browser at all in production** — the question `forward-headers-strategy: native` exists to answer, and the one the curl in the bullet above settles alongside the `X-Forwarded-For` hop. Measured 2026-10-28: Cloudflare is *not* adding the header itself, since its own 502 error page — a response Cloudflare generates end to end — carries no `Strict-Transport-Security`. That removes the masking worry, and it means that whatever the origin emits is what browsers actually get.

- **The web layer of registration** (frontend brief, pages 6, 7 and 8). Four decisions in it are not obvious.
  - **The controller ignores the return value of `register`.** `Registered` and `AddressTaken` take the same branch, build the same flash attribute and redirect to the same URL, and that is asserted by comparing the two responses rather than described in a comment. The service already pays BCrypt on both paths so a stopwatch cannot answer what the page will not; a controller that redirected one and re-rendered the other would hand the answer straight back in the response, and no test of a happy path would notice.
  - **Post/Redirect/Get, with the typed address in a flash attribute.** Rendering the notice from the POST leaves the browser holding a submission to replay, and replaying this one deletes and re-inserts the unverified row and sends another email on every refresh. The address is echoed **as typed** rather than as normalized — that is how a typo gets caught, and it leaks nothing, because they typed it. Opening the notice page without the flash attribute redirects back to the form rather than rendering "we have sent a link to ".
  - **The 72-byte password ceiling is checked in the controller, not by `@Size`.** `@Size(max = 72)` counts characters; BCrypt truncates at 72 bytes; a Cyrillic letter costs two. A forty-character Bulgarian passphrase therefore passes the annotation and reaches `RegistrationService`, whose own check is an `IllegalArgumentException` — a 500 on the registration page, for Bulgarian users only, which is to say for almost everybody this site is for. The controller rejects it onto the password field with a sentence that says why.
  - **A forged verification token gets the same page as an expired one.** The service distinguishes `Invalid` from `DeadLink` and logs the difference, because a bad signature is a probe and an aged-out link is ordinary. The page must not, or it tells whoever is probing whether their forgery was well-formed. A bare `GET /verify-email` with no token is the same page again rather than a 400.
- **The login page says one thing about every failure** — wrong password, unknown address, unconfirmed account, disabled account. That vagueness is load-bearing in two places that are easy to undo separately: it is what keeps the login form from becoming the enumeration oracle `AppUserDetailsService` exists to close, and "your address is not confirmed" would tell anybody with a list of addresses which of them have unconfirmed accounts here. The `error` and `logout` parameters are read for their **presence, never their value**, so a crafted link cannot put chosen text on a page carrying the site's own branding.

- **`LocaleSwitch` could turn an ordinary query string into a 500 on every page, and finding out why took measuring rather than reasoning.** The advice builds its links with `UriComponentsBuilder…build(true)`, which tells Spring the path and query are already encoded — and Spring verifies that promise rather than trusting it, throwing `IllegalArgumentException` from inside a `@ModelAttribute` method. Worse, the error dispatch reaches `BasicErrorController`, which the same advice covers, so building the error page threw again.
  - **The obvious trigger is not the real one.** A malformed percent sequence (`?x=100%`) never arrives: Tomcat 11 answers 400 itself, and does the same for a raw space, `|`, `{`, `^`, `\`, `"` and `<`. What it passes through with a 200, correctly, because RFC 3986 allows it in a query, is a literal `=` inside a value — `?t=abc==`, `?next=http://x/?a=b`. Spring is stricter one level down: `query(String)` splits on `[^&=]+` and verifies each value as `QUERY_PARAM`, which forbids `=` because that is its own delimiter. So the reachable trigger is base64 padding, a nested URL, a JWT. Both ends are now asserted — one over a raw socket, because every HTTP client in the way sanitises the input under test.
  - **Nothing in the flow reaches it today, and that is luck worth naming.** `SignedTokenService` encodes its MAC `withoutPadding()`, so no verification link carries an `=`. ADR-001's `/api/v1/actions?q=…` is one parameter away.
  - **Rejected fixes, each for a concrete reason.** Dropping `build(true)` re-encodes and turns a `%20` in the path into `%2520`. Rebuilding from `getParameterMap()` can never throw — and merges the form body on a POST, so the language links on a redisplayed registration form would carry the submitted password in their `href`. The guard degrades instead, in three tiers: this URL, else this path, else a bare `?lang=` that the browser resolves against what it is already showing (RFC 3986 §5.3, which needs no encoding of anything the request supplied).
  - **The first guard was in the wrong place, and the same review caught it twice.** It wrapped only the query and then called `build(true)` on the path in the fallback — same method, same verification, outside the guard — so a path like `/a%zzb` made the fallback rethrow exactly what had just been caught, and a request with no query at all skipped the guard entirely. Tomcat rejects those paths today, which is the container-strictness argument this class explicitly refuses to rely on for the query. All three tiers are now inside a guard, and the path case is asserted in a unit test rather than through MockMvc: setting `requestURI` to a malformed path stops the request matching any handler, so it 404s before the advice runs and the method under test is never reached.
  - **The advice stays unscoped**, after all three candidate scopes failed. `annotations = Controller.class` excludes nothing, because `@RestController` is meta-annotated `@Controller` and `HandlerTypePredicate` matches through `AnnotatedElementUtils.hasAnnotation` — measured. `basePackages` cannot express the split under package-by-feature, since `action/` will hold the HTML list controller and the JSON API controller both. A `HandlerInterceptor` on a non-null `ModelAndView` would exclude JSON correctly and trade the problem for objects being appended to `redirect:` URLs. The residual cost is two string builds on a request that ignores them, and the redirect hazard is pinned by a test because `ignoreDefaultModelOnRedirect` is a Spring default this depends on rather than sets.
- **One rule for the password ceiling, not two.** `@Size` carries `min = 8` and no `max`. With a `max`, a 73-character Latin password tripped the annotation and the byte check together and the form showed two messages under one field — one of them saying "between 8 and 72 characters", which is not the real ceiling for Cyrillic. The byte check owns the ceiling alone, and the message it shows is the true one.
- **A signed-in visitor is turned away from `/register`.** `permitAll()` permits everyone, not only the anonymous, and registering a second account while holding a session for the first is a state nothing downstream handles. Both the GET and the POST redirect, the POST before validation so it never reaches the service. An `AuthenticationTrustResolver` rather than a null check, because anonymous access is on and the anonymous request carries an `AnonymousAuthenticationToken` rather than nothing. **The cost, named rather than discovered later: `redirect:/` is a 404 today**, since the action list is page 1 and is not built. Rendering the form anyway is the state this prevents, and sending them to `/login` tells someone already logged in to log in — so a 404 is the least wrong answer available, and it becomes the right one when page 1 lands.

- **Ban.** `app_user.enabled = false` stops login and nothing else — on its own, a banned poster's published actions stay live on the site. The ban is therefore a single `@Transactional` method that disables the account **and** moves its `PUBLISHED` actions to `REJECTED`. Rejected: filtering every list query on the author's `enabled` flag, which would add a join to every read path forever in order to serve a rare write. The status change is a plain loop over the loaded actions, not a bulk `@Modifying` update: the count is small and bounded by the submission quota, so dirty checking maintains `updated_at` for free and there is no stale-persistence-context trap. The bulk form — with `updated_at` written into the query by hand and `clearAutomatically = true` — is the alternative if that count ever reaches thousands.
  - **A ban does not invalidate an active session**: a banned user keeps access until the session expires. Mitigated because the ban also rejects their published actions, so harm to others stops immediately. Fix with server-side session invalidation when moving to DB-backed sessions.
- **Account deletion.** Hard delete — see ADR-015.

**Interview point.** "Sessions or JWT?" is a stock question. Your answer: sessions for a first-party browser client (revocable server-side, no token-in-localStorage XSS problem, simpler); JWT when I add a mobile client that can't use cookies well. Naming the tradeoff beats naming the technology.

---

### ADR-003 — Modular monolith, package-by-feature

**Status:** Accepted, made explicit

```
com.example.actions
├── action/        Action, ActionRepository, ActionService, ActionController, dto/
├── user/
├── subscription/
├── digest/
├── moderation/
└── shared/        config, security, exceptions, base entities
```

**Why not package-by-layer** (`controllers/`, `services/`, `entities/`): with 6 features and 4 layers you get 4 fat packages where nothing is co-located and every change touches 4 directories. Package-by-feature keeps a change local and makes future extraction of a module trivial. It's also the structure interviewers associate with people who've maintained something, not just started something.

**One feature does not hold another's repository.** Added in Rev 22, from review. The welcome email's content is actions, so `user/` needs one query out of `action/` — and handed `ActionRepository` it would also have been handed every method on `JpaRepository`, which is a dependency on that feature's storage rather than on its behaviour. `action/ActionQueries` is the read side other features call; it is a pass-through today, because there are no invariants there yet, and what it buys is the seam. The claim this ADR makes — that extracting a module stays trivial — is only true while the surface another feature touches is the one deliberately offered.

**Explicitly rejected:** microservices, CQRS, event sourcing, a message broker, Redis, Elasticsearch. At your scale each of these is a *negative* signal — it says you reach for tools before you have the problem. Say exactly that if asked.

---

### ADR-004 — Hosting: pay the $5

**Status:** Resolved

Rev 1 said "Railway or Render, free tier." The free tiers are actively hostile to this specific app:

**Render free tier:**

- Free web services can't send outbound traffic on ports 25, 465, or 587 — the SMTP ports. The daily digest cannot use SMTP at all.
- Free web services spin down after 15 minutes of inactivity, with cold starts of 30–60 seconds. The `@Scheduled` digest job lives inside that process. A sleeping process runs no cron. The digest simply won't fire.
- Background workers and cron jobs are not available on the free tier at all, so there's no escape hatch.
- Free Postgres databases expire 30 days after creation, then a 14-day grace period, then the database and its data are permanently deleted. This is calendar-based, not idleness-based — keep-alive pings don't help.
- The local filesystem isn't persistent across deploys or spin-downs; persistent disks are a paid feature.
- A recruiter clicking your CV link and waiting 50 seconds on a blank page is a bad first impression you paid nothing to receive.

**Railway:** a one-time $5 trial credit that expires in 30 days, then the Hobby plan at $5/month which includes $5 of usage credit — you always pay the $5 subscription fee. Above the credit you pay per second for RAM and vCPU, so a small always-on JVM plus Postgres will sit near or slightly over the included credit.

**Decision (locked): Railway Hobby, $5/month plus metered usage.**

Rev 2 leaned Hetzner. Two facts changed it: 2.5 years of Docker at Experian already cover much of the ops-credibility gap a VPS would fill; and time — not money — is the binding constraint. A VPS costs a setup day plus ongoing attention (OS patching, TLS renewal, fail2ban, backups you have to verify). That day belongs in the app.

| | Railway Hobby (chosen) | Hetzner CX22 VPS |
|---|---|---|
| Cost | ~$5–12/mo, metered | ~€4/mo, flat |
| Setup effort | Minutes | A day, plus maintenance |
| Ops credibility | Low | High |
| What it costs you | Bill creep, vendor lock-in shape | A week of your nine |

Total spend over a 9-week build plus a 6-month job hunt is under €100.

**Deferred, not rejected.** Moving a Dockerized app to Hetzner is a weekend. If an interviewer probes: "Railway now, because deployment wasn't the skill I needed to prove; the container makes the move trivial."

**Local Postgres runs the same major version as Railway's.** Check with `SELECT version();` on Railway and pin the Docker image tag to match.

**Railway provisions PostgreSQL 18**, so local, CI and Testcontainers are
pinned to **18** — `compose.yaml`, `TestcontainersConfiguration` and the Railway
database all say 18, and they change together or not at all. Nothing in `V1__`
needs anything that new: identity columns are Postgres 10+, stored generated
columns 12+, `pg_trgm` and GIN far older. The pin is not about features, it is
about not developing against a major version the host cannot give back: a
`pg_dump` from a newer server will not restore into an older one, and behaviour
that exists only locally is behaviour nobody has tested.

Rev 7 of this document said the opposite — that Railway offered 16 and 17 with
18 only as community template forks, and pinned everything to 17 on that basis.
The premise was wrong. What settles it is the instruction two paragraphs up:
`SELECT version();` against the deployed database. Run that, not a blog post,
and pin to what it answers.

Two details are coupled to that 18, and both are live facts of this setup rather
than warnings about a version we might one day move to:

- **Postgres 18 changed the default for generated columns to `VIRTUAL`,** and a
  virtual column cannot be indexed. On 18, a `search_vector` declared without
  `STORED` makes `CREATE INDEX ... USING gin (search_vector)` fail — so the
  explicit `STORED` in `V1__` is load-bearing here, not belt-and-braces. The
  evidence that it takes effect is the full-text search tests passing against
  18.6 in CI; the index could not exist otherwise.
- **The data volume path moved in 18.** `PGDATA` is `/var/lib/postgresql/data`
  on 17 and earlier, and the image's declared volume moved up to
  `/var/lib/postgresql` in 18. `compose.yaml` mounts the 18 path; the mount and
  the image tag change together.

**Add Cloudflare in front of the domain** (free tier) once DNS is set up — TLS, caching, and a real answer to "what about DDoS" that costs nothing.

**Non-negotiable regardless:** the app must be Dockerized and configured entirely by environment variables, so the host is a swap, not a rewrite. Deploy an empty "hello world" in week 1, not week 8.

**Interview point.** "How do you deploy it?" answered with a real cost figure, a spin-down tradeoff, and a reason is worth more than naming any platform.

---

### ADR-005 — Email delivery and digest design

**Status:** Revised in Rev 4

**Delivery.** Use a transactional provider's **HTTP API**, not SMTP: Resend, Brevo, or Mailgun free tier. Reasons: SMTP ports are blocked on some hosts, HTTP gives you delivery status back, and providers handle SPF/DKIM. You need a domain you control and DKIM records — plan a day for deliverability, because mail from an unauthenticated new sender goes to spam and you'll debug it thinking your code is broken.

**The provider is Brevo, chosen in Rev 21 on the free-tier limits as they stood that day** (`shared/email/BrevoEmailSender`, `app.mail.brevo.api-key`).

- **The numbers it was chosen on.** Brevo: 300 emails a day, no monthly ceiling, unlimited contacts, no card. Resend: 100 a day *and* 3,000 a month, three domains, 30-day log retention. Mailgun: 100 a day, one domain, one-day log retention — and sendable only from a sandbox domain to five verified addresses until a card is on file, which means it **cannot mail an arbitrary registrant at all**, so it was not a real candidate rather than a close second.
- **Why the daily number is the one that decides it.** The digest sends one email per subscriber per day, so the binding constraint is emails *per day* and not per month, and Brevo's is three times Resend's. A monthly allowance that cannot be spent in a day is an allowance for a different shape of product.
- **The cost, stated rather than discovered.** Resend has the cleaner API and much better delivery logs, and 100 a day would be plenty for this site's real subscriber count for a long while. Trading those logs away in the week whose *other* job is debugging deliverability is the part of this decision to be honest about. It is one class if it turns out to be wrong, which is what the interface was for.
- **One class, and the branch that chooses it.** `EmailConfig` picks Brevo when an API key is set, SMTP when a mail host is, and the sender that refuses loudly when neither is — one `@Bean` method with a branch rather than three conditional beans, because `@ConditionalOnMissingBean` is documented as being for auto-configuration and resolves in bean-definition registration order. **Brevo wins when both are configured**, deliberately: `spring.mail.host` is set for every local run by application-dev.yaml so it is not evidence of an intention, while an API key is only ever present because somebody put it there. Each of the three branches is reached by a *different environment* and none of them is the one the test suite runs in, which is why the choice has a test of its own.
- **Two things the sender does that are not about Brevo.** Connect and read timeouts are mandatory, not defensive habit: the mail pool is one thread behind a fifty-deep queue, and a provider that accepts a connection and then stops talking would, with the JDK's default of no timeout, hold that thread forever and turn a slow afternoon into fifty lost emails followed by silence. And a 4xx and a 5xx are the same failure here — both throw `EmailDeliveryException` — because the distinction between permanent rejection and transient outage is only worth modelling once something retries, and the retry table is the thing this ADR declines to build at this volume.
- **Still owed, and not code:** SPF and DKIM records for the sending domain, and the warm-up. DNS propagation and sender reputation both take days, and neither is testable from here.

**Digest logic.** Do not build a per-user notification queue. One watermark column:

```
app_user.last_digest_sent_at  timestamptz NOT NULL DEFAULT now()
```

Nightly job:

```
cutoff = now() - interval '5 minutes'
for each verified, enabled subscriber:
    select actions where status = 'PUBLISHED'
                     and published_at >  last_digest_sent_at
                     and published_at <= cutoff
                     and ends_at > now()
                     and EXISTS matching category
                     and EXISTS matching region
    if non-empty: render, send, then set last_digest_sent_at = cutoff
```

The watermark moves **only after a successful send**. Idempotent, restartable, no extra tables, and no missed actions if the job fails for two days.

**The digest keys on `published_at` — never `created_at` or `updated_at`.**

- `created_at`: an action created Monday and approved Wednesday would be missed by every user whose watermark moved in between — in practice nearly everyone, silently, forever.
- `updated_at`: a typo fix weeks later would push the action above every watermark and re-send it to the whole platform.
- `published_at` is written exactly once, at the transition to `PUBLISHED` (ADR-016), and never touched again. That immutability is the property the pattern depends on.

**Known consequence: a restored action is not re-announced.** Since Rev 6 an admin can move an action `PUBLISHED → REJECTED → PUBLISHED`. `published_at` keeps its original value, so if the round trip spans a digest run the action is already below every watermark and no subscriber is ever emailed about it. It remains fully visible on the site, in search, and at its permanent URL.

The alternative — re-stamping `published_at` on restoration — was rejected. It would mail the action to the entire platform every time an admin reverses a decision, which is the same failure mode as keying the digest on `updated_at`. One missed email is a cheaper bug than a platform-wide re-send, and the common case (a mistake caught within hours, before the nightly run) loses nothing at all.

**The lag window.** `now()` returns transaction start time. A publish transaction beginning at 02:59:59 is invisible to a job running at 03:00:00, which advances the watermark past it; the row then commits with an earlier `published_at` and is never picked up. Excluding the last five minutes, and advancing the watermark only to that cutoff rather than to `now()`, closes the gap. Cost: up to five minutes of latency in a job that runs once a day.

**Matching uses `EXISTS`, not a join.** Actions carry one to three regions and categories (ADR-009, ADR-017). A join against those tables returns an action once per matching row — an action tagged `BURGAS, NATIONAL` would appear twice for a Burgas subscriber. `EXISTS` avoids creating the duplicate and short-circuits on the first match.

**New registrants.** `last_digest_sent_at` defaults to `now()` at registration, so the first digest contains only actions published after the user joined, never the whole back catalogue. **This restricts email only** — every active action is visible on the site through browsing, filtering and search from the moment the account exists.

**Welcome email.** Sent once, in the same transaction that sets `email_verified_at` — never at registration, because an unverified address is exactly the one not to mail. Contains the five newest active actions, unpersonalized (subscriptions are usually not set yet at that moment). It does **not** touch `last_digest_sent_at`. No new column: "has the welcome been sent?" is answered by `email_verified_at IS NOT NULL`. **Built in Rev 21**, and four things about it are decisions rather than mechanics.

- **"In the same transaction" means the *decision* is made there; the send is still after commit.** `RegistrationService.confirm` publishes a third `RegistrationEmailEvent` from the one branch that actually writes `email_verified_at`, and the existing listener delivers it on the mail pool like the other three. Every other way of reaching the confirmation page — an account already confirmed, a mail client prefetching the link, a user clicking it twice, an expired token — returns before that write. That is what makes "sent once" true with no column recording it, and it is asserted by clicking one link three times.
- **The five actions are loaded inside that transaction and travel in the event as projections** (`action/ActionSummary`), because by the time the listener runs there is no persistence context to load them from — the same reason the event has never carried an entity. Loading them in the listener instead would put a query on the mail pool's single thread and outside any transaction.
- **No categories or regions, which is a smaller email than the brief's digest spec.** The badges live in two element collections, so adding them means a query per action or a join that returns an action once per tag — the duplication the digest avoids with `EXISTS`. The welcome email is unpersonalized chrome and does not need them; the digest and page 1 do, and they can pay for a richer projection.
- **The projection carries an excerpt, cut in SQL, not the whole `description` column** (Rev 22, from review). Reading up to 5,000 characters in order to display 140 is affordable once per account verification and stops being affordable the moment the digest reuses this shape — one email per subscriber per day. Deciding it now rather than when the digest is written is the cheaper order. **`ActionSummary.EXCERPT_CHARS` is defined once** and reaches the query as a bound parameter through `ActionQueries`; Rev 22 wrote it twice and defended that as unavoidable because "JPQL cannot read a constant", excused by analogy to `@Column(length)` against the migration — the analogy does not hold, because that duplication really is forced by `ddl-auto=validate` and this one was a choice, and a duplication documented as inevitable when it was not is a worse answer than either fixing it or owning it (Rev 23, from review). What does remain per-view is the *display* length, with the invariant that the read bound stays comfortably above it or a view abbreviates text SQL had already cut and loses the ellipsis that says it was cut.
- **No promise of a daily digest, which is the sentence this email most obviously wants.** The job is not built, and a welcome email saying "you will now receive a daily digest" is a lie in somebody's inbox until it is. The line lands with the job. The empty-list case is copy rather than a gap, because the first confirmed account on a fresh deployment sees exactly that.
- **Every link in it resolves, which took a correction (Rev 22, from review).** As first built the email carried one link per action to `/actions/{id}` plus a call to action at `/actions`, and neither had a controller — so six links in the first email a confirmed user receives landed on the login form. The defence written down for that was an analogy to ADR-002's note that `redirect:/` is a 404 today, and the analogy does not hold: that one is reachable mid-flow by somebody already signed in, while this lands in a stranger's inbox and is the product's first impression. Two changes close it. `/actions` now has a controller — a structural placeholder for page 1, built because this email needed a destination and carrying none of the filters, search, htmx swapping or pagination that page owes. And the per-action links are **gone**: the five actions are listed as text, the call to action is the email's only link, and the deep links return with page 2. `ActionPaths` lost its `detail(id)` with them, because nothing delivered points there any more and the hazard it guarded — an already-mailed link to a path page 2 might not use — no longer exists.
- **Action type labels now exist in both bundles** (`actionType.PETITION` and the rest, ADR-017), keyed on the enum constant so the template builds the key from the value. Adding a type stays an enum constant plus two properties lines, with no template change.

**Sending is an after-commit event, not a call inside the service method.** Written in Rev 14 before the sender existed, because it is cheaper to build it this way than to retrofit it; built in Rev 16, with one deliberate departure. A registration publishes an application event; a `@TransactionalEventListener(phase = AFTER_COMMIT)` receives it and hands the send to a bounded, single-threaded mail pool (`user/RegistrationEmailListener`, `shared/email/EmailConfig`). Rev 14 specified `@Async` for that hand-off and Rev 16 replaced it with an explicit `emailExecutor.execute(...)` — see "`@Async` was replaced" below for the measurement that caused it.

Two separate bugs, two separate mechanisms, and conflating them is the easy mistake:

- **`AFTER_COMMIT` fixes correctness.** A send inside the transaction goes out even when the transaction later rolls back, so a user receives a verification link for an account that does not exist — and because the token signs a user id that was never committed, it resolves to nothing forever.
- **Handing the send to an executor fixes the timing oracle.** An `AFTER_COMMIT` listener runs on the thread that committed, so without one the HTTP response waits for the mail provider. That is 100–500ms added to one registration outcome and not the other, which reopens the account-enumeration gap ADR-002 closes by hashing on both paths — several times wider than the ~100ms BCrypt difference that was there before. Rev 14 specified `@Async` for this and Rev 16 replaced it with an explicit `emailExecutor.execute(...)`; the bug it fixes is the same either way, and the reason for the swap is three bullets down.

Consequences that come with it, all accepted:

- **Both registration outcomes must publish an event.** The planned "you already have an account, reset your password instead" mail for an address that is already verified goes out through the same listener, so the two paths are the same shape as well as the same page.
- **A failed send no longer rolls anything back,** which is correct — an account should not be undone because the mail provider was briefly down — but it means the failure has to be visible, in two directions. **To us:** the listener logs its own failures with the account id, and so does the queue-full rejection. **To the user:** with no resend form (ADR-002), the recovery is to register again, which is only a recovery if the "check your inbox" page says so — it now has to (frontend brief, page 8). A logged failure nobody can act on and a page that says nothing is an account that silently does not work.
- **The event carries values, not the entity.** The transaction is over and the persistence context is gone by the time the listener runs, so it takes the id, the address and the token as a record. The token goes in the link and never in a log line.
- **A pool of its own, declared with the rest of the email wiring** (`shared/email/EmailConfig`), not borrowed from whatever `@Async` finds.
  - **Correction to Rev 14:** the default is not `SimpleAsyncTaskExecutor` under Spring Boot. `TaskExecutionAutoConfiguration` provides `applicationTaskExecutor`, a `ThreadPoolTaskExecutor`, and `@Async` uses it — so the risk was never a thread per call. It is the **unbounded queue**: that pool's `queue-capacity` defaults to `Integer.MAX_VALUE`, so a mail provider that stops responding accumulates tasks until the heap goes, and the symptom appears somewhere unrelated because the same pool serves everything. (With `spring.threads.virtual.enabled` the default *is* a `SimpleAsyncTaskExecutor`, on virtual threads — still unbounded, in a different way.)
  - **One thread, and say one.** Rev 16 first wrote core 1 / max 2 / queue 50 and described it as two threads. It is not: `ThreadPoolExecutor` grows past its core size only when the queue is **full**, so with fifty slots in front of it the second thread appears on the fifty-first simultaneous send and never otherwise. A maximum above the core size with a non-trivial queue describes a pool that does not exist — and it is a stock interview question, which makes writing it down wrong worse than usual. Now core 1, max 1, queue 50. If the digest ever needs concurrency, both numbers move together.
  - **Defining an `Executor` bean silently removes Boot's.** That auto-configuration is `@ConditionalOnMissingBean(Executor.class)`, so adding the mail pool would otherwise make a single-threaded, fifty-deep-queue executor the default for Spring MVC's async request handling. `spring.task.execution.mode=force` keeps both, and is one line of configuration standing in for a subtle, later, unrelated-looking bug.
  - **`@Async` was replaced by an explicit submit, because of where rejection lands.** This is the part of Rev 14 that building it changed. With `@Async`, the submit happens inside Spring's transaction synchronization, so a full queue throws `TaskRejectedException` out of the proxy, past the listener, and into `TransactionSynchronizationUtils` — which logs *"TransactionSynchronization.afterCompletion threw exception"* and a stack trace, with no account id, no address and nothing identifying it as an email, because the event is no longer in scope. Neither the listener's `catch` nor an `AsyncUncaughtExceptionHandler` would have seen it; both cover failures *inside* the task, and there is no handler in the application now because there is nothing left for it to catch. Measured by saturating the queue and reading the log, not reasoned about. The listener now calls `emailExecutor.execute(...)` itself and catches the rejection with the event in hand, which costs the request thread a few microseconds on both registration paths equally and makes the one failure this design is built to produce attributable. `@EnableAsync` and the `AsyncConfigurer` went with it — there is no `@Async` left in the application.
  - Worth keeping in the notes even though the code no longer needs it: **a `@Configuration` class with `@Bean` methods cannot implement `AsyncConfigurer`** in Boot 4. A bean post-processor wraps every `AsyncConfigurer` to supply `applicationTaskExecutor` as its default, and the wrapped object is then not an instance of the configuration class, so the context fails to start with *"Illegal factory instance for factory method"*.

Four more things settled by building it (Rev 16):

- **`EmailSender.send` throws; it does not return a boolean.** A send that did not happen must not be mistakable for one that did, and a `boolean` is a value a caller can ignore. One exception type (`EmailDeliveryException`) for every transport, so the listener catches "the send failed" rather than `MailException` from SMTP and whatever an HTTP client throws from the provider that replaces it.
- **No transport configured is a loud failure, not a no-op.** Production has no mail host until the provider is chosen, and the deployed site still has to start and serve the pages that send nothing. `UnconfiguredEmailSender` therefore warns at startup and throws on `send`, which puts "nothing is configured" on the same path as "the provider is down" — the path ADR-005 already requires to be logged. A no-op sender is the version of this that makes registrations look successful while no mail exists, and it is also what every `@SpringBootTest` would silently get.
- **The plain-text part is a template, not string concatenation.** One Thymeleaf resolver serves one template mode, so `shared/email/EmailConfig` adds a second, `TEXT`-mode resolver scoped to `email/*.txt`. The alternative — building the text body in Java from bundle keys — puts the layout of a user-facing document in source code and splits one email's content across two places. The two resolvers cannot shadow each other: the text one answers only `email/*.txt`, and the HTML one appends `.html`, finds nothing, and declines (`spring.thymeleaf.check-template` is true by default, which is what makes declining possible rather than fatal).
- **There are four email templates in the MVP, not three.** The "you already have an account" mail is a fourth, and it cannot say what this ADR said it would: password reset is v1.1, so it offers the login page and stops. That is the one place the missing reset is visible to a user, and the copy changes when the feature lands.

**Interview point.** "Why after commit?" is a fair question with two answers, and giving both — the rollback bug and the timing side-channel — is better than giving either.

**Interview point.** "What happens if the email fails?" is the follow-up, and the answer has four parts in this order: the account stays, because it is correct and the provider's bad afternoon is not the user's fault; the failure is logged with the user id, both for a send that threw and for a queue that rejected; the user recovers by registering again, which the page tells them to do, and which really is a resend because the row is replaced; and at scale I would move to a transactional outbox with retries. Naming the next step is what makes "I did not build retries" a decision instead of an omission. The follow-up worth having ready is "what does sending asynchronously cost you?": no rollback, no retry, a bounded queue that drops rather than grows, and a failure whose only symptom is an email that never arrives. The strongest version of that answer is the one that names the measurement — *I used `@Async` until I filled the queue and found the rejection being logged anonymously by the transaction synchronization, so I submit by hand and catch it where the account id still exists* — because it is the difference between having read about the annotation and having run it.

**Also required, not optional:** a signed unsubscribe link that works without login (one-click, no auth). This is a legal requirement in the EU, not a nicety. Same signing mechanism as email verification (ADR-002).

**Email language.** `app_user.locale` (`bg` / `en`). The UI locale comes from a cookie, but a nightly job has no cookie, so the preference must be stored.

**Dev environment: Mailpit in Docker Compose, behind an `EmailSender` interface.**
Resolved in Rev 7. The interface is the same shape and the same justification as
`ImageStorage` in ADR-006: one named, concrete migration path, not a speculative
abstraction.

The tension this resolves is that production sends over an HTTP API while a
local sink speaks SMTP, so without an interface the dev path and the production
path are different code and the one that gets tested is not the one that ships.
With it they differ in exactly one class.

Mailpit was chosen over logging to the console because the deliverable is three
HTML email templates that must survive Gmail, Outlook and Apple Mail (ADR-005,
frontend brief §4). A logged string cannot be looked at; Mailpit renders it, and
shows the plain-text alternative and the headers next to it. The cost is one
more Compose service that is never deployed.

Never send real mail from tests: unit tests use a mock `EmailSender`, and no test
points at Mailpit.

**Interview point.** The watermark pattern is a reusable batch-processing idea; the `published_at` choice and the lag window show you thought about failure, delay and concurrency, not just the happy path.

---

### ADR-006 — Image storage

**Status:** Resolved

Local disk is wrong on every hosting option except a VPS with a mounted volume, and it breaks Docker rebuilds. Two acceptable answers:

**Option A — Cloudflare R2 (or any S3-compatible store).** Store the object key in Postgres, serve via public bucket URL. Free tier is generous, no egress fees. Adds the AWS SDK and some config.

**Option B — Postgres `bytea`, served through a controller.** Genuinely fine below ~1000 images at ≤500KB each. Zero new infrastructure, transactional with the row, trivially backed up with the DB.

**Decision: B for MVP, behind an `ImageStorage` interface** so swapping to R2 is one implementation class. That interface is the whole point — a real, justified use of an abstraction (as opposed to the speculative ones interviewers dislike).

**Validate on upload:** max size (2MB), allowed MIME types by *content sniffing* not file extension, and re-encode with `Thumbnailator` to strip EXIF and cap dimensions. Accepting arbitrary user files without this is a genuine security hole.

**Videos:** external links only. Store the URL, whitelist YouTube/Vimeo hosts, render the embed. Never `th:utext` a user-supplied URL into an iframe without validating the host — that's how you get XSS.

**Avatars: deferred.** With `bytea` storage an avatar is an FK from `app_user` to the image table, which doesn't exist in V1 and creates a user↔image cycle worth one deliberate integration test. Roughly half a day once `ImageStorage` and upload validation exist. Added in the images migration, not V1.

---

### ADR-007 — Search, and the Bulgarian problem

**Status:** Revised in Rev 4

Postgres full-text search relies on per-language dictionaries. The built-in set includes English, Russian, German, French and about two dozen others via Snowball stemmers — **Bulgarian is not among them**. Verify:

```sql
SELECT cfgname FROM pg_ts_config;
```

`to_tsvector('english', 'петиция')` would apply English rules to Bulgarian, which is worse than none. And Bulgarian is heavily inflected: "петиция" / "петиции" / "петицията" won't match each other without stemming.

**Decision:**

- Use the `simple` configuration (tokenization + lowercasing, no stemming, no stop words) for the `tsvector` column.
- Add `pg_trgm` and a GIN trigram index on `title`, using `similarity()` to catch inflected and misspelled forms. FTS and trigrams answer different questions; both run.
- Store the `tsvector` as a **generated column** with a GIN index — not maintained in application code, not a trigger. It is recomputed on every write to the source columns, so it cannot drift from the row.

```sql
search_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
    setweight(to_tsvector('simple', coalesce(description, '')), 'B')
) STORED
```

- **Weights are explicit.** `to_tsvector` assigns positions only; every lexeme defaults to weight D. `setweight` labels title lexemes A and description lexemes B, and `ts_rank` then scores a title hit above a body hit. Without weights, ranking is roughly term frequency, which favours long descriptions.
- **Two-argument `to_tsvector` is required.** Generated columns demand `IMMUTABLE` expressions. The one-argument form is `STABLE` — it depends on the `default_text_search_config` session setting — and is rejected.

**`unaccent` was specified in Rev 3 and removed in Rev 4**, for two reasons found in that order:

1. **It cannot be used here.** `unaccent()` is declared `STABLE`, because it reads a rules file that may change, so Postgres rejects it in a generated column. The common workaround — wrapping it in a locally declared `IMMUTABLE` function — lies to the planner: if the rules file changes, the stored index silently disagrees with new queries.
2. **It buys nothing for Bulgarian.** `unaccent` strips diacritics, a Latin-script problem. Bulgarian has none. It would only help with Latin transliteration, which is a different feature and partly covered by trigrams.

Removing it was cheaper and more honest than working around it.

**Brutal honesty about this feature.** With fewer than a few hundred actions, `ILIKE '%term%'` would perform identically and take twenty minutes. You are building FTS because it's a strong interview artifact, not because the data demands it. That's legitimate for a *portfolio* project — but say so in the interview rather than pretending scale forced your hand. "I used it because I wanted to learn it and it scales past ILIKE, though at current volume either works" is a much better answer than a fabricated performance justification.

---

### ADR-008 — Content language and i18n

**Status:** Resolved — Bulgarian-first UI with English available; single-language content

**UI chrome** (labels, buttons, validation messages, email templates). Externalize every string into `messages.properties` from the first template you write. Ship `messages_bg.properties` and `messages_en.properties`, a `CookieLocaleResolver`, and a language switcher. Default locale `bg`.

**Bundle completeness is checked, not asserted.** `MessageBundlesTest` compares the two key sets in both directions and resolves every key in both locales, because the strict `MessageSource` below turns an asymmetry into a `NoSuchMessageException` — a 500 on that page, or an email that never goes out, for English users only, which nobody notices in development where the default is `bg`. It is the cheapest test in the suite and it covers every label added from here on without anybody remembering to.

**The `MessageSource` is declared, not auto-configured** (`shared/MessageSourceConfig`, Rev 16). Boot's `MessageSourceAutoConfiguration` sits behind a condition that resolves `classpath*:messages.properties` and matches only if that exact resource exists. This bundle layout deliberately has no default-locale file, so the condition does not match, **no `MessageSource` bean is created at all**, and the context falls back to a `DelegatingMessageSource` that resolves nothing. The symptom is not a startup failure: it is a `NoSuchMessageException` when the first email renders, on a pool thread. Found by the email tests failing, not by reading the condition. An empty `messages.properties` would also satisfy it, and was rejected — a file that exists only to make a condition true, whose emptiness is load-bearing. Declaring the bean also puts `fallbackToSystemLocale=false` and the UTF-8 encoding next to their reasons rather than in `spring.messages.*`, which that bean makes inert.

**The locale is clamped to the two languages that exist** (`shared/LocaleConfig`, Rev 16), and that is the other half of making the `MessageSource` strict. Boot's default resolver is an `AcceptHeaderLocaleResolver` with no default locale, so a visitor whose browser sends `Accept-Language: de` resolves to German, and with no fallback bundle the first template to render a message throws `NoSuchMessageException` — a 500 on the home page for anyone with a German browser, found by them rather than by us. There are exactly two ways a locale enters the application and both are now closed: a web request, through a `CookieLocaleResolver` that rejects any language without a bundle (the cookie is user-supplied input, so a hand-edited `locale=de` must not reach the `MessageSource`), and `app_user.locale` for email, through `ck_app_user_locale`. A regional tag matches on language, so `en-GB` is English rather than a fallback. The switcher itself lands with the first templates; the resolver has to exist first, because it is the half that keeps a missing message from being a 500.

**Emails resolve the recipient's locale, never the request's.** `app_user.locale`, read from the row: the nightly digest has no request to read a cookie from (ADR-005) and an after-commit listener runs on a pool thread that inherits no `LocaleContextHolder`. Passing the locale explicitly into the composer is what makes those two cases the same code.

Cost if done from day one: a few hours total, spread thin. Cost if retrofitted in week 8: a miserable pass over every template hunting hardcoded strings, and you will miss some. **This is the entire reason to decide now.**

**Enum labels live here too.** Regions, categories, action types and statuses are English identifiers in code and in the database (`STARA_ZAGORA`, `HUMAN_RIGHTS`); their display text comes from the properties files. Storing Cyrillic labels in the database would put display text in the schema, so translating the UI would require a migration.

**User-generated content** (action titles, descriptions, bios). Single language, stored as free text, no translation columns, no translation table. Bulgarian in practice.

**Bilingual content is rejected, not deferred.** It means either duplicated columns or a `translation` table with fallback logic touching the list pages, the detail page, the search vector, the digest email, and the create form — and it asks NGOs to write everything twice, which they won't.

| Change | Difficulty |
|---|---|
| Add a third UI language | Trivial — one properties file |
| Switch default UI language | Trivial — one config line |
| Retrofit i18n onto hardcoded templates | Painful but mechanical, ~2 days |
| Add bilingual *content* later | Expensive — schema migration, backfill, every read path, new UX |

**For interviews.** Demo with the UI switched to English; content stays Cyrillic. That reads as a real product with a real audience. README, code comments, commit messages and Javadoc in English.

---

### ADR-009 — Region model

**Status:** Revised in Rev 4

**Decision.** `Region` is a Java enum stored as a string: Bulgaria's 28 oblasti plus `NATIONAL` (country-wide issues) and `INTERNATIONAL` (EU or global campaigns). `SOFIA_CITY` and `SOFIA_PROVINCE` are two distinct oblasti — conflating them is a real data error in Bulgarian address handling.

**Region answers "where is the issue", not "where do you act".** Rev 3 had `ONLINE`; it was replaced because nearly every five-minute action is performed online, so that value would have absorbed the whole catalogue and told a user nothing.

**One to three regions per action**, in an `action_region` join table with composite primary key `(action_id, region)`, `ON DELETE CASCADE` from `action`.

- Rejected: numbered columns `region1/2/3` — unindexable, `OR`-chains in every query, and a fourth means a migration.
- Rejected: a Postgres array column. It would enforce the maximum of three in a plain `CHECK` and avoid a second query on list pages, but needs Hibernate-6-specific array mapping that would have to be proven before relying on it. The array is marginally the better design; the join table is the better decision under a nine-week constraint and a requirement to defend every line.
- The maximum of three is enforced in the service and by Bean Validation (`@Size(min = 1, max = 3)`). A row-level `CHECK` cannot count sibling rows.

**`NATIONAL` and `INTERNATIONAL` are ordinary values** and may be combined with oblasti — an action can be national while affecting Burgas more than the rest of the country. A "must stand alone" rule was considered and rejected: it added a service invariant posters would have to learn, for no user benefit.

**Matching rule: plain set intersection, chosen by the user.** A subscriber receives an action when any of their chosen regions is among the action's regions — nothing more. `NATIONAL` and `INTERNATIONAL` are ordinary checkboxes on the preferences page, pre-ticked by default; a user who only wants Burgas unticks them and receives only Burgas actions.

Rev 4 made those two automatic ("subscribed to `BURGAS` implies `NATIONAL` + `INTERNATIONAL`"). Rejected in Rev 5: it overrode a preference the user had expressed, and it meant one more rule to explain and test. A symmetric model is both simpler code and better product. The cost is that a narrow subscription can produce long stretches with no digest at all; mitigated by pre-ticking the two broad values and a hint on the preferences page that national actions usually affect their region too.

The digest query uses `EXISTS` so an action tagged with several matching regions appears once (ADR-005).

**Why an enum, not a table.** The set is fixed, changes only with legislation, needs no admin UI, and gives compile-time safety. A lookup table would be extra files and a join for zero benefit. If the product ever goes multi-country, migrate to a table — a one-day change you can name if asked.

**Mandatory:** `@Enumerated(EnumType.STRING)`, never the default `ORDINAL`. Ordinal storage silently corrupts all your data the day someone reorders the enum constants.

---

### ADR-010 — No `Organization` entity; the role is the source of truth

**Status:** Revised in Rev 4

Rev 1 listed `User`, `Organization (poster)`, **and** a `POSTER` role — the same concept modeled twice, forcing decisions nothing in the MVP depends on (does an Organization log in, can a user belong to several).

**Decision.** One `app_user` table with a single `role` column. Profile fields: `display_name` (required for everyone), `bio`, `website_url`, `poster_verified_at`.

**One fact, one column.** Rev 3 had both a `POSTER` role and a `poster_verified` boolean. With the approval model in ADR-016 those are the same fact stored twice, and two columns holding one fact can disagree. The boolean is removed. The role is the single source of truth for the privilege. `poster_verified_at` survives only because it records *when*, which the role cannot, and a `CHECK` keeps it consistent: `poster_verified_at IS NULL OR role IN ('POSTER','ADMIN')`.

If multi-member organizations are ever needed, add an `Organization` table and a membership join. Documented extension point.

**Interview point.** "Why isn't Organization its own entity?" → YAGNI applied with a named migration path. The good answer is never "I didn't think about it"; it's "I considered it, here's the trigger that would make me add it."

---

### ADR-011 — Duplicate detection

**Status:** Revised in Rev 6

Unique constraint `uq_action_normalized_url` on a **normalized** external URL, stored in its own column alongside the original.

Normalization: lowercase host, strip `www.`, strip all `utm_*` / `fbclid` query params, strip trailing slash, strip fragment. Keep the raw URL for display and the normalized one for the constraint.

`normalized_url` is capped at 500 characters because a B-tree index entry is limited to roughly 2704 bytes. If a real URL ever exceeds the cap, the fallback is a unique index on a hash of the normalized URL.

**The external URL is optional** (Rev 6). Most actions link out, but some are valid without a link. Both URL columns are nullable, paired by `CHECK ((external_url IS NULL) = (normalized_url IS NULL))` so a row can never hold one without the other.

Postgres treats NULLs as distinct inside a `UNIQUE` constraint, so link-less actions never collide with each other and no special case is needed in code. (Postgres 15 added `UNIQUE NULLS NOT DISTINCT` for the opposite behaviour; the default is what this design wants.)

**The constraint covers every status.** A URL exists at most once on the platform, whatever state that row is in. Freeing a URL means hard-deleting the row, which is an admin action. A partial index excluding `REJECTED` was considered and rejected: with the cyclic state graph of ADR-016, a rejected action can be restored, and the restore would then fail on a duplicate submitted in the meantime — a constraint violation at the one moment nobody expects one.

**Two layers, both required.**

1. The service looks the normalized URL up before inserting, so the poster gets "this link is already on the platform" with a link to the existing action, inline on the form.
2. The insert can still fail, because step 1 is a check-then-act race: two posters submitting the same URL in the same second both pass the lookup. Catch `DataIntegrityViolationException`, identify the constraint **by name**, show the same message.

**A duplicate never creates a row.** The submission is rejected at the form; it is not stored as a `REJECTED` action. Storing one per attempt would fill the table with garbage and the moderation queue with noise, and the unique constraint would block it anyway.

**Near-duplicates without a URL** — two link-less actions about the same cause, worded differently — are not detectable this way and are left to the report flow. Accepted gap; AI similarity detection stays deferred.

---

### ADR-012 — Scheduled jobs

**Status:** New

`@Scheduled` in the application process, with `@EnableScheduling`. This assumes **exactly one instance is running**. Two instances = every user gets two digests.

Accept this for MVP. Document it in the README as a known constraint with the fix named (ShedLock, or a database advisory lock). Do not implement ShedLock now.

**Interview point.** "How would you scale this?" → "The scheduler is the thing that blocks horizontal scaling; I'd add ShedLock or move the digest to a separate worker deployment." Knowing where your own design breaks is the answer they're actually testing.

---

### ADR-013 — Persistence configuration

**Status:** New

```properties
spring.jpa.open-in-view=false
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.properties.hibernate.jdbc.time_zone=UTC
```

- **`open-in-view=false`.** Spring Boot enables Open Session In View by default. It keeps the Hibernate session open during view rendering, which hides N+1 queries and lazy-loading bugs until production. Turning it off forces you to load what you need in the service layer, inside a transaction. Expect `LazyInitializationException` the first time — that's the point.
- **`ddl-auto=validate`.** Flyway owns the schema. Hibernate only checks that entities match. Note the limits: `validate` compares column existence and type, and **nothing else** — not `CHECK` contents (adding an enum value to a constraint is invisible to it), not defaults, not indexes, and **not lengths**. Measured, not assumed: with `@Column(length = 500)` on an entity whose column is `varchar(2048)`, the context starts and a 620-character value is stored happily. `length` and `unique = true` on `@Column` are DDL-generation metadata; under `validate` they are documentation that can silently be wrong, so the entity and the migration have to be changed together by hand.
- **All list pages use DTO projections**, not entities. Fewer columns, no lazy proxies, no accidental entity mutation.

Add `datasource-proxy` or `hibernate.generate_statistics=true` in dev so you can *see* the query count. "The category listing was doing 1+N queries, I fixed it with a fetch join and here's the before/after count" is one of the strongest concrete things a mid-level Java candidate can say.

---

### ADR-014 — Testing

**Status:** Accepted, specified

- **Unit tests with Mockito** for service logic only — digest matching rules, URL normalization, region matching, the three-approved-actions rule. That's where the actual logic lives, and it's fast.
- **`@DataJpaTest` + Testcontainers** for repository queries, especially FTS. Testing FTS against H2 is impossible; that's the real justification for Testcontainers.
- **`@SpringBootTest` + MockMvc** for a handful of end-to-end paths: register and verify, log in, post an action, approve it, see it in the list, report it.
- **One singleton Postgres container** reused across the suite, or CI time balloons. A container returned from a `@Bean` method is created per application context, and Spring caches one context per distinct test configuration — `@SpringBootTest` and `@DataJpaTest` are two — so the `@Bean` must return a `static` instance started once per JVM. Nothing stops it; Testcontainers' Ryuk sidecar removes it when the JVM exits. Verified by counting `Creating container for image` in the build log: one, across three context configurations.
- **`TestcontainersConfiguration` must be `public`.** Repository tests live in their feature packages (`action`, `user`), and `@Import` cannot reach a package-private class from another package. Spring Initializr generates it package-private.
- Tests compare `Instant`s truncated to microseconds, or use an injected `Clock`.
- **Don't** chase a coverage percentage. Test the logic that would embarrass you if it broke.

**Boot 4 / JUnit 6 specifics, because every tutorial predates them:**

- The stack is **JUnit 6** (Jupiter 6.0.x), not JUnit 5 — that is what Boot 4.1 manages. Earlier revisions of this document said JUnit 5.
- `@DataJpaTest` moved to `org.springframework.boot.data.jpa.test.autoconfigure`. The old `...test.autoconfigure.orm.jpa` package does not exist.
- The umbrella `spring-boot-starter-test` is replaced by per-slice starters: `spring-boot-starter-data-jpa-test`, `-webmvc-test`, `-security-test`, `-flyway-test`, `-thymeleaf-test`. Mockito and AssertJ arrive through them.
- **`PostgreSQLContainer` is not generic in Testcontainers 2.x.** The `<SELF>` self type that every 1.x example writes as `PostgreSQLContainer<?>` was removed, and the artifact is `org.testcontainers:testcontainers-postgresql` with package `org.testcontainers.postgresql`.
- An exception raised by a native query on the `EntityManager` is JPA's `PersistenceException`, not Spring's `DataIntegrityViolationException`: translation into the Spring DAO hierarchy happens at the repository proxy boundary.

---

### ADR-015 — Account deletion

**Status:** Resolved — hard delete

**Decision.** Deleting an account hard-deletes the user row, their subscriptions, their private completed-action records, and their actions with everything attached. No tombstones, no soft-delete flag.

**Why this is defensible.** GDPR Art. 17 requires erasure of *personal data*, not of all content a person authored. Keeping anonymized actions would arguably be compliant. But leaving means leaving, the MVP has no other stakeholders in that content, and hard delete is simpler to implement, test and explain.

**What it costs.** A poster who deletes takes live actions with them, breaking bookmarks and digest links. Deleted actions return **404** through the normal error page. Not 410: a 410 asserts the resource existed and is permanently gone, which requires remembering it — a tombstone, which this ADR rejects.

**Referential integrity.** Every table pointing at `app_user` or `action` has an explicit deletion rule, declared once in the Flyway migration:

| Referencing row | On user delete | On action delete |
|---|---|---|
| Action (authored) | cascade | — |
| `action_category`, `action_region` | cascade (via action) | cascade |
| Subscription | cascade | n/a |
| Image | cascade (via action) | cascade |
| Report *filed by* the user | cascade | — |
| Report *about* their action | cascade (via action) | cascade |

**Do not** let JPA and the database both own cascading. `ON DELETE CASCADE` lives in the migration; JPA uses plain associations without `cascade = REMOVE`. The classic bug is the two disagreeing, or JPA loading 500 children into memory to delete them one row at a time.

**Implementation:** a single `@Transactional deleteAccount(userId)`, and one integration test that creates a poster with actions, categories, regions, images, reports and subscriptions, deletes them, and asserts every table is empty.

**Completed actions ("I did it", v2):** private to the user by design, never shown on a public profile. A `user_id` + `action_id` + `completed_at` table, deleted with the user. A public opt-in was proposed and deferred: which petitions someone signed reveals political opinions, which is special-category data under GDPR Art. 9, so it needs a deliberate consent flow rather than a checkbox. Adding it in v2 is one boolean — nothing in V1 changes either way.

---

### ADR-016 — Action lifecycle and poster approval

**Status:** New (Rev 4)

**Who may submit.** Every registered, email-verified user. The role determines whether the submission needs approval:

| Role | May submit | Result |
|---|---|---|
| `USER` | yes | `PENDING`, awaits an admin |
| `POSTER` | yes | `PUBLISHED` immediately |
| `ADMIN` | yes | `PUBLISHED`, plus moderation powers |

**States: four, not five.** `HIDDEN` is removed in Rev 6. It existed only because the Rev 4 graph was one-way: it meant "an admin took this down", as distinct from a poster's own `WITHDRAWN`. Once an admin can move `PUBLISHED → REJECTED`, `REJECTED` *is* the takedown state and `HIDDEN` is a second name for the same fact.

```
PENDING ↔ PUBLISHED ↔ REJECTED ↔ PENDING    (admin, any direction)
PUBLISHED ↔ WITHDRAWN                       (the poster's own choice, reversible)
```

- **An admin** may move an action between `PENDING`, `PUBLISHED` and `REJECTED` in any direction. Admins make mistakes, reports arrive after publication, and an action that needs a second look belongs back in the queue rather than in a state of its own.
- **The poster** may withdraw their own published action and re-publish it. They cannot leave `REJECTED` directly; editing a rejected action returns it to `PENDING`, which is the natural "fix it and resubmit" path and the only way a poster re-enters the queue.
- The column defaults to `PENDING`, so a forgotten assignment fails closed rather than publishing.

**Restoring a rejected action keeps its identity.** The admin action is a status change, not a re-creation: same id, same permanent URL, same `created_at`, bookmarks and shared links keep working, and the poster retypes nothing. Rejected: "hard-delete and re-post as new", which changes the id, breaks every link already shared, destroys the record of what was moderated, and requires the content to be re-entered — all to buy one thing the status change does not give, a second appearance in the digest. See the known consequence in ADR-005 for why that one thing is not worth having.

**`published_at`** is set once, at the first transition to `PUBLISHED`, and never modified afterwards — including on restoration. The `CHECK` is deliberately one-directional (`status IN ('PENDING','REJECTED') OR published_at IS NOT NULL`): because the graph is cyclic, a `PENDING` or `REJECTED` row may legitimately carry a `published_at` from an earlier publication. The invariant still worth asserting is that anything currently `PUBLISHED` or `WITHDRAWN` has been published at least once. See ADR-005 for why the digest depends on it.

**Admin queue.** The moderation page defaults to `status = 'PENDING'`, oldest first, because that is the only state with someone waiting on the other end. A status filter and a title search cover everything else; finding an action that needs special treatment does not need a separate page or a separate state.

**Submission quota.** A maximum of ten actions per user per rolling 24 hours, enforced in the service as `COUNT(*) WHERE author_id = ? AND created_at > now() - interval '1 day'`. This is the answer to "a trusted poster goes bad and publishes fifty actions in a minute" — `POSTER` submissions skip review, so without a cap the only control is a ban after the damage.

It is a database count, not a Bucket4j bucket (ADR-002): the limit is per account rather than per IP, and it must survive a restart and a second instance. Rejected: a counter column, which would need resetting and could drift; the count is cheap and `idx_action_author_id` serves it.

**Promotion to `POSTER`.** An admin grants the role manually — there is no automatic promotion — and only once the user has at least three approved actions. The threshold is a floor the admin must clear, not a trigger. Enforced in the service — `verifyPoster()` throws below the threshold — and computed as a `COUNT(*)` at the moment of the click. Rejected: a `pending_approvals_remaining` counter column, which would need updating on every approval, rejection and deletion, and would drift.

**The count is `published_at IS NOT NULL`, not `status = 'PUBLISHED'`** (corrected in Rev 7; the first implementation had it wrong). Because this graph is cyclic, `status` is the *current* state while the threshold asks a *historical* question: has an admin ever approved this action? An author who published five actions and then withdrew two would count as three, and one whose action was later taken down would lose credit for work that was approved. `published_at` is written once and never cleared, so it already records "has been published at least once" — the same immutability the digest depends on, reused. The one-directional `ck_action_published_at_required` exists for exactly this reason.

**Expiry is derived, not stored.** `ends_at` is mandatory, and the database checks `ends_at > created_at`. The one-year maximum is enforced in the service and by Bean Validation — it's a policy that may change, and `timestamptz` interval arithmetic depends on session time zone, which makes it a poor fit for a `CHECK`. An action is expired when `ends_at < now()`, evaluated in the query. Rejected: an `EXPIRED` status, which would need a nightly job whose only purpose is keeping a derived value in sync, plus a daily window where the database contradicts itself.

**Quarterly re-confirmation deferred to v1.1.** Asking posters every three months to confirm the action is still live, and archiving after a month of silence, needs a `last_confirmed_at` column, an `ARCHIVED` state, a second scheduled job, a bilingual reminder template and a signed no-login confirm link — one to two days landing in week 7, the week most likely to overrun. Mandatory `ends_at`, poster withdrawal, and a "no longer live" report reason cover the same ground for the MVP. Adding it later is one `ALTER TABLE` and one `CHECK` change: a scheduling decision, not an architectural one.

**Rejection reasons** arrive with the moderation module (week 8), not in V1.

---

### ADR-017 — Categories and action types

**Status:** New (Rev 4)

**Categories: one to three per action**, in an `action_category` join table with composite primary key `(action_id, category)` — same structure, same rejected alternatives and same service-enforced maximum as regions in ADR-009.

MVP set (10):

`NATURE`, `ANIMALS`, `CHILDREN`, `HEALTHCARE`, `DISABILITY`, `LOCAL_COMMUNITIES`, `SOCIAL`, `HUMAN_RIGHTS`, `LABOUR_RIGHTS`, `POLITICAL`

Folded rather than added: addiction → `HEALTHCARE`; freedom of speech and protection from violence → `HUMAN_RIGHTS`; mental and physical disability → one `DISABILITY` category.

**The taxonomy is one axis with accepted fuzziness.** The original categories were beneficiary-based (who is helped); some additions are issue-based (what it's about). A proper two-facet model (beneficiary + issue) is cleaner but doubles the filter UI and the subscription model — v2 if ever. For the MVP, "up to three" absorbs the overlap. The README carries a one-line definition of each category; if two can't be told apart in a sentence, merge them.

**Ten is a deliberate ceiling.** It fits a filter sidebar without scrolling, and with ~40 seed actions, fifteen categories would leave half the category pages showing one item.

**Changing the set costs one migration**: drop and recreate the `CHECK`, add the Java constant, add two properties entries. Adding is cheap because existing rows are untouched. Removing is expensive because existing rows would violate the new constraint — so err toward too few.

**Action types: exactly one per action**, a column on `action`:

`PETITION`, `LETTER_OR_EMAIL`, `SURVEY`, `SOCIAL_MEDIA`, `OTHER`

Cut:
- `EVENT`, `VOLUNTEERING` — neither fits "five good minutes", and an event needs a date and a physical location, which is a different shape from a link-out action.
- `DONATION` — the platform does not carry solicitations for money. This also keeps it clear of payment-related moderation and fraud liability.
- `PUBLIC_CONSULTATION` — folds into `LETTER_OR_EMAIL` (submitting a written opinion) or `OTHER`.

**Hashtags rejected for the MVP.** They need a `tag` table, an `action_tag` join, normalization, tag pages and moderation of abusive tags, and they hit the same inflection problem as ADR-007 — `#природа`, `#природата` and `#природи` are three unrelated tags. Full-text search already finds words in the title and description. Adding tags later is purely additive, so the cost of waiting is zero.

---

### ADR-018 — Build tool, framework version, Java version, configuration format

**Status:** New (Rev 5)

**Maven, not Gradle.** Most Java job adverts and enterprise codebases use Maven; its XML is explicit and boring; interviewers ask about the build lifecycle and dependency scopes in Maven terms. Gradle builds faster and is nicer to write — not enough to outweigh matching the market you're applying into.

**Spring Boot 4.1.x, not 3.5.x.** Rev 1–4 said "Spring Boot 3"; Spring Initializr no longer offers 3.x, and Boot 3's open-source support window closed in 2026. Starting a new project on a version in extended maintenance would need explaining; starting on the current one does not.

What it brings along: Spring Framework 7, Spring Security 7, Hibernate 7, Jackson 3, a Jakarta EE 11 / Servlet 6.1 baseline, and renamed modular starters (`spring-boot-starter-webmvc` rather than `-web`, `spring-boot-starter-flyway` rather than a bare Flyway dependency).

**The known cost, accepted:** the overwhelming majority of tutorials, Stack Overflow answers and AI-generated snippets are written for Boot 3. Expect to translate — especially Spring Security configuration, where the 7.x DSL dropped long-deprecated methods. Treat any example that uses `spring-boot-starter-web` or `WebSecurityConfigurerAdapter` as out of date. **Mitigation:** pin versions in the POM, read the Boot 4 migration guide once, and prefer official docs over blog posts for security and persistence config.

**Java 21, not 17 or 25.** Boot 4's baseline is Java 17, with first-class Java 25 support. 21 is the LTS that dominates current job adverts, it gives records, pattern matching, sealed types and virtual threads, and every library in the stack is mature on it. Java 25 is a legitimate alternative if you want to say you're on the newest LTS; 17 would mean giving up language features for nothing.

**Configuration in YAML** (`application.yml`), not `.properties`. Profiles live in one file via `spring.config.activate.on-profile`, nesting removes the repeated `spring.jpa.properties.hibernate.` prefixes, and lists are readable. The cost is indentation sensitivity and that tabs are illegal.

**Unaffected by that choice:** `messages_bg.properties` and `messages_en.properties` are a `MessageSource` format, not Spring configuration, and stay `.properties` regardless (ADR-008).

**Local versus deployed Postgres must match on major version.** Check Railway's with `SELECT version();` and pin the same tag in `docker-compose.yml`.

---

### ADR-019 — Time: one injected `Clock`, never `Instant.now()` in place

**Status:** New (Rev 13)

**Decision.** Nothing in `src/main/java` calls `Instant.now()`, `LocalDate.now()` or any other no-argument `now()`. "Now" enters the application through a single `Clock` bean (`shared/TimeConfig`, `Clock.systemUTC()`), injected into whatever needs it; code that is called by something which already knows the time takes an `Instant` parameter instead of reaching for a clock of its own. Entities never hold one — `AppUser`'s constructor and `markEmailVerified` both take the instant from their caller.

The two exceptions, both deliberate: Hibernate's `@CreationTimestamp` / `@UpdateTimestamp` own `created_at` and `updated_at`, and `DEFAULT now()` in the migration is the database's safety net for SQL-level writes. Those columns are audit metadata nothing branches on. Anything the application *reasons* about comes from the clock.

**Why this is a decision and not a style preference.** Every time-dependent rule in this design is interesting only at a boundary, and the boundary is days away:

- the digest watermark and its five-minute lag window (ADR-005),
- the seven-day lifetime of a verification token and the seven-day purge of unverified rows, which ADR-002 requires to be the same window,
- `ends_at` expiry on an action.

Time read from inside a method is time a test cannot set. The alternatives are to sleep — seven days — or to leave the boundary untested and find out in production, which for the purge means deleting rows a day early or a day late with nothing to show for it either way. With the clock injected, crossing seven days is one line in a test.

**What it costs.** One more constructor parameter in a handful of classes, and the discipline to notice when a new one needs it. A `Clock` is not free to thread through code that did not previously take one, which is exactly why the rule is stated before the code rather than retrofitted after.

**`systemUTC()`, not `systemDefaultZone()`.** Everything stored is `timestamptz` read as `Instant` (§2), so a zone on the clock would only be a default waiting to be picked up by something that should not have one. Zone conversion to Europe/Sofia is the template's business, at the point of display.

**Rejected: a static mutable clock holder,** the `Clock.setForTesting(...)` pattern. It makes tests order-dependent and parallel execution unsafe, and it hides the dependency instead of declaring it — the whole point here is that a class which depends on the time says so in its constructor.

**Enforcement: a grep in CI, not ArchUnit.** The rule is broken by addition rather than by editing — nobody puts an `Instant.now()` into a class that already takes a `Clock`; they put one into a class written months from now, which is the case review is worst at catching. So the workflow fails on any no-argument `now()` or `System.currentTimeMillis()` under `src/main/java`, with the Javadoc that *discusses* the rule filtered out.

ArchUnit would express this properly, and would also express the two other rules worth enforcing — package-by-feature's dependency directions (ADR-003) and entities not leaving the service layer (ADR-013). It is not here yet for one reason: its JUnit integration is built against JUnit Platform 1.x and this project is on JUnit 6 (ADR-014), so adopting it means resolving an unverified compatibility question in exchange for a rule two lines of shell already enforce. The moment the package rules need enforcing is the moment that dependency pays for itself; a grep cannot express those.

**Interview point.** "How do you test something that expires in seven days?" is the stock question, and the answer is this rule rather than a mocking framework. Worth having the follow-up ready too: a fixed `Clock` proves a boundary, a *mutable* one proves a sequence — issue a token, move eight days, watch the same token change its answer.

---

## 4. MVP scope — what's cut

| Feature | Rev 1 | Now |
|---|---|---|
| Admin panel | "Review reports, warn, ban" | One page: approval queue (default `PENDING`, oldest first), a status filter, open reports, buttons to approve / reject / restore / dismiss, and a "grant POSTER" button. No warning system. Ban = disable the account and reject its published actions — ADR-002. |
| Moderation reports | Form with categories | A reason enum (incl. "no longer live") + optional text. |
| Profiles | Bio + history + links | One page: display name, bio, website, their published actions. No avatar in MVP. |
| Search | FTS + filters + ranking | FTS on title/description plus trigram similarity on title. Filters are plain SQL predicates. |
| Poster verification | Verification flow | Role change by an admin after three approved actions — ADR-016. |
| Hashtags | — | Rejected for MVP — ADR-017. |
| Quarterly re-confirmation | — | Deferred to v1.1 — ADR-016. |
| Public completed-action history | — | v2, private by default — ADR-015. |
| Account lockout | — | Rejected in favour of rate limiting — ADR-002. |
| Resend-verification form | Rev 8 required one | Cut in Rev 14 — registering again over an unverified row already is a resend, and `/register` is rate-limited — ADR-002. |

**Deferred to v2+:** ranking algorithm, AI duplicate detection, "I did it", articles hub, activism calendar, push notifications, native mobile app, avatars (images migration), two-facet taxonomy.

**One addition that is not optional: seed data.** You're building an aggregator with no ingestion pipeline and no onboarded NGOs. On demo day the app is empty unless you make it not be. ~40 realistic actions across every category, type and region, written in Bulgarian. Without this, a polished app demos as a blank page — the most common way portfolio projects fail their one job.

**Seed data is an ordinary versioned migration** (`V100__seed_demo_data.sql`, numbered high so schema migrations keep V1–V20), running in every environment including the deployed site — because the deployed site *is* the portfolio and must never be empty. Rejected: a `dev`-profile-only Flyway location, which adds configuration for a distinction that doesn't exist here and later fails validation ("detected applied migration not resolved locally") when a seeded database is started with the production config.

Two requirements on its content: seeded authors use emails at `seed.example` (a reserved domain per RFC 2606 that can never receive mail), so one `DELETE` plus cascade removes all demo data when real NGOs arrive; and all dates are relative (`now() + interval '180 days'`), because fixed literals mean the whole catalogue is expired and invisible on the day you demo it.

---

## 5. Decisions resolved and open

**Resolved:**

| Question | Answer |
|---|---|
| Content language | Bulgarian-first UI (`bg` default, `en` available), single-language content — ADR-008 |
| Hosting | Railway Hobby — ADR-004 |
| Domain | `5goodmins.com` (buy `.org` too if cheap). Register at Porkbun/Namecheap. Point DNS at Cloudflare, then Railway. Set up SPF + DKIM for the mail provider on day one, not in week 7 — DNS propagation and deliverability warm-up both take days. |
| Image storage | Postgres `bytea` behind an `ImageStorage` interface — ADR-006 |
| Account deletion | Hard delete, 404 afterwards — ADR-015 |
| Poster approval | Role-based, three approved actions before promotion — ADR-016 |
| Region cardinality | One to three, `NATIONAL`/`INTERNATIONAL` combinable — ADR-009 |
| Categories and types | 10 categories, 5 types — ADR-017 |
| Email provider | **Brevo**, on free-tier limits at the time: 300/day with no monthly cap, against Resend's 100/day and Mailgun's sandbox-only free tier — ADR-005 |
| Rate limiting | `/login` 20 per 15 min per address; `/register` 10 per hour per address (refuses the request, and is what `refuse-requests` switches off) and 3 per day per recipient (refuses the send, commits on success, has no switch) — ADR-002 |

**Open:**

1. **JSON API timing** — MVP or the week after. Half a day; don't let it displace the digest.
2. **Frontend contributor and mobile.** A front-end developer has offered to contribute and has proposed a progressive web app. Proposed (not yet agreed):
   - Fully responsive, mobile-first templates — required regardless.
   - Web app manifest + icons so the site is installable to a home screen — cheap, yes.
   - Service worker for offline caching — **no** for the MVP: every action links out to an external site that needs a connection, and caching server-rendered pages that carry session state and CSRF tokens produces stale pages and 403s after deploys.
   - Web push — v2, already on the deferred list.
   - Contribution boundary: the contributor works within ADR-001 (Thymeleaf templates, htmx attributes, CSS, static assets), follows ADR-008 (no hardcoded strings), and does not introduce an SPA framework or a JavaScript build pipeline without a new ADR.

---

## 6. Timeline

Realistic, at ~5 focused hours/day with applications in parallel:

| Weeks | Deliverable |
|---|---|
| 1 | Skeleton, Docker Compose (app + Postgres + Mailpit), Flyway `V1__`, CI pipeline, **deployed hello-world on the real host** |
| 2–3 | User + auth + roles + registration + email verification + profile page. Slower than it looks — Spring Security is where the rust shows. |
| 4–5 | Action entity, submission, approval queue, detail page, image upload, list + category/region/type filters, seed data |
| 6 | Search, subscriptions/preferences |
| 7 | Digest job + welcome email + email provider + deliverability + unsubscribe |
| 8 | Moderation, admin page, JSON API + Swagger |
| 9 | Polish, turquoise theme, README with architecture diagram, ADR log, roadmap |

**Nine weeks, and ship something demoable at week 5.** From week 5 the URL goes on your CV even while features land.

If you're getting interviews by week 4, shift hours from building to preparation without guilt. The project is a means to the job, not the other way round.

---

## 7. Interview talking points this design gives you

Full Q&A in `docs/INTERVIEW-QA.md`. The headline list:

- Sessions vs JWT — chosen per client type, not by default
- `open-in-view=false` and the N+1 problem, with a measured before/after
- `EnumType.STRING` vs `ORDINAL` and why ordinal is a data-corruption bug waiting to happen
- Flyway as the single owner of schema; `ddl-auto=validate` and its limits
- Identity vs UUID primary keys, and the Hibernate batching consequence
- Idempotent batch processing via a watermark column — keyed on an immutable `published_at`, with a lag window for in-flight transactions
- `EXISTS` vs join when matching against one-to-many sets
- Why cascade deletes live in the database and `updated_at` lives in Java
- Unindexed foreign keys; partial indexes; GIN vs B-tree
- The `unaccent` correction — `STABLE` vs `IMMUTABLE`, found by reading the docs
- Rate limiting vs account lockout
- Where the design stops scaling (the in-process scheduler) and what you'd do about it
- Why you *didn't* use microservices, Kafka, Redis, or Elasticsearch
- Testcontainers justified by a real need (FTS can't be tested on H2)
- An abstraction (`ImageStorage`, `EmailSender`) introduced for a concrete, named migration path
- Why the email goes out after the commit *and* off the request thread — two different bugs, one of them a timing side-channel rather than a correctness bug
- Proxy IP trust: what `X-Forwarded-For` is actually worth behind two proxies, and why the rate-limit key is the valve's output rather than a vendor header

---

## 8. Changelog

- **Rev 24 (2026-10-29):** Review of Rev 23, and **the finding worth recording is not the stale comment, it is how it stayed stale.** Rev 23 renamed `enabled` to `refuse-requests` and reported the class javadoc rewritten; it was not. The edit was a string replacement with a mismatched indent and no assertion, so it silently changed nothing — and the paragraph `RateLimitFilter` points at for its explanation went on claiming the switch turns off the limiter, which is the exact conflation Rev 23 existed to remove. A reader following that pointer found the opposite of the truth. Both javadocs are now correct, every edit in this revision asserted its anchor before writing, and the repository was swept for the whole class of error rather than the one instance reported. **The second finding became a code change rather than a dashboard chore.** A renamed property leaves any environment still setting the old name binding to nothing, silently, because Spring ignores unknown configuration properties — so `APP_RATE_LIMIT_ENABLED` left behind in Railway would have brought the limiter back on at the next deploy with nothing saying so. `app.rate-limit` is now bound with `ignoreUnknownFields = false`, measured to work with record binding before being relied on: startup fails with *The elements [app.rate-limit.enabled] were left unbound*. That is the answer this project already gives for the absent datasource and the absent signing key, and on Railway a container that will not start never takes over from the one running, so being wrong costs a deploy rather than the site. Pinned by a test and mutation-checked like the others. 259 tests, up from 257. **Still open, and still the one thing between this branch and a limiter anybody can say works:** the live `X-Forwarded-For` reading. The README carries the procedure and the three answers it can give; it needs a deploy and a `railway login`.

- **Rev 23 (2026-10-29):** Review of Rev 22, and **three of the four findings are the same mistake in different places: a correction applied to the happy path and not to everything around it.** Rev 22 moved the per-recipient mail cap off the request and onto the send, correctly — and then left `enabled` disarming all three buckets, spent the token before the send rather than after it, and logged the suppression at WARN under a comment saying it was not a failure. **The switch was the worst of them.** Its documented purpose is recovering from the limiter refusing real users, which only the two address-keyed buckets can do; the mail cap is keyed on the recipient and cannot misfire against a client. So the one situation the flag exists for was also the situation in which flipping it turned `/register` back into a mail cannon — with the per-address bucket going off at the same moment, so nothing was left bounding it at all. It is now `refuse-requests`, named for what it does, read only by `RateLimitFilter`, and the mail cap has no switch: to relax it you change the number. `RateLimitProperties` carries a test over its own component list, so adding a flag that disarms the cap means changing that test first. **The second is one idea this project already states elsewhere:** the cap counts mail that reached an inbox, not attempts to hand it to a provider, so a Brevo outage plus three retries by a user who can see no email must not leave that address capped for a day having received nothing. A token is now refunded when the send throws — consume-then-refund rather than peek-then-consume, because the pool is single-threaded today and ADR-005 says both pool numbers move together if the digest ever needs concurrency, at which point a peek becomes a race that over-sends. It is the same rule as the digest watermark moving only after a successful send, and the general form is worth naming: a side effect that bounds a resource commits on success, never on attempt. **The third:** `EXCERPT_CHARS` did not have to be written twice. JPQL takes a parameter in `substring`, so `ActionQueries` passes the constant and the duplication is gone rather than documented — and the `@Column(length)` analogy used to excuse it does not hold, because that one is genuinely forced by `ddl-auto=validate` while this was a choice. **The fourth is a log level**, which matters only because the comment above it claimed the opposite: suppression is INFO now, and the operator signal is the per-address WARN in the filter, which names the client. **One process note worth keeping.** The first version of the refund test passed with the refund deleted, because it built a fresh limiter per listener and therefore a fresh bucket store. Each of the three new guarantees was then checked by mutating the code it covers and confirming the intended test — and only that test — went red. A regression test nobody has seen fail is a comment. 257 tests, up from 252. **Still not measured, and still the reviewer's merge gate:** the live `X-Forwarded-For` reading. The procedure is now written down in the README so it is a copy-paste rather than a thing to remember, but it needs a deploy and a `railway login`, and this branch cannot do either.

- **Rev 22 (2026-10-29):** Review of Rev 21, and **the headline finding is that the rate limiter had rebuilt the thing ADR-002 rejects account lockout for, on a different form.** The per-address registration bucket was spent in the filter, keyed on the submitted email — so three POSTs naming `victim@example.com` locked the real owner of that address out of registering for twenty-four hours, with a bare 429, and needed neither a session nor a CSRF token because the filter sits in front of `CsrfFilter`. The code refuted an answer this project has written down twice in ADR-002's own words: a denial-of-service weapon against anyone whose email is known. **The fix follows from the bucket's own stated job** — it protects a stranger's inbox, not this server, so it belongs on the send: the allowance now suppresses the *email* in `RegistrationEmailListener` and the request still returns the neutral "check your inbox" page. The inbox is bounded exactly as before, its owner is refused nothing, and `/register`'s single answer for every address survives, which the 429 had also broken. What it still costs is named rather than glossed: an attacker can deny a targeted address its email for a day, just not its account, and no cap on mail to an address can both bound the mail and promise the next message arrives. The rule left in the filter is the narrow one — a bucket that can refuse a request may only be keyed on something the requester cannot choose on somebody else's behalf — and two things fall out of it: the filter reads no request parameter, so the multipart caveat is gone, and the welcome email is exempt from the cap because reaching it needs a signed token only the mailbox's reader has. **Three more findings landed and two of Rev 21's own claims were wrong.** The welcome email shipped six links to pages with no controller, defended by an analogy to `redirect:/` being a 404 — which does not hold, because that one is mid-flow for somebody signed in while this lands in a stranger's inbox. `/actions` therefore has a controller now, a structural placeholder for page 1 carrying none of its filters, search, htmx swapping or pagination; the per-action deep links are gone and return with page 2, and `ActionPaths.detail` went with them. `user/` no longer holds `action/`'s repository: `ActionQueries` is the read side other features call, which is ADR-003's claim about trivial module extraction being made true rather than asserted. And `ActionSummary` carries an excerpt cut to 200 characters in SQL rather than a 5,000-character column read to display 140 of it — affordable once per verification, not once per subscriber per day, which is the shape the digest will reuse. **The two wrong claims, both in comments, which is the worse place for them in a file meant to be defended line by line.** "Boot's own internal-proxies default is a CIDR list, which is the proof it goes through the same setter" is circular; the real reason is documented, and better: Tomcat 11 accepts either a CIDR list or a single regular expression on both attributes, and regular-expression configuration is deprecated and removed in Tomcat 12 — so Boot's historical regex default is itself on a clock. And calling a stale Cloudflare list "over-restrictive, the right direction for a limiter to break in" was wrong: collapsing every request onto one bucket is twenty login attempts per fifteen minutes for the whole site, which is an outage, not a safe state. **One review concern did not survive measurement:** spring-boot#34119, the regex `internal-proxies` default missing carrier-grade NAT, is fixed in Boot 4.1.1 — its default, read out of `spring-boot-tomcat`'s own configuration metadata, is a CIDR list containing `100.64.0.0/10`, so Railway's hop is covered and the property is rightly left alone. **And the Bucket4j pin was worse than reported:** `bucket4j-core` is a dead artifact line that stops at 8.10.1, because from 8.11 the artifacts are split by target JDK — the right one on Java 21 is `bucket4j_jdk17-core`, now at 8.21.0, with the same API and no transitives. 252 tests, up from 233. **Still not measured, and the reviewer is right that it should gate the merge:** nobody has taken the `X-Forwarded-For` reading the limiter's log line now makes available, or the HSTS curl. Both need a live origin and a `railway login`, and neither is something this branch can do.

- **Rev 21 (2026-10-29):** **ADR-002's rate limiting is code, the email provider is chosen, and the welcome email exists.** 233 tests, up from 157 (252 after Rev 22's corrections). **The rate limiting was blocked on something no amount of building would have produced: the numbers.** ADR-002 specified the key and the mechanism and never a requests-per-window, so the first thing this revision did was settle `/login` at 20 per 15 minutes per client address and `/register` at 10 per hour per address plus 3 per 24 hours per email address — and the reasoning is the part worth keeping, because none of the three is sized for one user. The login number is sized for a *shared* address, which in Bulgaria is most mobile traffic behind carrier-grade NAT; the per-address registration number exists only for an office signing up after a talk; and the per-email number is the one that actually matters, because it protects a stranger's inbox rather than this server. **Four findings came out of writing it rather than planning it.** `InetAddress.getByName` resolves numeric-looking hostnames, so `1.2.3.4.5` is a DNS lookup and not a parse failure — one lookup per login attempt against an attacker-chosen name is a better weapon than the one being defended against, so the dotted-quad form is parsed by hand and `getByName` is reached only for values containing a colon, which it refuses outright. A `Filter` bean is auto-registered with the servlet container *as well as* added to the security chain, so a bean would have spent two tokens per request; the filter is constructed in `SecurityConfig` instead. Boot 4 moved the `RestClient.Builder` auto-configuration into a `spring-boot-restclient` module that `-starter-webmvc` does not pull in, so asking for that bean failed the context — found by doing it. And `ck_action_ends_at_after_created` makes an already-expired fixture row impossible to insert, which is the constraint working: the expiry test asks the same question from the other side, inserting a row that ends soon and querying slightly later. **The `X-Forwarded-For` measurement Rev 18 could not run now exists as a by-product**, exactly as Rev 18 predicted it would: the limiter logs the resolved bucket key next to the raw remote address on every refusal, so twenty-one posts to `/login` on the deployed site answer whether Railway's proxy appends or passes through. **The measurement is in place; the reading has not been taken,** and neither has the HSTS curl — both still need a live origin and a `railway login`. Detaching the `*.up.railway.app` domain is still the better answer and is still a dashboard change. **The provider is Brevo**, on the free-tier limits as they stood: 300 emails a day with no monthly cap, against Resend's 100 a day *and* 3,000 a month, and a Mailgun free tier that sends only from a sandbox domain to five verified addresses until a card is on file and therefore cannot mail a registrant at all. The daily number is the one that decides it, because the digest sends one email per subscriber per day. The cost is named rather than skipped: Resend has the better API and much better delivery logs, and this trades them away in the week whose other job is debugging deliverability. **The welcome email is built**, and the thing to be precise about is that "in the same transaction that sets `email_verified_at`" means the *decision* is made there while the send is still after commit — a third event from the one branch that performs that write, which is what makes "sent once" true with no column recording it, asserted by clicking one link three times. It carries five `ActionSummary` projections, no badges (they live in two element collections and the welcome email is unpersonalized chrome), and **no promise of a daily digest**, because the job does not exist and a welcome email that promises one is a lie in somebody's inbox until it does. **Scope honestly reported:** the daily digest, subscriptions and unsubscribe are *not* built. The digest's query needs ADR-005's two `EXISTS` clauses against a subscription table that does not exist — it is week 6 work and Appendix A lists it under "not in V1" — and unsubscribe needs a column to turn off. Those are one V2 migration and their own revision. The welcome email's links point at `/actions` and `/actions/{id}`, which have no controller yet, so they land on the login form until page 1 ships; the path shape is fixed in `action/ActionPaths` so page 2 has to map it.

- **Rev 20 (2026-10-28):** Review of Rev 19, and **the headline finding is the same mechanism as Rev 19's `?lang=de` bug one layer out: something that reads user input directly, whose validation hole only appears once it is wired.** `LocaleSwitch` builds its links with `build(true)`, which is a promise to Spring that the input is already encoded — and Spring verifies promises. An `IllegalArgumentException` out of a `@ModelAttribute` method is a 500 on *every* page, and the error dispatch hits `BasicErrorController`, which the same advice covers, so the error page throws again. **What the measurement corrected was the trigger.** The obvious candidate, `?x=100%`, never arrives — Tomcat 11 rejects it itself, along with a raw space, `|`, `{`, `^`, `\`, `"` and `<`. What sails through with a 200 is an ordinary `=` inside a value, because RFC 3986 allows it in a query and `UriComponentsBuilder` then refuses it as `QUERY_PARAM`: base64 padding, a nested URL, a JWT. Both ends are asserted, one over a raw socket because every HTTP client in the way sanitises the input under test. Nothing in the flow reaches it today only because `SignedTokenService` pads nothing. **Three other findings landed, and one did not survive contact.** `@ControllerAdvice(annotations = Controller.class)` would have scoped the advice away from the future JSON API — except it excludes nothing, since `@RestController` is meta-annotated `@Controller` and the predicate matches meta-annotations; `basePackages` cannot express the split under package-by-feature either, so the advice stays unscoped with the reasoning written down and the redirect hazard pinned by a test. The HSTS assertion no longer compares Spring's exact header string, whose space before the semicolon is a formatting quirk rather than policy — it compares the directive set, which still fails for every change that is the decision. `@Size` lost its `max` so an over-long password produces one message instead of two, and the message is the byte-accurate one. A signed-in visitor is redirected away from `/register`. **And `/privacy` exists**, because a GDPR consent whose policy link lands on a login form is not a consent that can be defended; the text is a truthful stub stating what registration stores, and the legal document is still owed. 149 tests, up from 139.
- **Rev 19 (2026-10-28):** **Registration has a web layer**, which is what stood between this project and a real end-to-end signup: the controllers behind pages 6, 7 and 8 of the frontend brief, the form object and validation behind page 6, and the language switcher ADR-008 has been owed since the clamping resolver was written. 139 tests, up from 106. **The templates are unstyled structural placeholders and the design is not built** — the brief is addressed to a frontend contributor and sections 5 and 7 are theirs. A first pass did write a stylesheet and a colour and type system; it was deleted, because deciding the brand's typography from the backend is a decision made in the wrong place. What the templates carry is what the backend depends on, each with a comment at the top saying what must survive a redesign: `th:action` for the CSRF input, the `th:field` bindings, the three result states, the consent checkbox inside its label, and page 8's recovery paragraph out in the open rather than behind a disclosure. **The decision that most of it exists to protect is that the two registration outcomes are indistinguishable from outside.** `RegistrationService` already equalises the ~100ms; the controller ignores its return value entirely, and the test compares the status, the `Location` and the flash map of the two rather than trusting a comment. Not every header — `Set-Cookie` differs by session id — which is why the test is named for what it compares. **Three findings came out of building it rather than out of planning it.** Thymeleaf renders a bound checkbox as `id="privacyAccepted1"` — a checkbox can be one of many sharing a name — so the hand-written `for="privacyAccepted"` on the GDPR consent dangled, and the only symptom would have been that tapping the consent text on a phone did nothing; the label wraps the input instead, which needs no id at all. Wiring `LocaleChangeInterceptor` opened a hole in the clamping resolver: the interceptor calls `setLocale` directly and never goes near `parseLocaleValue`, so `?lang=de` set German, wrote a `de` cookie, and threw `NoSuchMessageException` on the next label — a 500 reachable from the address bar, now closed by clamping both doors. And `@Size(max = 72)` on the password counts characters where BCrypt truncates at 72 **bytes**, so a forty-character Bulgarian passphrase passed the form and would have reached `RegistrationService`'s `IllegalArgumentException` — a 500 for Cyrillic users only. **One duplication removed:** `RegistrationEmailListener` held private copies of `/verify-email` and `/login` with a comment calling them "the specification of what the controllers must be called"; the controllers now exist, and all four files read `shared/AuthPaths`, so there is no longer a side that can change alone. **Scope honestly reported:** the flow works end to end and the pages it links out to do not exist — `/` and `/privacy` are pages 1 and 5 of the brief and currently resolve to the login redirect rather than to themselves, which is wrong for the consent link and will stay wrong until page 5 lands. There is no stylesheet, no font choice, no fragment library and no mobile layout; the UI copy on pages 6 and 7 is placeholder editorial in both bundles, unlike page 8's, which ADR-002 specifies.
- **Rev 18 (2026-10-28):** **The HSTS policy settled as a decision rather than inherited as a default** (ADR-002). The header the application emits is `max-age=31536000 ; includeSubDomains`, measured rather than assumed, and `SecurityHeadersTest` now asserts the whole value instead of the presence of `max-age` — a Spring Security upgrade that changes the policy now fails the build rather than changing the site silently. `includeSubDomains` is kept for the reason that is easy to state backwards: the apex is protected either way, and what the directive actually closes is an attacker serving `http://anything.5goodmins.com` and *planting* a `Domain=.5goodmins.com` cookie the apex then accepts — `Secure` stops a cookie leaking out, not one being pushed in. `preload` is refused as the one-way door: submission is baked into browser binaries and removal takes release trains, after which every subdomain is HTTPS or unreachable. And the shared-suffix worry is dismissed with the mechanism: HSTS pins the host in the request and below, so serving it from `*.up.railway.app` pins that hostname and never `up.railway.app`. **One half of the production question is answered and the other is not.** Cloudflare is not adding HSTS itself — its own 502 page, generated end to end at the edge, carries none — so the origin's header is what browsers get and nothing is masking the answer. Whether the origin emits it at all still needs the curl, which is also still unrun: the Railway origin was returning 502 through Cloudflare on 2026-10-28, so there was nothing live to ask. **Two corrections to Rev 17 fell out of trying to run that curl.** It cannot settle the `X-Forwarded-For` half at all — the HSTS half is self-reporting, but nothing reports the resolved client address back: there is no echo endpoint, Tomcat 11's `RemoteIpValve` logs only its `invalid*` warnings, and the access log writes into the container rather than Railway's log stream, so the cheapest instrument turns out to be the rate limiter's own log line and the verification Rev 17 put *before* the code comes free *with* it. And the question is dissolvable rather than merely answerable: both branches only bite on the direct-origin path, which is also the path around Cloudflare's WAF, so detaching the `*.up.railway.app` domain closes it either way — a dashboard change, no code.
- **Rev 17 (2026-10-27):** **The Bucket4j keying decision, which was blocking the rate limiting on `/login` and `/register` and was recorded nowhere** (ADR-002). The key is `request.getRemoteAddr()`, with Cloudflare's published ranges added to `server.tomcat.remoteip.trusted-proxies`. Two parts, and neither is the obvious one. `trusted-proxies` rather than `internal-proxies`, because Spring Boot gives `internal-proxies` a **default** — every private range, Railway's own hop among them — and setting that property *replaces* the default rather than extending it, so the fix that looks right breaks the hop it depends on; `trusted-proxies` is empty by default and adds. And `getRemoteAddr()` rather than `CF-Connecting-IP`, reversing half of the standing recommendation: that header is unforgeable only when the request really came through Cloudflare, and a request sent straight to the `*.up.railway.app` origin carries whatever the client wrote in it, so every forged value is a fresh bucket and the limit is bypassed by changing one header. `RemoteIpValve` already does the "is the peer Cloudflare" check for `X-Forwarded-For`, and after it runs the original peer is no longer readable. Recorded with the mechanism traced through the valve's right-to-left walk, the one hop still to verify with a curl — whether Railway appends to `X-Forwarded-For` or passes it through, the same request that settles the open HSTS question — and the two failure modes, both of which fail closed: a stale Cloudflare range list makes the limit global rather than absent, and an IPv6 client needs keying on the `/64` prefix rather than the `/128`. No code yet: this unblocks the work rather than doing it.
- **Rev 16 (2026-10-19):** The `EmailSender` and the verification email, built to the specification Rev 14 wrote for them — plus four framework findings that only appear when you run it. **ADR-005's mechanism is now code:** `shared/email` holds the interface, the SMTP sender for Mailpit, the composer, and the two template resolvers; `user/RegistrationEmailListener` is the `@TransactionalEventListener(AFTER_COMMIT)` that hands each send to a bounded mail pool, and `register()` publishes on **both** outcomes and calls no sender. `RegistrationOutcome.Registered` no longer carries the token, which was only ever there because there was nowhere to send it. **Rev 14's claim about the default executor was wrong:** Boot provides `applicationTaskExecutor` and `@Async` uses it, so the hazard was never a thread per call but an unbounded queue — and defining a named pool *removes* Boot's, because that auto-configuration is `@ConditionalOnMissingBean(Executor.class)`, which `spring.task.execution.mode=force` is one line to prevent. A `@Configuration` class with `@Bean` methods also cannot implement `AsyncConfigurer` under Boot 4; the context refuses to start. **And there was no `MessageSource` at all:** Boot's auto-configuration requires `messages.properties` to exist, which ADR-008's bundle layout deliberately does not have, so it is now declared (ADR-008). **Two corrections from review, both measured rather than argued:** the mail pool was written as core 1 / max 2 / queue 50 and described as two threads, which is a pool that does not exist — `ThreadPoolExecutor` only grows past its core size when the queue is full, so the second thread would appear on the fifty-first simultaneous send and never otherwise; it is now core 1 / max 1. And `@Async` was replaced by an explicit `executor.execute(...)`: with the annotation, a full queue throws at submit time *inside* Spring's transaction synchronization, which logs it as an anonymous "afterCompletion threw exception" stack trace with no account id — the listener's catch and an `AsyncUncaughtExceptionHandler` both only cover failures inside the task. Found by saturating the queue and reading the log. **A third:** making the `MessageSource` strict needed a locale resolver to match, or the first page would have been a 500 for any browser not set to bg or en (ADR-008). **And one review finding that was not about code at all:** the recovery from an email that never arrives — register again, which really is a resend — was real but undiscoverable, because the "check your inbox" page would have said nothing about it. Rev 14 cut the resend form on the strength of a page that did not exist yet, and nothing was tracking that. The copy is now in both bundles and required by the frontend brief (page 8), the two better answers are recorded with their traps rather than left as folklore — resend on a *proved* login attempt, which needs the unverified gate moved to `setPostAuthenticationChecks` because it currently runs before the password is compared, and a transactional outbox as the scale answer ADR-005 still declines to build — and both are in `INTERVIEW-QA.md` §10. **The executor findings are in `INTERVIEW-QA.md` §17**, which is new: the `ThreadPoolExecutor` growth rule, what is actually wrong with Boot's default `@Async` pool, what defining your own silently removes, and where a rejected task's exception ends up. Four stock questions this build answered by accident. **Scope honestly reported:** the welcome email (ADR-005) is not built — it needs the five newest actions and belongs to week 7 — and the "you already have an account" mail cannot offer a password reset, because reset is v1.1, so it offers login. The frontend brief's "Emails (3)" is now four.

- **Rev 15 (2026-10-19):** Two gaps closed. **The ADR-019 CI guard was checking the wrong thing** — it matched a literal `.now()` and let `LocalDate.now(ZoneOffset.UTC)` through, which reads the system clock exactly as much as the no-argument form does, since the zone overload builds a `Clock.system(zone)` internally. It now flags every `.now(` and allows only `.now(clock)`: an allowlist of the one legal shape rather than a blocklist someone has to remember to extend. `new Date(` is in the pattern too, since `java.util.Date` is rejected outright and is the other way to read the clock in place. Checked against a file of deliberate cases, so the allowed forms are known to be allowed rather than assumed. **And `INTERVIEW-QA.md` gained the id-reuse question** (§1), which is the one the registration design most depends on and the one nothing had written down: delete-and-insert revokes the superseded verification token only because the new row gets a new id, Postgres identity sequences never go backwards, and MySQL's InnoDB before 8.0 re-derived `AUTO_INCREMENT` from `MAX(id) + 1` at startup — so deleting the highest row and restarting handed the id straight back. The worked consequence is the unsubscribe token rather than the verification one, because that link is valid for ten years (ADR-005) and a reused id would let a departed user's old digest link unsubscribe whoever inherited their id.
- **Rev 14 (2026-10-19):** Two consequences of Rev 12 that it did not follow through, plus a correction. **The timing oracle Rev 12 closed would have reopened, wider, the moment emails were sent.** Hashing on both registration paths equalises ~100ms; a verification email sent from inside `register()` would add 100–500ms of the mail provider's HTTP API to one path and nothing to the other. ADR-005 now settles the sending mechanism before the sender exists: an application event, a `@TransactionalEventListener(phase = AFTER_COMMIT)` marked `@Async`, and an event published by **both** registration outcomes so the "you already have an account" mail travels the same route as the verification mail. The two annotations fix two different bugs and the distinction matters — `AFTER_COMMIT` stops a link going out for a transaction that later rolls back (the token would sign a user id that was never committed), while `@Async` is what keeps the provider's latency off the response, since an after-commit listener runs on the publishing thread by default. Recorded with its consequences: a failed send rolls nothing back and therefore has to log loudly, the event carries values rather than the detached entity, and `@EnableAsync` with a named executor belongs to this decision rather than to whichever `@Async` arrives first. **The resend-verification form is cut from the MVP**, reversing Rev 8's requirement for one. The same argument as Rev 12's expired case, applied once more: registering again over an unverified row already *is* a resend — it replaces the row and issues a fresh token — and `/register` is rate-limited, so the form would be a second entry point to one operation, needing its own per-address limit, cooldown and daily cap to avoid being a mailbox-flooding tool, in order to save the user retyping a password and a display name. The cost is named rather than waved away: a user whose first email never arrives refills the form, and the password typed the second time is the one the account keeps. **Correction:** Rev 12 was dated 2026-10-15 and was written on the 19th; Revs 8–11 genuinely share the earlier date. `FRONTEND-BRIEF.md` page 8 still described an "expired-with-resend" screen, which would have had the contributor build a page for a flow that no longer exists — it now names the three real result states and the resend's absence.
- **Rev 13 (2026-10-19):** Added **ADR-019 — Time: one injected `Clock`, never `Instant.now()` in place**, which existed only as a line in CLAUDE.md and was therefore being cited from Javadoc as "(CLAUDE.md)". That is a citation pointing at a working-practices file rather than at a decision, and it reads badly to anyone coming to the repository cold. The rule itself is unchanged and was already followed everywhere; what is new is that it is a decision of record with its reasoning attached — every time-dependent rule in this design is interesting only at a boundary seven days away (ADR-002's token lifetime and purge, ADR-005's watermark and lag window), so time read from inside a method is a boundary that can only be tested by sleeping. Also records the two deliberate exceptions (`@CreationTimestamp`/`@UpdateTimestamp` and `DEFAULT now()`, both audit metadata nothing branches on), why the clock is `systemUTC()` rather than `systemDefaultZone()`, and the rejection of a static mutable clock holder. Every `CLAUDE.md` citation in the source is now an ADR number: three to ADR-019, and `AppUserDetails`'s entities-never-leave-the-service-layer to ADR-013. The rule is enforced by a grep step in CI rather than asserted — ArchUnit would express it and the package-dependency rules better, but its JUnit integration targets Platform 1.x against this project's JUnit 6, which is an unverified compatibility question in exchange for something two lines of shell already do.
- **Rev 12 (2026-10-19):** Three corrections to Rev 11, found in review. **The registration form had a timing oracle.** Both outcomes render the same page, which is what stops the form answering "does this address have an account?" -- but `AddressTaken` returned without hashing anything while the registering path spent ~100ms in BCrypt first, so a stopwatch recovered the answer the page withheld. The password is now encoded before the lookup and discarded on the refusing path. It is the same defence `DaoAuthenticationProvider` uses at login, where it hashes a dummy password for users it did not find, and it is asserted by counting `encode` calls rather than by measuring a duration. What remains unequal is the writes -- insert, delete-then-insert, or nothing -- a millisecond against a shared hundred. **The resend-verification path after expiry is deleted rather than built.** Because the token's lifetime and the purge window are deliberately the same seven days, an expired link names a row already past the purge cutoff: a resend inside that window would issue a fresh seven-day token against a row that 03:30 is about to delete, and the only fix for *that* would be pushing `created_at` forward on every resend, which is a way to keep a squatted address alive indefinitely. `Expired` and `NoSuchAccount` therefore collapse into one `DeadLink` whose answer is "register again" -- which already *is* a resend, since registering over an unverified row replaces it and issues a new token. ADR-002's resend form keeps its other job, the user who never received the first email while it was still valid. One consequence: `TokenVerification.Expired` now carries the user id, because an expired token still has to be looked up -- someone who confirmed on day one and re-clicks on day nine must be told they are already confirmed, not sent round a loop ending at "check your inbox" for an email `AddressTaken` never sends. **And "exactly one instance" is false during a deploy.** Railway health-checks the new container before draining the old one, so two processes briefly run the same schedule on every deploy, not only when someone scales up. A deploy straddling 03:00 double-sends the digest. Written down in the README with the operational rule -- do not deploy near the job times -- and it is the argument for the lock landing with the digest rather than after it.
- **Rev 11 (2026-10-15):** ADR-002, registration written rather than described, and one decision in it that the earlier text did not contain. "Re-registration over an unverified row is allowed" is implemented as **delete-and-insert, not update-in-place**, because updating leaves the previous occupant's verification token valid — same id, same key, same purpose. The damaging direction is the quiet one: if the real owner registers first and someone else re-registers over the row, the owner's own link then confirms an account whose password belongs to the stranger. Replacing the row makes the superseded token resolve to nothing, which is revocation without a revocation list — the only kind available when there is no token table. The DELETE must be flushed before the INSERT or Hibernate reverses them and uq_app_user_email rejects a case that is meant to work; that ordering is asserted against a real PostgreSQL rather than mocked. Three further specifics: the seven-day purge window is read from `TokenPurpose.EMAIL_VERIFICATION.ttl()` rather than written again, so ADR-002's "the two must match" is structural; passwords are capped at **72 bytes, not characters**, because BCrypt hashes 72 bytes and Cyrillic costs two each, so a thirty-seven-character Bulgarian passphrase already exceeds a limit an ASCII-minded form would never notice; and two simultaneous registrations for the same free address are left to uq_app_user_email, which means no duplicate row can exist and one of the two sees an error page — catching it in the service is impossible as written, because a constraint violation marks the transaction rollback-only. `@EnableScheduling` is on from here (ADR-012), carrying its single-instance assumption; the purge is the one job that does not care, since running it twice deletes nothing the first pass left.
- **Rev 10 (2026-10-15):** ADR-002, the signed-token mechanism written rather than described. Five decisions the Rev 8 text left implicit in "HMAC over user id and expiry": the purpose is signed too and is supplied by the verifying endpoint, because one key covers every emailed link; the lifetime hangs off the purpose (seven days for verification, ten years for unsubscribe, which ADR-005 makes a legal requirement rather than a preference); the signature is checked before the expiry is read; `app.token.secret` is required with no default and no startup-generated fallback, both rejected for named reasons; and password reset is explicitly **not** yet able to share the mechanism, because it is the one of the three uses that is not idempotent and needs the payload bound to the current password hash first. Also added `shared/TimeConfig`, a `Clock` bean, so that nothing reads the time from inside itself.
- **Rev 9 (2026-10-15):** ADR-004 corrected: **Railway provisions PostgreSQL 18**, so local, CI and Testcontainers are pinned to 18 — this supersedes "pinned to 17" wherever Rev 7 says it. Rev 7's premise, that Railway offered only 16 and 17 and that 18 existed there as community forks, was wrong; the code had already moved to 18 and the documents had not. The decision itself never changed — match the deployed major version, checked with `SELECT version();` — only the number it resolves to. The 18 `VIRTUAL` generated-column default and the moved volume path are now live facts of the setup rather than warnings about a future upgrade, which makes the explicit `STORED` in `V1__` load-bearing; the full-text search tests passing against 18.6 in CI are the evidence it takes effect.
- **Rev 8 (2026-10-15):** ADR-002, two gaps named rather than left implicit. The resend-verification form needs a per-address and per-IP rate limit, a cooldown and a daily cap: it has to answer identically for every address, which is what stops it being an enumeration oracle, and that same property lets it email-bomb a mailbox its user does not own. And a ban does not end a session already in progress — the banned user keeps access until it expires, which is acceptable only because the same transaction rejects their published actions, so harm to others stops at once; server-side invalidation arrives with DB-backed sessions. Neither is a change of decision; both are consequences of ADR-002's in-memory sessions and mandatory verification that the MVP accepts knowingly.
- **Rev 7 (2026-10-11):** Written against the code rather than ahead of it, and corrected by it. ADR-016: the `POSTER` threshold counts `published_at IS NOT NULL`, not `status = 'PUBLISHED'` — current state cannot answer a historical question under a cyclic graph. ADR-013: `ddl-auto=validate` does not compare lengths, defaults or indexes either, measured rather than assumed; `@Column(length = ...)` and `unique = true` are documentation under `validate`. ADR-005: dev mail resolved as Mailpit behind an `EmailSender` interface, so the dev and production paths differ in one class. ADR-004: Railway's official Postgres is 16/17 and 18 is community-only, so everything is pinned to 17; added the Postgres 18 `VIRTUAL` generated-column default and the volume-path move as the two version-coupled traps. ADR-014: JUnit 6 not 5, the moved `@DataJpaTest` package, per-slice test starters, the non-generic `PostgreSQLContainer` of Testcontainers 2.x, and what a singleton container actually requires. Appendix A: `password_hash` 255 (Argon2 needs 105), `website_url` 500 to match the entity, `btrim` in the name and title checks. Entity invariants: no `setStatus`, `setPublishedAt`, `setId`, `setCreatedAt` or `setUpdatedAt` anywhere — state moves only through the ADR-016 transitions.
- **Rev 6 (2026-10-02):** Written alongside the first migration, and corrected by it. ADR-016: `HIDDEN` removed (four states, not five); the admin graph `PENDING`/`PUBLISHED`/`REJECTED` is now cyclic in every direction; restoration is a status change, never delete-and-recreate; added a ten-per-day submission quota and clarified that promotion to `POSTER` is manual with the three approved actions as a floor rather than a trigger. ADR-011: the external URL is optional, both URL columns nullable and paired by a `CHECK`, the unique constraint covers every status, duplicates are refused at the form and never stored as rows, and the check-then-act race is documented. ADR-005: a restored action is not re-announced, and why re-stamping `published_at` was rejected. ADR-002: a ban disables the account *and* rejects its published actions. §2: pure join tables are exempt from the `created_at` convention. Appendix A updated to match `V1__`.
- **Rev 5 (2026-10-01):** Added ADR-018 (Maven, Spring Boot 4.1.x, Java 21, YAML configuration) — this supersedes "Spring Boot 3" wherever earlier revisions say it. ADR-009: subscription matching is now a plain set intersection chosen by the user; `NATIONAL` and `INTERNATIONAL` are pre-ticked defaults rather than an automatic rule. Seed data resolved as an ordinary versioned migration with reserved-domain authors and relative dates. Added Appendix A (contents of `V1__`).
- **Rev 4 (2026-09-21):** Added schema conventions (identity PKs, `timestamptz`, `app_user` naming, named constraints, constraint-vs-Bean-Validation split, FK indexes, ordering tiebreakers). Added ADR-016 (action lifecycle and poster approval) and ADR-017 (categories and action types). Removed `unaccent` from ADR-007 — `STABLE`, therefore illegal in a generated column, and useless for a diacritic-free language; documented explicit weights. ADR-009: `ONLINE` → `INTERNATIONAL`; one to three regions per action via a join table; `NATIONAL`/`INTERNATIONAL` combinable with oblasti. ADR-010: removed the `poster_verified` boolean (duplicated the role). ADR-005: digest pinned to `published_at`, five-minute lag window, `EXISTS` matching, `DEFAULT now()` for new registrants, welcome email, stored locale. ADR-002: mandatory email verification; rate limiting instead of lockout. ADR-015: 404 only. Hashtags, avatars and quarterly re-confirmation deferred; `DONATION`, `EVENT`, `VOLUNTEERING`, `PUBLIC_CONSULTATION` cut. Frontend contributor / PWA scope added as open.
- **Rev 3 (2026-09-07):** Added ADR-015 (account deletion). Resolved ADR-004 to Railway Hobby and ADR-008 to Bulgarian-first UI with single-language content. Domain `5goodmins.com`. Image storage confirmed as Postgres `bytea` behind an interface.
- **Rev 2 (2026-09-07):** Added ADRs 004–014. Corrected hosting (free tiers unusable for scheduled email). Specified image storage, email delivery, region model. Flagged the Bulgarian FTS gap. Removed the `Organization` entity. Added a read-only JSON API. Added seed data as an MVP requirement. Timeline extended to 9 weeks.
- **Rev 1:** Initial stack and scope decisions.

---

## 9. Appendix A — contents of `V1__create_user_and_action.sql`

The first migration, in statement order. Flyway wraps the whole file in one
transaction, so a mistake in any statement rolls the rest back — there is no
half-applied state to clean up.

1. `CREATE EXTENSION IF NOT EXISTS pg_trgm;` — needed before the trigram index.
   (`unaccent` is deliberately absent; ADR-007.)
2. **`app_user`** — identity PK; `email` (254, unique, `CHECK (email = lower(email))`);
   `password_hash` (255 — `{bcrypt}` plus a BCrypt hash is 68 characters, but
   `{argon2}` plus a Spring Security Argon2id hash is 105, so the 100 of Rev 6
   would have made changing encoder a schema migration);
   `display_name` (80, required, `CHECK (char_length(btrim(...)) >= 1)` so a name
   of three spaces is refused); `bio` (nullable, `CHECK` ≤ 1000);
   `website_url` (500, nullable, poster profile — a number the entity must
   repeat by hand, because `validate` does not compare lengths; ADR-013);
   `role` (`CHECK IN ('USER','POSTER','ADMIN')`, default `'USER'`);
   `enabled` (ban switch, default true); `email_verified_at` (nullable — no login,
   no mail until set); `poster_verified_at` (nullable, `CHECK` consistent with role);
   `last_digest_sent_at` (`NOT NULL DEFAULT now()` — new users get only what is
   published after they join); `locale` (`CHECK IN ('bg','en')`, default `'bg'`);
   `created_at`, `updated_at`.
3. **`action`** — identity PK; `author_id` FK → `app_user` `ON DELETE CASCADE`;
   `title` (200, `CHECK (char_length(btrim(title)) >= 5)` — the upper bound is
   the `varchar` itself, so it is not repeated); `description` (`text`,
   `CHECK` btrim ≥ 1 and untrimmed ≤ 5000 — two halves, because `text` has no
   cap from its type);
   `action_type` (`CHECK` over the five values);
   `external_url` (2048, **nullable** — a link-less action is valid);
   `normalized_url` (500, nullable, unique as `uq_action_normalized_url`;
   `CHECK` that it is null exactly when `external_url` is — ADR-011);
   `video_url` (nullable, host whitelist enforced in the app);
   `status` (`CHECK` over the four states, default `'PENDING'`);
   `published_at` (nullable, `CHECK` non-null unless `PENDING`/`REJECTED`);
   `ends_at` (`NOT NULL`, `CHECK (ends_at > created_at)`);
   `created_at`, `updated_at`;
   `search_vector` — generated `tsvector`, `simple` config, title weighted A over
   description B (ADR-007).
4. **`action_category`** — `(action_id, category)` composite PK; FK `ON DELETE CASCADE`;
   `CHECK` over the ten categories.
5. **`action_region`** — `(action_id, region)` composite PK; FK `ON DELETE CASCADE`;
   `CHECK` over the 28 oblasti plus `NATIONAL` and `INTERNATIONAL`.
6. **Indexes** — GIN on `search_vector`; GIN trigram on `title`;
   B-tree on `action(author_id)` (Postgres does not index foreign keys automatically);
   partial B-tree on `action(published_at DESC, id DESC) WHERE status = 'PUBLISHED'`
   matching the listing's `ORDER BY` including its tiebreaker;
   B-tree on `action_category(category)` and `action_region(region)`, since the
   composite PKs cannot answer "all actions in this category".

**Not in V1** (later migrations): `subscription`, `report`, `image`,
`completed_action`, and the `V100__seed_demo_data.sql` seed.

Every constraint is explicitly named so the application can tell a duplicate URL
from a duplicate email when it catches `DataIntegrityViolationException`.
