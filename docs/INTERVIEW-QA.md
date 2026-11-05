# Interview Q&A — 5 Good Minutes

Running reference. Every entry is something this project's design actually
justifies, phrased the way I would answer it out loud. Keep it in `docs/`
next to `ARCHITECTURE.md` and add to it as the build goes on.

Marker key:
- **[COLD]** — must be answerable with no thinking time. These come up constantly.
- **[WARM]** — should be able to reconstruct the reasoning in a few seconds.
- **[DEEP]** — only comes up with a strong interviewer; a very good answer to give.

---

## 1. Primary keys and identity

### Q: Why `BIGINT` identity and not UUID? **[COLD]**

Single database, single application instance, browser clients. UUID's real
benefits — client-side id generation for offline or distributed writers, and
idempotent retries where the client supplies the id — are benefits I have no
use for. Its costs are real at any scale: 16 bytes instead of 8 in every
primary key and every foreign key index, and random values that scatter
inserts across the B-tree, causing page splits and half-empty pages instead
of appending to one hot page at the right-hand edge.

The one argument that does apply is URL enumeration — `/actions/41`,
`/actions/42` invites scraping. But published actions are public by design,
and non-public ones must be protected by an authorization check, not by an
unguessable id. Hiding an IDOR behind a UUID is not fixing it.

### Q: Why `GENERATED ALWAYS AS IDENTITY` rather than `BIGSERIAL`? **[WARM]**

`SERIAL` is the pre-Postgres-10 shorthand: a separately owned sequence plus a
column default. Identity is the SQL standard replacement — the column owns its
sequence, permissions follow the table, and `ALWAYS` rejects hand-supplied ids
unless you write `OVERRIDING SYSTEM VALUE`. That stops a manual insert from
colliding with the sequence later.

### Q: Doesn't `GenerationType.IDENTITY` disable Hibernate batch inserts? **[COLD]**

Yes. Hibernate keys every entity in the persistence context by its id, and
with identity the id only exists after the INSERT has executed. So it cannot
queue INSERTs to batch them — each one goes immediately.

It doesn't matter here: my only bulk insert is the Flyway seed migration,
which is plain SQL and never touches Hibernate. If it did matter I would
switch to `GenerationType.SEQUENCE` with `allocationSize = 50` and a matching
`INCREMENT BY 50` on the sequence, so one `nextval` reserves 50 ids.

### Q: What happens if `allocationSize` and `INCREMENT BY` disagree? **[DEEP]**

Primary key collisions, intermittently. Hibernate calls `nextval`, gets `1`,
and assumes it owns ids 1–50. With `INCREMENT BY 1` the next caller gets `2`
and assumes it owns 2–51. The blocks overlap, so you get
`DataIntegrityViolationException` on the PK under concurrency or after a
restart — not reproducible on demand, which is what makes it nasty.

### Q: Your ids have gaps. Is that a bug? **[WARM]**

No. Sequences are non-transactional: a rolled-back insert still burns its id,
and a pooled sequence discards unused ids on shutdown. An id is an identifier,
not a counter. Never use `MAX(id)` to count rows.

### Q: Your registration security depends on ids never being reused. Does it? **[DEEP]**

On Postgres, yes. On MySQL before 8.0, no — and the difference would have been
a live vulnerability rather than a curiosity.

The setup: re-registering over an unverified account deletes the row and
inserts a new one rather than updating it in place (ADR-002). The point is to
kill the previous occupant's verification token. The token is stateless — it
signs a user id and an expiry, there is no token table and nothing to mark as
used — so the *only* thing that revokes it is the id it names ceasing to exist.
Delete-and-insert is revocation by deletion.

That works because the new row gets a new id. Postgres identity columns draw
from a sequence that only ever moves forward: `nextval` is non-transactional,
deleted values are never returned, and a restart does not rewind it.

MySQL's InnoDB before 8.0 held the `AUTO_INCREMENT` counter **in memory** and
re-derived it on startup as `MAX(id) + 1`. Delete the highest row, restart the
server, and the next insert receives the id you just deleted. MySQL 8.0
persists the counter to the redo log, which is what fixed it.

What that would cost here, concretely — and the unsubscribe link is the worse
case, not the verification link:

- A user deletes their account (ADR-015, hard delete). Their row was the
  highest id. The server restarts; the next person to register is handed the
  departed user's id.
- Every digest email ever sent to the first user carries a one-click
  unsubscribe token signed with that id, and those are valid for **ten years**
  (ADR-005 makes the link a legal requirement, so it cannot expire quickly).
- An old link out of the first user's mailbox now unsubscribes the second user.
  Same signature check, same key, same purpose — nothing about the token is
  forged. The identifier simply means someone else now.

Two things worth saying after that. First, this is why "which database" is a
security question and not only a performance one: the design leans on a
guarantee one engine makes and another, until recently, did not. Second, the
general fix if I ever had to run on an engine that reuses ids is to sign
something that is unique per row rather than per id — a random `token_version`
or the row's creation timestamp in the payload — which is the same shape as the
fix ADR-002 already names for password reset, where the signed material binds
to the current password hash.

---

## 2. Time

### Q: `timestamp` or `timestamptz`? **[COLD]**

`timestamptz`, always. Despite the name it does not store a time zone — it
stores an absolute UTC instant and converts on display. Plain `timestamp`
stores wall-clock digits with no zone, so "02:30" is ambiguous twice a year in
Europe/Sofia. Java side is `Instant`, plus
`hibernate.jdbc.time_zone=UTC` so the JVM default zone is irrelevant.
`ZonedDateTime` in Europe/Sofia only at render time.

### Q: Which Java time type maps to what? **[COLD]**

| Java | Meaning | Postgres |
|---|---|---|
| `Instant` | a moment on the UTC timeline | `timestamptz` |
| `LocalDateTime` | wall-clock digits, no zone | `timestamp` (avoid) |
| `LocalDate` | a calendar day | `date` |
| `ZonedDateTime` | moment + zone, for display | — |

Never `java.util.Date`.

### Q: Your entity test compares `Instant`s and fails. Why? **[DEEP]**

`Instant.now()` has nanosecond precision; Postgres `timestamptz` stores
microseconds. Save, reload, `assertEquals` — the trailing digits are gone.
Fix with `truncatedTo(ChronoUnit.MICROS)`, or inject a fixed `Clock` in tests.

### Q: Two rows inserted in one transaction have identical `created_at`. Problem? **[DEEP]**

`now()` returns transaction start time, not wall-clock time, so yes, they are
identical. Two consequences:

1. `ORDER BY created_at DESC` has undefined order among ties, so keyset or
   offset pagination can repeat or skip rows. Every list query carries a
   tiebreaker: `ORDER BY published_at DESC, id DESC`.
2. A watermark batch job can miss rows (see §6).

---

## 3. Schema conventions

### Q: Why is the table called `app_user`? **[COLD]**

`user` is reserved in Postgres — `SELECT user` returns the current role. Using
it means quoting it in every migration and every native query, and the day
someone forgets, the unquoted version does not fail, it silently means
something else.

### Q: Singular or plural table names? **[WARM]**

Singular, matching entity class names and avoiding irregular plurals
(`person`/`people`, `status`/`statuses`). Neither is wrong. What is wrong is
mixing them. I picked one and applied it consistently.

### Q: Why name every constraint explicitly? **[WARM]**

Because the application reads constraint names. Duplicate detection catches
`DataIntegrityViolationException` and has to tell "this action already exists"
from "that email is taken". Auto-generated names like
`action_normalized_url_key` work, but depending on a name the database chose
for you is an accident waiting to break.

### Q: You have Bean Validation. Why also `NOT NULL` and `CHECK` in the database? **[COLD]**

Different jobs, different audiences. Bean Validation produces friendly
localized messages before the database is touched — it is UX. Database
constraints are invariants that hold no matter what code runs: the Flyway seed
migration, a future admin tool, a bug, or me in `psql` at midnight. The
database is the source of truth; the Java layer is a nicer error message.

The cost is duplication — the length limit exists in the migration and in
`@Size`. I keep the Java side in one constants class so at least that side has
one home.

### Q: Why not put business rules in `CHECK` constraints? **[WARM]**

A `CHECK` can only see the row being inserted or updated. Anything that
depends on other rows, other tables, or the current time cannot be expressed
in one, and anything that is policy rather than integrity should be able to
change without a migration. "Only verified posters publish immediately" is
service logic; "status must be one of these four strings" is integrity.

### Q: `CHECK (email = lower(email))` — what is that for? **[WARM]**

It asserts the stored value is already lowercase. Combined with a plain
`UNIQUE (email)`, it gives case-insensitive uniqueness without any of the
alternatives' downsides: `citext` is another extension with awkward JPA
mapping, and a unique index on `lower(email)` only helps if every query
remembers to write `lower()` — one that forgets silently skips the index.
Normalizing in the application and letting the database verify it is the
cheapest correct option.

### Q: So where does the lowercasing actually happen? **[WARM]**

In the registration service, before the save:
`user.setEmail(form.getEmail().trim().toLowerCase(Locale.ROOT))`. The
constraint converts nothing — it refuses. A row carrying `Georgi@abv.bg` is
rejected outright with a named constraint violation.

That is the division of labour I would state in one line: **the application
normalizes, the database verifies.** In normal operation the constraint never
fires. It fires the day a new code path — an admin form, a seed migration, me
in `psql` — forgets to normalize, and then it fails loudly at the moment of the
bug instead of quietly creating a second account for the same mailbox.

`Locale.ROOT` matters: on a JVM with a Turkish default locale,
`"I".toLowerCase()` returns the dotless `ı`, so addresses stop matching
themselves.

---

## 4. Enums and reference data

### Q: `EnumType.STRING` or `ORDINAL`? **[COLD]**

Always `STRING`. `ORDINAL` stores the enum's position, so the day someone
inserts a constant in the middle or reorders the list, every existing row
silently means something different. That is a data-corruption bug with no
error message. The cost of `STRING` is a few bytes per row.

### Q: Why an enum plus a `CHECK` instead of a lookup table? **[WARM]**

The 30 regions change only when Bulgaria's administrative law changes. A
lookup table would add a join to every query, four more files, and an admin UI
nobody needs, and it would lose compile-time safety in Java. If the product
ever goes multi-country, migrating to a table is a day's work — I can name the
trigger that would make me do it.

### Q: Why not a native Postgres `ENUM` type? **[WARM]**

Adding a value is fine, removing or reordering one is painful, and Hibernate
needs extra mapping for it. `varchar` + `CHECK` gives the same guarantee with
plain types, and changing the allowed set is an ordinary migration.

### Q: Why a join table for categories rather than `category1/2/3` columns? **[WARM]**

Numbered nullable columns cannot be queried or indexed sensibly — filtering
means `WHERE category1 = ? OR category2 = ? OR category3 = ?`, and allowing a
fourth means a migration plus touching every query. A join table with a
composite primary key `(action_id, category)` gives uniqueness, cascade
delete, and a single indexable column for free.

### Q: Why not a Postgres array column then? **[DEEP]**

I considered it. An array enforces the maximum of three in a plain `CHECK`,
which a join table cannot do without a trigger, and it avoids a second query
on list pages. I chose the join table because `@ElementCollection` is standard
JPA that I can defend line by line, while the array mapping is
Hibernate-6-specific and would have needed a spike to prove. The array is the
slightly better design; the join table was the better decision for my
constraints.

### Q: Why are the enum constants in English when the product is Bulgarian? **[WARM]**

Identifiers and code are English by project rule — the constant is
`STARA_ZAGORA`, the label comes from `messages_bg.properties` and
`messages_en.properties`. Putting Cyrillic in the database would mean the
display text is stored in the schema, so translating the UI would need a
migration. The enum name is an identifier, not a label.

---

## 5. Hibernate and JPA

### Q: What is dirty checking? **[COLD]**

Inside a transaction Hibernate keeps a snapshot of each loaded entity. At
flush time it compares the entity to its snapshot and issues UPDATEs for
whatever changed. You do not call `save()` to update an existing row — you
change a field inside `@Transactional` and it is written.

### Q: What is the N+1 problem, and how do you find it? **[COLD]**

One query loads N rows, then touching a lazy association on each row runs one
more query per row. Typical symptom: a list page that is fast with 5 rows and
slow with 200.

I find it by turning on `hibernate.generate_statistics` (or `datasource-proxy`)
in dev and reading the query count. I fix it with a fetch join or, on list
pages, by not loading entities at all — a DTO projection selects the columns
the page renders and nothing else.

### Q: Why `spring.jpa.open-in-view=false`? **[COLD]**

Open Session In View is on by default and keeps the Hibernate session open
through view rendering, so a lazy association touched inside a template
quietly issues a query. That hides N+1 until production. Turning it off makes
the same code throw `LazyInitializationException` at development time, which
forces the loading decision into the service layer where it belongs, inside a
transaction.

### Q: What does a JPQL bulk update (`@Modifying @Query`) skip? **[DEEP]**

It is translated straight to SQL, so it bypasses: the persistence context
(already-loaded entities keep stale values and dirty checking can write them
back), `@UpdateTimestamp` and `@Version`, entity lifecycle callbacks, and
cascades. Concretely: an admin "hide" button written as a bulk update leaves
`updated_at` untouched, because Hibernate never inspected an entity and the
column's `DEFAULT now()` only applies on INSERT.

At one row per click I load the entity and set the field instead. If I keep
the bulk query I set `updated_at` in it explicitly, and use
`@Modifying(flushAutomatically = true, clearAutomatically = true)`.

### Q: How do you implement `equals`/`hashCode` on a JPA entity? **[COLD]**

`hashCode` returns a constant (or `getClass().hashCode()`), and `equals`
compares the id, treating two entities with null ids as non-equal unless they
are the same instance. The trap is using a generated id in `hashCode`: an
entity added to a `HashSet` before persist gets one bucket, then the id is
assigned and the object is no longer findable in its own set. Using business
fields breaks the moment they are editable.

### Q: Why `ddl-auto=validate`? **[COLD]**

Flyway owns the schema. Hibernate's only job at startup is to confirm the
entity mappings match the tables — if they have drifted, the application fails
to start rather than producing wrong SQL at runtime. Anything other than
`validate` means two systems fighting over the same database.

### Q: What does `validate` actually check? **[DEEP]**

Column existence and type. Not much else — and I know because I shipped a
mismatch and went looking. My entity had `@Column(length = 500)` on a column the
migration declared `varchar(2048)`. The context started normally and a
620-character value was stored without complaint.

So `validate` does not compare lengths, defaults, indexes, or the contents of a
`CHECK` constraint — adding a value to an enum's `CHECK` is invisible to it.
Under `validate`, `length` and `unique = true` on `@Column` are
DDL-generation metadata that nothing reads: they are documentation, and
documentation can be wrong. The entity and the migration have to be changed
together, by hand.

The useful follow-up is what *would* catch it: a repository test against the
real database, which is also the only thing that catches a missing `package`
declaration (§16).

---

## 6. The digest job

### Q: Explain the watermark pattern. **[COLD]**

One column, `app_user.last_digest_sent_at`. Nightly, for each subscriber:
select actions published after that timestamp matching their categories and
regions; if non-empty, render and send; set the watermark to the cutoff *only
after a successful send*. No queue table, no per-user rows, idempotent, and if
the job fails for two days the next run picks up everything missed.

### Q: Why `published_at` and not `created_at` or `updated_at`? **[COLD]**

`created_at` is when the row was inserted, which for an action awaiting
approval can be days before it is public. A user whose digest ran in between
has a watermark later than `created_at`, so the action never matches and is
invisible to them forever.

`updated_at` is worse: a poster fixing a typo three weeks later would push the
action back above every watermark and re-send it to the entire platform.

`published_at` is set exactly once, at approval, and never touched again.
That property is what makes it the right key.

### Q: How can a watermark job miss a row even with `published_at`? **[DEEP]**

`now()` is transaction start time. A publish transaction can start at 02:59:59,
the digest job at 03:00:00 cannot see the uncommitted row, sets the watermark
to 03:00:00, and the row then commits with `published_at = 02:59:59` — forever
below every future watermark.

Fix: a lag window. The job only considers rows with
`published_at <= now() - interval '5 minutes'` and sets the watermark to that
same cutoff, not to `now()`. No transaction stays open for five minutes, so
nothing slips through. The cost is up to five minutes of latency in a job that
runs once a day.

### Q: Where does this design stop scaling? **[COLD]**

The `@Scheduled` annotation runs in the application process, so it assumes
exactly one instance. Two instances means every user gets two digests. It is
documented as a known constraint with the fix named: ShedLock or a database
advisory lock, or moving the digest to a separate worker deployment. Knowing
where my own design breaks is more useful than pretending it doesn't.

### Q: An admin rejects a published action by mistake and restores it. Does it get emailed? **[WARM]**

Usually yes, occasionally no, and the "no" is deliberate. `published_at` is
written once and never changed, including on restoration, so the action keeps
its original timestamp. If the mistake is caught the same day, that timestamp
is still above everyone's watermark and the action goes out in the next digest
normally. If the round trip spans a digest run, the action is already below
every watermark and nobody is emailed about it — it stays fully visible on the
site, in search, and at its permanent URL.

The alternative is re-stamping `published_at` on restoration, which would mail
the action to the entire platform every time an admin reverses a decision —
the same failure as keying the digest on `updated_at`. I would rather lose one
email than re-send to every subscriber, so I accepted the gap and documented
it.

### Q: The server was down for two days and the job never ran. What do users get? **[COLD]**

Everything they missed, in one email. The watermark advances only after a
successful send, so after a two-day outage `last_digest_sent_at` is still where
it was, and the next run selects every action published since then. Nothing is
lost, no catch-up logic exists, and the job is safe to re-run at any time —
that idempotence is the whole reason there is no queue table.

### Q: Why an HTTP email API rather than SMTP? **[WARM]**

Some hosts block outbound ports 25/465/587 outright, an HTTP API returns
delivery status instead of a fire-and-forget handoff, and the provider manages
SPF/DKIM. Plus a one-click unsubscribe link that works without login, which is
a legal requirement in the EU, not a nicety.

---

## 7. Search

### Q: Why Postgres full-text search rather than Elasticsearch? **[COLD]**

At a few hundred actions, Elasticsearch is a second datastore to run,
synchronize, and explain, for a dataset that fits in memory. The honest
version: at this volume `ILIKE '%term%'` would perform identically. I built
FTS because it is the right shape as content grows and because I wanted to
learn it — not because the data forced it. I would rather say that than invent
a performance justification.

### Q: What is a `tsvector`? **[WARM]**

A preprocessed, sorted list of the distinct lexemes in a document, each with
its positions and optional weight — an inverted index for one row. Comparing
it to a `tsquery` with `@@` is then a sorted-list intersection rather than a
substring scan.

### Q: Why is it a generated column rather than a trigger or application code? **[WARM]**

A generated column is recomputed by Postgres on every insert or update of the
source columns, so it cannot drift from the row. A trigger achieves the same
with more moving parts; application code fails the moment anything writes
outside the application — the seed migration, an admin fix, a bulk update.

### Q: Why the `simple` configuration instead of a language one? **[COLD]**

Postgres ships Snowball stemmers for about two dozen languages. Bulgarian is
not one of them — check with `SELECT cfgname FROM pg_ts_config`. Using the
English configuration would apply English stemming rules to Bulgarian, which
is worse than none. `simple` tokenizes and lowercases without stemming.

The consequence is real: Bulgarian is heavily inflected, so "петиция",
"петиции" and "петицията" are three separate lexemes and do not match each
other. That gap is what `pg_trgm` covers — a trigram similarity index on the
title catches inflected forms and typos.

### Q: You specified `unaccent` in your design doc and then removed it. Why? **[DEEP]**

Two reasons, found in that order. First, generated column expressions must be
`IMMUTABLE`, and `unaccent()` is `STABLE` because it reads a dictionary file
that could change — so Postgres rejects the `ALTER TABLE` outright. The usual
workaround is wrapping it in your own `IMMUTABLE` function, which lies to the
planner: if the rules file ever changes, the stored index disagrees with new
queries.

Second, and more decisive: `unaccent` strips diacritics, which is a
Latin-script problem. Bulgarian has no diacritics. It would only have helped
with Latin transliteration, which is a different feature and partly covered by
trigrams anyway. So I removed it rather than working around it.

### Q: What is `setweight` doing? **[WARM]**

It tags lexemes with a label A–D so `ts_rank` can score them differently.
Title lexemes get A, description gets B, so an action with the search term in
its title outranks one that merely mentions it in the body. Without weights,
ranking is just term frequency, which favours long descriptions.

### Q: What are function volatility categories? **[DEEP]**

`IMMUTABLE` — same arguments always give the same result, forever, with no
database access (`lower()`, `to_tsvector('simple', x)`). Can be used in index
expressions and generated columns, and constant-folded by the planner.

`STABLE` — consistent within a single statement, but can depend on database
state or settings (`now()`, `unaccent()`, the one-argument `to_tsvector`).

`VOLATILE` — can change on every call (`random()`, `nextval()`).

The practical rule: index and generated-column expressions require
`IMMUTABLE`, because the stored value has to stay correct forever. That is
also why the one-argument `to_tsvector(x)` is rejected — it depends on the
`default_text_search_config` session setting.

---

## 8. Indexes

### Q: What is a B-tree, and why does key order matter? **[COLD]**

A balanced tree of 8 KB pages holding keys in sorted order. Hundreds of keys
per page means millions of rows are reachable in three or four page reads.
Sequential keys always land at the right-hand edge, so one hot page stays in
memory and pages fill completely. Random keys land anywhere, forcing page
splits that leave half-empty pages and a larger index with worse cache
behaviour.

### Q: What is a GIN index and when do you use it? **[WARM]**

GIN is a Generalized Inverted Index: instead of indexing whole values, it
breaks each value into items and stores, for each item, the sorted list of row
pointers containing it. That is exactly what you need when one column holds
many searchable items — lexemes in a `tsvector`, trigrams in a string,
elements in an array, keys in `jsonb`.

Trade-offs: much faster than a B-tree for "contains this item" queries,
slower to update (one row touches many index entries), and larger. GiST is the
alternative — smaller and cheaper to write, but lossy, so it rechecks
candidates against the heap. For a read-heavy catalogue, GIN.

### Q: What is `gin_trgm_ops`? **[WARM]**

An operator class: it tells GIN *how* to decompose the indexed value. The
default for text has no idea what to do with a `varchar`. `gin_trgm_ops`, from
`pg_trgm`, splits it into three-character sequences — "петиция" becomes
"пет", "ети", "тиц" and so on. Two strings sharing many trigrams are similar,
which is what powers `similarity()` and `%`, and it also makes unanchored
`ILIKE '%term%'` indexable, which a B-tree cannot do.

### Q: Does Postgres index foreign keys automatically? **[COLD]**

No — it indexes primary keys and unique constraints, not the referencing side
of a foreign key. Without an explicit index, every "all actions by this
author" query and every cascade delete does a sequential scan. Unindexed
foreign keys are a classic production slow-query cause.

### Q: Why is your listing index partial? **[DEEP]**

`CREATE INDEX ... (published_at DESC, id DESC) WHERE status = 'PUBLISHED'`.
The listing only ever shows published actions, so indexing the rest wastes
space and write time. The column order matches the query's `ORDER BY`
exactly, including the tiebreaker, so the sort comes free from the index scan.

---

## 9. Deletion and integrity

### Q: What happens to content when its author deletes their account? **[WARM]**

Hard delete — the user, their subscriptions, their actions and images all go.
GDPR Art. 17 requires erasure of personal data, not of everything a person
authored, so keeping anonymized actions would arguably be compliant. I chose
the simpler and slightly over-compliant option because the user's intent is
that leaving means leaving, and there are no other stakeholders in that
content yet. The cost is broken bookmarks and digest links, which return 404
through the normal error page.

### Q: Why is cascade delete in the database but `updated_at` in Java? **[COLD]**

The dividing line is what breaking the rule costs. A missed cascade leaves
orphan rows pointing at nothing — broken referential integrity, and here also
personal data surviving an erasure request. That must hold whoever does the
delete, so the database enforces it. A stale `updated_at` after a manual fix
is slightly inaccurate audit metadata that harms nobody, so it is not worth a
trigger per table and behaviour invisible to a Java reader.

### Q: Your `POSTER` threshold is "three approved actions". How do you count that? **[DEEP]**

Not with `status = 'PUBLISHED'`, which is what I wrote first and had to fix.

The state graph is cyclic: an admin can move an action `PUBLISHED → REJECTED`
and back, and a poster can withdraw and re-publish their own. So `status` is the
*current* state, while the threshold asks a *historical* question — has an admin
ever approved this action? Counting the current state means an author who
published five actions and withdrew two counts as three, and one whose action
was later taken down loses credit for work that really was approved. The count
goes down over time, which a floor should never do.

The fix was already in the schema. `published_at` is written exactly once, at
the first transition to `PUBLISHED`, and never cleared — that is the immutability
the digest watermark depends on. So it is also the record of "has been published
at least once", and the query is
`countByAuthorIdAndPublishedAtIsNotNull`. The one-directional `CHECK`
(`status IN ('PENDING','REJECTED') OR published_at IS NOT NULL`) exists for the
same reason: a `PENDING` row may legitimately carry a `published_at` from an
earlier publication.

The general lesson is the part worth saying out loud: a mutable status column
cannot answer a question about the past, and if a timestamp already records the
transition, that timestamp is the answer.

### Q: Why not `cascade = REMOVE` on the JPA side as well? **[WARM]**

Because then two systems own the same rule and can disagree. JPA cascade also
loads every child entity into memory to delete them one row at a time, where
`ON DELETE CASCADE` is one statement in the database. I declare it in the
Flyway migration and use plain associations in JPA.

---

## 10. Auth and accounts

### Q: Sessions or JWT? **[COLD]**

Sessions, for a first-party browser client: revocable server-side, no
token-in-localStorage XSS exposure, and less machinery. JWT when I add a
client that cannot use cookies well, such as a mobile app. The answer is the
tradeoff, not the technology.

### Q: What is the downside of in-memory sessions? **[WARM]**

Every deploy logs everyone out, and horizontal scaling breaks without sticky
sessions. Correct for an MVP, documented as a constraint; Spring Session with
JDBC is a two-line change when it matters.

### Q: Rate limiting or account lockout? **[DEEP]**

Rate limiting, with Bucket4j on `/login` and `/register` only. Lockout after N
failures turns the login form into a denial-of-service weapon: anyone who
knows a user's email can lock them out on purpose. It also needs a counter
column, a reset path, an unlock email, and a decision about lockout duration.

Rate limiting throttles the attacker without giving them a way to harm the
account. If credential stuffing became a real problem I would add exponential
backoff per IP and per account plus a CAPTCHA, not permanent lockout.

### Q: Why does registration require email verification? **[WARM]**

The address is the digest destination, so an unverified or mistyped one
produces bounces and spam complaints, which damages sender reputation and
sends every future digest to spam. It also raises the cost of fake accounts.

It is not a DDoS defence — a flood of traffic never needs to finish
registering. DDoS is handled by Cloudflare in front of the domain.

### Q: How is the verification link implemented? **[WARM]**

A signed, expiring token in the URL — HMAC over user id plus expiry — not a
token table. Same mechanism as the one-click unsubscribe link, so it is one
piece of infrastructure used twice. Unverified accounts are purged after seven
days, and re-registration over an unverified row is allowed so nobody can
squat someone else's address.

### Q: What happens if the verification email fails to send? **[DEEP]**

Four things, in this order.

The account stays. It was committed before the send was attempted — that is
what `@TransactionalEventListener(AFTER_COMMIT)` is for — and undoing a correct
registration because a mail provider had a bad minute would be the wrong
trade. The send is also off the request thread, so the user is not waiting on
it either way.

The failure is logged with the account id. Two different failures, both named:
the send that threw, and the send that was never attempted because the mail
queue was full. The second one is the interesting half — I had it with
`@Async` first, and when I filled the queue the rejection turned out to be
thrown at *submit* time, inside Spring's transaction synchronization, where it
is logged as an anonymous "afterCompletion threw exception" with no account id,
because the event is out of scope by then. Neither the listener's catch nor an
`AsyncUncaughtExceptionHandler` sees it. So I submit to the executor by hand
and catch `TaskRejectedException` where the event still exists.

The user recovers by registering again, and **the page tells them to.** There
is no resend form, deliberately: registering again over an unconfirmed address
replaces the row and issues a fresh link, so it already is a resend, and a
separate form would be a second entry point to one operation needing its own
per-address limit, cooldown and daily cap. The honest part of that answer is
that the decision was only sound because of copy on a page — "check spam, or
register again with the same address" — and when I made it, that page did not
exist and nothing was tracking it. A recovery the user cannot discover is not a
recovery.

And at scale I would move to a transactional outbox: write the pending email to
a table in the same transaction as the user, let a job send it with backoff and
mark it done. The outage becomes invisible and survives a restart. It costs a
table, a job, a retry policy and cleanup, which is not worth it at this volume
— but naming it is the difference between "I did not build retries" being a
decision and being an omission.

### Q: Could you resend the link when an unverified user tries to log in? **[DEEP]**

Yes, and it is the best version of this — no new form, and it costs a correct
password to trigger, so it cannot be used to flood someone else's mailbox. It
is not free, though, and the reason is interesting.

The unverified check currently lives in `loadUserByUsername`, above Spring
Security's own status checks, so that every reason not to log in leaves through
one `UsernameNotFoundException` and an unverified account is indistinguishable
from an unknown one. Resending requires knowing the password was right, so the
gate has to move to where that is known: `setPostAuthenticationChecks` on the
provider, which runs after `additionalAuthenticationChecks` — the password
comparison.

The naive version is the one to be able to reject out loud: map
`email_verified_at IS NULL` onto `isEnabled() == false` and let Spring Security
block it. The pre-authentication checks run before the password is compared, so
an unverified address answers `DisabledException` for *any* password, and the
login form becomes an account enumeration oracle. On a site whose users are
activists, "does this person have an account here" is not a harmless fact.

### Q: Why no `Organization` entity? **[WARM]**

`User` plus a `POSTER` role plus profile fields expresses everything the MVP
needs. An `Organization` would force decisions nothing depends on yet: does it
log in, can a user belong to several, who owns its actions. The named
migration path is an `Organization` table plus a membership join, added when
multi-member NGOs actually exist. The bad answer is "I didn't think about it";
the good one is "here is the trigger that would make me add it".

### Q: You have a `POSTER` role and a `poster_verified` flag. Aren't those the same fact? **[DEEP]**

They were, and I removed the flag. Two columns holding one fact can disagree,
and then nothing tells you which is authoritative. The role is the single
source of truth for the privilege; `poster_verified_at` survives only because
it records *when*, which the role cannot, and a `CHECK` keeps it consistent
with the role.

---

## 11. Architecture

### Q: Why package-by-feature? **[WARM]**

With six features and four layers, package-by-layer gives four fat packages
where nothing related is co-located and every change touches four directories.
Package-by-feature keeps a change local and makes extracting a module later
mechanical.

### Q: Why not microservices, Kafka, Redis, or Elasticsearch? **[COLD]**

None of them solves a problem I have. At this scale each would add an
operational component, a failure mode, and a synchronization problem in
exchange for nothing. Reaching for them before having the problem is the
signal I would want to avoid in someone else's design.

### Q: Why server-rendered Thymeleaf and not an SPA? **[WARM]**

Content-heavy, SEO-relevant, low-interactivity site — server rendering is the
default that fits, with htmx for the handful of partial updates. I also expose
a small read-only JSON API over the same service layer, which is what future
mobile clients or third parties would use. Chosen per use case, not by
fashion.

### Q: Why is Testcontainers justified here and not just fashionable? **[WARM]**

The full-text search query cannot be tested against H2 — `tsvector`,
`to_tsvector`, GIN indexes and `pg_trgm` do not exist there. Testing repository
queries against a different database than production tests the wrong thing. One
singleton container is reused across the whole suite so CI time stays sane.

### Q: What does "one singleton container" actually take? **[DEEP]**

More than it sounds. A container returned from a `@Bean` method is created per
application context, and Spring caches one context per distinct test
configuration — `@SpringBootTest` and `@DataJpaTest` are two different ones — so
the naive version starts Postgres once per slice and pays its startup cost each
time. The container has to be a `static` field, started once per JVM, with the
`@Bean` method returning that instance.

Nothing stops it, deliberately: Testcontainers' Ryuk sidecar removes the
container when the JVM exits, so there is no close() to get wrong. I check it by
counting `Creating container for image` in the build log — one line, across
three context configurations.

Two things that cost me time and are worth knowing: the configuration class has
to be `public`, because Initializr generates it package-private and `@Import`
cannot reach a package-private class from a repository test in a feature
package; and in Testcontainers 2.x `PostgreSQLContainer` is no longer generic,
so every `PostgreSQLContainer<?>` you find online fails to compile.

---

## 12. Things to be able to demonstrate, not just describe

- The N+1 fix with a measured before/after query count from
  `hibernate.generate_statistics`.
- The duplicate-URL path: normalized URL, unique constraint,
  `DataIntegrityViolationException` caught by constraint name, friendly page
  instead of a 500.
- The deletion integration test: create a poster with actions, images, reports
  and subscriptions, delete, assert every table is empty.
- The digest matching rules as fast unit tests with no database.
- `ddl-auto=validate` failing to notice a length mismatch, in four lines.

---

## 13. Queries, pagination and schema change

### Q: Why `EXISTS` instead of a join against the category/region tables? **[COLD]**

A join doesn't filter rows, it pairs them: one parent row times N matching
child rows. An action tagged `BURGAS` and `NATIONAL`, matched against a
subscriber who follows both, comes back twice; add the category join and it
comes back four times. `EXISTS` asks a yes/no question about related rows
instead of combining them, so the result is one row per action.

Postgres plans `EXISTS` as a **semi-join**: it stops at the first matching
child row rather than finding all of them. With `LIMIT 20` on a digest query
that difference compounds.

### Q: Why not just add `DISTINCT`? **[WARM]**

It works and it's what most people reach for, but it creates duplicates and
then pays to remove them, it masks the multiplication rather than preventing
it, and it breaks counting — `count(*)` over the joined rows returns 4 where
there is 1 action, so pagination reports the wrong total. In JPA it is worse:
a collection fetch join combined with `firstResult`/`maxResults` triggers
`HHH000104`, where Hibernate loads every matching row into memory and
paginates there.

Related trap: `IN (subquery)` is usually planned the same as `EXISTS`, but
`NOT IN` returns nothing at all if the subquery yields a single NULL. Use
`NOT EXISTS`.

### Q: How does offset pagination break, and what's the alternative? **[DEEP]**

`LIMIT 20 OFFSET 40` asks the database to produce the full ordered result,
discard 40 rows, and return the next 20. Two failure modes:

1. **Ambiguous ordering.** With ties in `ORDER BY published_at DESC` the order
   among equal rows is undefined between queries, so a row can appear on page 1
   and again on page 2, while another is never shown. Fixed by a unique
   tiebreaker: `ORDER BY published_at DESC, id DESC`.
2. **Deep offsets are slow.** `OFFSET 10000` still generates and throws away
   10,000 rows.

Keyset (cursor) pagination fixes both: instead of an offset, carry the last
row's sort key — `WHERE (published_at, id) < (:lastPublishedAt, :lastId)
ORDER BY published_at DESC, id DESC LIMIT 20`. It uses the index directly and
is stable while rows are inserted. The trade-off is no "jump to page 7".

### Q: How do you change a schema in production without downtime? **[DEEP]**

Expand/contract, in three phases, because during a rolling deploy the old and
new application versions run against the same database at the same time:

- **Expand** — add the new structure in a backwards-compatible way: a nullable
  column, or a new table. Old code ignores it.
- **Migrate** — deploy code that writes both old and new, backfill existing
  rows in batches, then switch reads to the new structure.
- **Contract** — once nothing reads the old structure, drop it in a later
  migration.

A rename becomes: add `new_col`, dual-write, backfill, switch reads, drop
`old_col`. Postgres specifics worth naming: `ADD COLUMN` with a default is
metadata-only since version 11; adding `NOT NULL` or a `CHECK` scans the table
and takes a strong lock, so add the constraint `NOT VALID` first and
`VALIDATE CONSTRAINT` afterwards, which takes a weaker lock.

### Q: Is DDL transactional? **[WARM]**

In Postgres, yes — `CREATE TABLE`, `ALTER TABLE`, `CREATE INDEX` all roll back.
Flyway wraps each migration in a transaction, so a file with eleven statements
either applies completely or not at all; a typo in the last one leaves nothing
behind and nothing recorded in `flyway_schema_history`. On MySQL and Oracle
each DDL statement implicitly commits, which is why half-applied migrations are
a normal hazard there and not here.

Exceptions that cannot run inside a transaction block: `CREATE INDEX
CONCURRENTLY`, `CREATE DATABASE`, `VACUUM`, `ALTER SYSTEM`. Flyway handles them
with `-- executeInTransaction=false`.

The related operational concern is locking: DDL takes an `ACCESS EXCLUSIVE`
lock, so an `ALTER TABLE` queues behind running queries and everything else
queues behind it. `SET lock_timeout` before DDL turns a potential outage into a
failed migration you can retry.

---

## 14. Stack choices

### Q: Why Spring Boot 4 rather than 3? **[WARM]**

The project started after Boot 3's open-source support window closed, and
Initializr no longer offers 3.x. Boot 4 brings Spring Framework 7, Spring
Security 7, Hibernate 7, Jackson 3 and a Jakarta EE 11 / Servlet 6.1 baseline,
with modular starters (`spring-boot-starter-webmvc` rather than `-web`). The
real cost is ecosystem lag: most tutorials and snippets online are written for
3.x, so security and persistence configuration has to come from the official
documentation rather than blog posts.

Concretely, the things that have already cost me time: `@DataJpaTest` moved to
`org.springframework.boot.data.jpa.test.autoconfigure`; the umbrella
`spring-boot-starter-test` is replaced by per-slice starters
(`-data-jpa-test`, `-webmvc-test`, `-security-test`), which is where Mockito and
AssertJ now come from; the test stack is **JUnit 6**, not 5; and
`spring-boot-starter-validation` still has to be added by hand, because the web
starter has not included Bean Validation since Boot 2.3.

### Q: Which PostgreSQL version, and how did you choose it? **[DEEP]**

18, because that is what the host provisions. The deployed version decides and
nothing else does: developing on a major version the host cannot give back means
a `pg_dump` that will not restore and behaviour nobody has tested. So
`compose.yaml`, the Testcontainers image tag and the Railway database all say 18,
and they move together. Nothing in my schema needs anything that new — identity
columns are Postgres 10+, stored generated columns 12+, `pg_trgm` and GIN far
older — which is exactly why matching the platform costs nothing.

The honest part of this answer is that I had it wrong first. An earlier revision
pinned everything to 17 on the belief that Railway offered 16 and 17 with 18 only
as community forks. The code moved to 18 when the deploy proved otherwise and the
documents lagged a week behind it. The lesson I actually take from it is the
check, not the number: `SELECT version();` against the deployed database settles
it, and a platform's documentation or a blog post does not.

Two details are coupled to that 18, and on 18 they are live, not hypothetical.
It changed the default for generated columns to `VIRTUAL`, and a virtual column
cannot be indexed — so `GENERATED ALWAYS AS (...)` without `STORED` makes my
`CREATE INDEX ... USING gin (search_vector)` fail outright. The explicit `STORED`
in `V1__` is therefore load-bearing rather than defensive, and the evidence it
takes effect is that the full-text search tests pass against 18.6 in CI; that
index could not exist otherwise. And 18 moved the image's declared data volume
from `/var/lib/postgresql/data` up to `/var/lib/postgresql`, so the Compose mount
path is tied to the image tag — get that wrong and the database silently starts
empty on every container recreate.

### Q: Why Java 21 and not 17 or 25? **[WARM]**

Boot 4's baseline is 17 and it supports 25, but 21 is the LTS most job adverts
target, and it carries records, pattern matching, sealed types and virtual
threads. Choosing 17 would give up language features for no benefit; 25 is
defensible but buys nothing this project needs.

### Q: Maven or Gradle? **[WARM]**

Maven, because its lifecycle and dependency model are what most teams I'd be
joining use, and its configuration is explicit. Gradle is faster and nicer to
write; the deciding factor was familiarity for whoever inherits the project.

### Q: YAML or properties? **[WARM]**

`application.yml` for configuration: profiles in one file via
`spring.config.activate.on-profile`, no repeated key prefixes, readable lists.
Message bundles stay `.properties` because that is the `MessageSource` format,
not a Spring configuration choice.

---

## 15. Moderation, abuse and operational limits

### Q: Walk me through what happens when someone submits a link that is already on the platform. **[COLD]**

The URL is normalized first — lowercase host, `www.` stripped, `utm_*` and
`fbclid` parameters removed, trailing slash and fragment removed — so that two
spellings of the same link compare equal. The raw URL is kept for display; the
normalized one is what the uniqueness rule uses.

Then two things, both of which have to exist:

1. The service looks the normalized URL up before inserting. If it finds a row,
   the poster gets an inline message on the form — "this action is already on
   the platform" — with a link to it. Nothing is written.
2. The insert can still fail on `uq_action_normalized_url`. I catch
   `DataIntegrityViolationException`, identify the constraint **by name**, and
   show the same message instead of a 500.

A duplicate never becomes a row. It is not stored as a `REJECTED` action,
because that would fill the table with garbage and the moderation queue with
noise.

### Q: Why both? Isn't the lookup enough? **[COLD]**

Because the lookup and the insert are two separate statements — a check-then-act
race. Two posters submitting the same link in the same second both pass the
lookup, and only the unique constraint stops the second one. The database is
the only place a uniqueness rule can actually be enforced; the lookup exists
purely to produce a better error message in the common case.

The same reasoning applies to the URL being optional. Both URL columns are
nullable and paired by a `CHECK`, and Postgres treats NULLs as distinct inside
a `UNIQUE` constraint, so any number of link-less actions coexist with no
special case in the code.

### Q: You have four action statuses. Why not a separate HIDDEN state for admin takedowns? **[WARM]**

I had one, and removed it. It existed only because the state graph used to be
one-way: `HIDDEN` meant "an admin took this down", as distinct from
`WITHDRAWN`, which is the poster's own reversible choice. Once I allowed an
admin to move an action from `PUBLISHED` back to `REJECTED`, `REJECTED` *is*
the takedown state and `HIDDEN` was a second name for the same fact. Two names
for one fact is how states drift apart.

Restoration is a status change, never delete-and-recreate: the action keeps its
id, so shared links and digest links keep working, and the poster retypes
nothing.

### Q: A poster you trusted starts publishing spam. What stops them? **[WARM]**

Three things, in order. A quota of ten submissions per user per rolling 24
hours, enforced in the service as a `COUNT` over `created_at` — that bounds the
damage before anyone notices. An admin ban, which disables the account *and*
moves its published actions to `REJECTED`, because `enabled = false` on its own
only stops login and would leave the spam live. And the fact that promotion to
`POSTER` is granted by hand in the first place; the three-approved-actions rule
is a floor the admin has to clear, not an automatic trigger.

The quota is a database count rather than an in-memory rate limiter because the
limit is per account rather than per IP, and it has to survive a restart.

### Q: Why is the ban a loop rather than a bulk update? **[WARM]**

The number of actions per user is small and bounded by the quota, so I load
them and set the status on each one. Dirty checking writes the UPDATEs and
maintains `updated_at` for free, and there is no stale-persistence-context
trap. The bulk `@Modifying` form is faster but skips `@UpdateTimestamp` and can
be silently undone by entities already loaded in the session, so it needs
`updated_at` written into the query by hand and `clearAutomatically = true`. I
would switch to it at thousands of rows, not at fifty.

### Q: Can someone take the site down by submitting things that fail? **[DEEP]**

Not realistically through the duplicate path. Submitting requires a logged-in,
email-verified account, so an attacker needs a real mailbox first, and a
refused duplicate costs one indexed lookup and a rolled-back transaction. It
does not even consume the daily quota, because that counts rows and no row is
created.

The expensive endpoint is the image upload: re-encoding a 2 MB file to strip
EXIF is real CPU and heap on a small container. The mitigations are cheap and
ordered — `spring.servlet.multipart.max-file-size` rejects oversized bodies
before any of my code runs, content sniffing and the cheap validations run
before the re-encode, and Cloudflare sits in front for volumetric floods.

The resource that actually runs out first is the connection pool, not CPU:
every request that touches the database holds a HikariCP connection, and
Postgres has its own connection ceiling. That is the number I would watch.

### Q: You rate-limit the login form. What numbers did you pick, and why those? **[WARM]**

Twenty attempts per fifteen minutes per client address on `POST /login`. On
`POST /register`, ten per hour per address and three per twenty-four hours per
*email* address.

The login number is the one worth defending, because twenty sounds generous. It
is not sized for one user — one user needs one attempt, or about five if they
have forgotten which password this is. It is sized for a **shared** address.
Most mobile traffic in Bulgaria arrives through carrier-grade NAT, so one
address can be a whole network, and a limit of five would lock out the second
person who mistyped. Twenty leaves room for four people fumbling. From the
attacker's side it caps a run at eighty guesses an hour from one address, which
makes a thousand-password list a half-day job and a credential-stuffing list of
thousands pointless.

What it does **not** stop is a slow distributed run — a hundred addresses each
making two attempts a minute. Nothing per-IP does. The honest answer to that is
Cloudflare Turnstile on the form, which is the next step I would take and have
not taken.

### Q: Why two buckets on registration? **[DEEP]**

Because they defend different things, and only one of them defends *me*.

Ten registrations an hour from one address exists only for shared addresses
again — an office or a classroom signing up after a talk. Registration is a
once-ever act, so any number above one is there for that case.

The per-recipient bucket protects **somebody else's inbox**. Registering over
an address that exists but is unconfirmed replaces that row and emails its
owner a fresh link — which is deliberate, because it is the resend mechanism,
and there is no resend form. Without a cap keyed on the address, the
registration form is a mail cannon anyone can point at a stranger: one request,
one email, repeat. Three a day is enough for someone who really is registering,
failing and registering again, and far too few to be a nuisance.

**And the two caps are enforced in different places, which is the part worth
the question.** The per-address one refuses the request, in the filter. The
per-recipient one refuses the *send*, in the email listener. See the next
answer — it is the best thing in this section.

The second one is also why the rejection log line does not contain the email
address. The login page refuses to say whether an address has an account here;
a log file that answers the same question is the same leak, written down.

### Q: You said lockout is a DoS weapon. Did you avoid building one? **[DEEP]**

Not on the first attempt, no. This is the best finding anyone has given me on
this project and I would rather tell it than be asked.

I rejected account lockout in ADR-002 for a specific reason, written down:
*it turns the login form into a denial-of-service weapon against anyone whose
email is known.* Then I built the per-recipient registration cap **in the
filter**, keyed on the submitted email address. Three POSTs naming
`victim@example.com` and the real owner of that address could not register for
twenty-four hours — they got a bare 429. No session and no CSRF token needed,
because the limiter sits in front of `CsrfFilter`, so even a request that
Spring Security was about to refuse had already spent the victim's allowance.
Attacker cost was three of their own ten hourly requests, so three victims an
hour from one address, and an IPv6 `/64` is a fresh bucket.

That is the same weapon. Same mechanism, different form. I had written the
argument against it twice and then implemented it anyway, which is worth
sitting with: the reasoning was in the ADR, and what was missing was asking
*which key* each bucket used and *who* could name it.

**The fix came out of the bucket's own stated purpose.** It exists to protect a
stranger's inbox, not my server. So it belongs on the send, not on the request:
when the allowance is spent, suppress the email and still return the neutral
"check your inbox" page. The inbox is bounded exactly as before. The owner is
refused nothing. And `/register`'s single answer for every address survives —
which the 429 had also broken, because it was a third response the form could
produce, keyed on request history rather than on the address.

**What it still costs, because this is not a clean win.** A suppressed send is
an email that never arrives, and the recovery the "check your inbox" page
offers — register again — will not produce one either until the window passes.
So an attacker can still deny a targeted address its *email* for a day. What
they can no longer do is deny it the *account*. That is a strictly smaller
weapon, and I think it is the smallest one available: no cap on mail to an
address can both bound the mail and promise that the next message gets through.

**The general rule I took from it**, which is the reusable part: a bucket that
can refuse a request may only be keyed on something the requester cannot choose
on somebody else's behalf. That leaves the client address. Anything keyed on a
value a stranger can type belongs on the side effect, not on the response.

Two things fell out of the fix for free. The filter reads no request parameter
any more, so it never parses a request body — the multipart caveat the first
version needed is gone. And the welcome email is exempt from the cap, because
it cannot be used as a cannon: reaching it requires a valid signed token for
that account, which only the mailbox's reader has.

### Q: Did you get the rest of that right first time? **[DEEP]**

No, and the follow-up findings are more interesting than the original, because
all three are the same mistake: I corrected the happy path and not the things
arranged around it.

**The kill switch still disarmed the mail cap.** It was called `enabled` and
turned off all three buckets. But the switch's whole purpose is recovering from
the limiter refusing real users, and only the two address-keyed buckets can do
that — the mail cap is keyed on the recipient and cannot misfire against a
client. So the one situation the flag exists for was also the situation in
which flipping it turned `/register` back into a mail cannon, with the
per-address bucket going off at the same instant so nothing was left bounding
it. It is now `refuse-requests`, named for what it does, read only by the
filter; the mail cap has no switch at all, and loosening it means changing the
number. There is a test over the properties record's component list, so adding
a flag that disarms the cap means changing that test first.

**And I spent the token before the send instead of after it.** A provider
outage plus three retries by a user who can see no email left that address
capped for twenty-four hours having received nothing — the limiter punishing
the person it exists to protect. The cap counts mail that reached an inbox, not
attempts to hand it to a provider.

That second one is worth naming as a pattern, because this codebase already
contains the same idea somewhere I *did* get right: ADR-005's digest watermark
"moves only after a successful send". **A side effect that bounds a resource
commits on success, never on attempt.** Two instances, one rule, and I only
spotted it in the second place after someone pointed at the first.

The implementation detail is a small decision too: consume up front and refund
on failure, rather than peek then consume. The mail pool is one thread today,
so peeking is exact — but ADR-005 says both pool numbers move together if the
digest ever needs concurrency, and on that day a peek becomes a race that
over-sends while a consume stays atomic. The refund mirrors the welcome-email
exemption, or a failed welcome would mint a free token.

**One process point I would volunteer here.** The first version of the refund
test passed with the refund deleted from the code — it built a fresh limiter
per listener, so each got a fresh bucket store and the assertion proved nothing.
I now check a regression test by mutating the thing it covers and confirming
that test, and only that test, goes red. A regression test nobody has watched
fail is a comment with an assertion in it.

### Q: How do you key the buckets? **[DEEP]**

`request.getRemoteAddr()`, after Tomcat's `RemoteIpValve` has walked
`X-Forwarded-For` — with Cloudflare's published ranges in
`server.tomcat.remoteip.trusted-proxies`, so the walk goes *past* the edge and
lands on the real client. Without that one property every request on the site
shares one bucket and the whole country is behind one login limit.

Not `CF-Connecting-IP`, which is the usual recommendation, and reversing it is
the interesting part. That header is unforgeable only when the request really
came through Cloudflare. A request sent straight to the `*.up.railway.app`
origin carries whatever the client wrote in it, so every forged value is a
fresh bucket and the limit is bypassed by editing one header. `getRemoteAddr()`
is sound on both paths, because the valve has already done the "is this peer
Cloudflare" check that the header cannot do for itself.

Two more things happen before the value is a key. IPv6 collapses to its `/64`,
because a `/128` is one address out of the block an ISP hands a single
household — key on the whole thing and one attacker has 2^64 buckets. And
anything unparseable goes to a single shared key rather than its own: the valve
does not validate what it takes out of the header, so a forged value can be
arbitrary text, and a key per forgery is the bypass all over again.

### Q: Anything surprising come out of writing that? **[DEEP]**

Three things, and the first is a denial-of-service hole I put in myself.

`InetAddress.getByName` **resolves numeric-looking hostnames**. `1.2.3.4.5` is
not a parse failure, it is a DNS lookup. So the obvious implementation — parse
the address, mask it, use it as a key — would have performed one lookup per
login attempt, against a name the attacker chooses, which is a far better
weapon than the brute force I was defending against. The dotted-quad form is
therefore parsed by hand, and `getByName` is called only for values containing
a colon, which it rejects outright rather than resolving, because a colon is
not legal in a hostname. `InetAddress.ofLiteral` says all of that in one call
and arrives in Java 22; I am on 21.

Second: Spring Boot auto-registers a `Filter` bean with the servlet container
*as well as* adding it to the security chain. Declared as a bean, the limiter
would have run twice and spent two tokens per request. It is constructed in
`SecurityConfig` instead, which also puts its position next to every other
filter's.

Third, and this one is a choice rather than a trap: the limiter's clock is the
application's injected `Clock`, handed to Bucket4j as a custom `TimeMeter`. A
rate limiter *is* a stopwatch. Without that, a test of "twenty attempts, a
refusal, then sixteen minutes later another twenty" either waits sixteen
minutes or asserts nothing about the window.

### Q: Where do the buckets live, and what breaks? **[WARM]**

In memory, in a Caffeine cache per scope. The core module only — every other
Bucket4j module exists to share buckets across a cluster, and the scheduler
already assumes exactly one instance, so a distributed bucket store would
encode that assumption a second time in a dependency I had rejected.

A small trap worth knowing if you ever add Bucket4j: `bucket4j-core` looks like
the artifact and is a dead line that stops at 8.10.1. From 8.11 the artifacts
are split by target JDK, so on Java 21 it is `bucket4j_jdk17-core`. I pinned
the dead one at what looked like its newest release and a reviewer caught it.

The `enabled` flag that used to sit in front of all three scopes is gone from
this class for a reason worth repeating: a kill switch has to respect the
distinction the scopes make. Two of them refuse a request and one refuses a
send, and only the first kind can ever need killing.

One more failure mode, and I had the sign wrong on it at first. If the valve
ever stops short of the real client — a stale Cloudflare range, a change at the
edge — every request shares one bucket, which means twenty login attempts per
fifteen minutes *for the entire site*. I had written that down as
"over-restrictive, which is the right direction for a limiter to break in."
That is wrong: it is a site-wide login outage whose symptom is "login is broken
for everybody". It is still better than the limit being absent, and it is one
log line to diagnose, because the refusal prints the resolved key and a
Cloudflare edge address there *is* the diagnosis. But the real mitigation is at
the edge — restrict the origin to Cloudflare so the chain is always the one the
configuration describes — not in the application.

What else breaks, stated rather than discovered: **every deploy resets every
bucket.** At one deploy a day against a fifteen-minute window that is a
rounding error. It stops being one the day a second instance exists — and on
that day the limiter and the digest scheduler need the same fix.

Caffeine rather than a `ConcurrentHashMap` because a map keyed on a client
address never shrinks: one entry per address ever seen, and IPv6 `/64`s to
spare. That is an out-of-memory error an attacker can schedule. Two bounds
close it. `expireAfterAccess` equal to the scope's own window, which is derived
rather than picked — after a full window with no request a greedy bucket is
back at full capacity, so the entry holds no information and dropping it is
free. And a maximum size, whose cost is real: evicting a half-spent bucket
resets it. What makes that unattractive to exploit is Caffeine's W-TinyLFU
admission policy, which keeps entries by frequency — under an attack the
entry the attacker wants evicted is the one the cache most wants to keep.

---

## 16. A bug of mine worth telling

### Q: Tell me about a bug you caused and how you found it. **[WARM]**

`AppUserRepository` had no `package` declaration — the file opened straight on
an `import`. Java has no requirement that a source file's directory match its
package, so it compiled, silently, into the default package.

Three consequences, in the order they would have surfaced. Spring's component
scan starts at the `@SpringBootApplication` package, and the default package is
not below it, so the repository was never registered as a bean — no error, it
simply did not exist. A class in the default package cannot be imported from any
named package, so the first service to use it would have failed to compile with
a "cannot find symbol" on a file sitting right there. And the test suite was
green the whole time, because the only test was `contextLoads` and nothing ever
asked Spring for the bean.

I found it by looking at `target/classes` rather than at the source: the class
file was at the root instead of under `com/fivegoodmins/fivegoodmins/user/`.

What I changed is the part I would actually want to be asked about: a passing
test suite that cannot distinguish "this works" from "this was never executed"
is not a test suite. The repository now has a `@DataJpaTest` that exercises both
of its query methods against a real PostgreSQL, so the bean's absence would fail
the build.

---

## 17. Email delivery and the mail pool

Everything here was learned by running it, which is the only reason it is worth
saying out loud.

### Q: `corePoolSize=1, maxPoolSize=2, queueCapacity=50` — how many threads does that pool use? **[COLD]**

One. `ThreadPoolExecutor` only creates a thread beyond the core size when the
queue is **full**, so with fifty slots in front of it the second thread appears
on the fifty-first simultaneous task and never otherwise. A maximum above the
core size with a non-trivial queue describes a pool that does not exist.

I wrote exactly that configuration and described it in a comment as two
threads. The fix was to make it honest rather than to make it two: one thread
is right for one SMTP round trip at a time, so it is now core 1, max 1. If the
digest ever needs concurrency, both numbers move together.

The order the executor applies is worth having ready: use a core thread if one
is free, else **queue**, else grow to max, else reject. People expect "grow,
then queue".

### Q: What is wrong with Spring Boot's default `@Async` executor? **[WARM]**

Not what the usual answer says. The common claim is that `@Async` falls back to
`SimpleAsyncTaskExecutor` and starts a thread per call — that is Spring's
behaviour without Boot. Boot's `TaskExecutionAutoConfiguration` provides
`applicationTaskExecutor`, a `ThreadPoolTaskExecutor`, and `@Async` uses it.

The real hazard is that pool's **unbounded queue** (`queue-capacity` defaults to
`Integer.MAX_VALUE`). A mail provider that stops responding accumulates tasks
until the heap goes, and the symptom surfaces somewhere unrelated because the
same pool serves everything. So email gets a small pool with a bounded queue,
where the failure is a rejected task at a known moment rather than a slow leak.

With `spring.threads.virtual.enabled` the default *is* a
`SimpleAsyncTaskExecutor` — on virtual threads, still unbounded, in a different
way.

### Q: You defined your own executor. What did that quietly break? **[DEEP]**

Boot's. `TaskExecutionAutoConfiguration` is conditional on there being no
`Executor` bean at all, so defining `emailExecutor` removes
`applicationTaskExecutor` — and then a single-threaded pool with a fifty-deep
queue, sized for one SMTP call at a time, becomes what Spring MVC uses for
async request handling and what any unqualified `@Async` picks up.

`spring.task.execution.mode=force` keeps both. It is one line of configuration
standing in for a bug that would have surfaced much later, in something
unrelated to email, and a test asserts the bean still exists so deleting that
line fails the build.

### Q: You dropped `@Async`. Why? **[DEEP]**

Because of where a rejection ends up. With `@Async`, the submit to the executor
happens inside the proxy, which here is inside Spring's transaction
synchronization — so when the queue is full, `TaskRejectedException` is thrown
past the listener and lands in `TransactionSynchronizationUtils`, which logs
"TransactionSynchronization.afterCompletion threw exception" and a stack trace.
No account id, no address, nothing saying it was an email, because the event is
out of scope by then. Neither the listener's `catch` nor an
`AsyncUncaughtExceptionHandler` covers it; both only see failures *inside* the
task.

I found that by filling the queue and reading the log, not by reasoning about
it. The listener now calls `emailExecutor.execute(...)` itself and catches the
rejection where the event still exists, so the line reads "mail queue full, no
Verification email sent for account 7". The cost is that the committing thread
does the submitting — microseconds, and on both registration paths equally, so
the timing oracle stays closed.

The general lesson is the one I would lead with: an annotation moves work to
another thread *and* moves the failure to another place, and the second half is
the one nobody checks.

### Q: Anything else that bit you in that commit? **[WARM]**

Two framework behaviours, both of which fail at startup rather than quietly,
which is the only reason they were cheap.

A `@Configuration` class with `@Bean` methods cannot implement
`AsyncConfigurer` under Boot 4: a bean post-processor wraps every
`AsyncConfigurer` to supply the default executor, and the wrapped object is
then not an instance of the configuration class, so the context fails with
"Illegal factory instance for factory method".

And there was no `MessageSource` at all. Boot's auto-configuration is behind a
condition that resolves `classpath*:messages.properties`, and this project
deliberately ships only `messages_bg` and `messages_en` — no default-locale
bundle — so the condition never matched and the context fell back to a
`DelegatingMessageSource` that resolves nothing. That one did *not* fail at
startup: it failed when the first email rendered, on a pool thread. It is now
declared by hand.

### Q: How did you pick the email provider? **[WARM]**

On the free-tier limits as they actually stood on the day, not on what I
remembered about them. Brevo: 300 emails a day, no monthly ceiling. Resend: 100
a day *and* 3,000 a month. Mailgun: 100 a day, and sendable only from a sandbox
domain to five verified addresses until a card is on file — which means it
cannot mail an arbitrary registrant at all, so it was not a close second, it
was not a candidate.

The number that decides it is **emails per day**, because the digest sends one
email per subscriber per day. A monthly allowance you cannot spend in a day is
an allowance for a different shape of product. Brevo's daily number is three
times Resend's, so Brevo.

The honest part of the answer is the cost. Resend has the cleaner API and much
better delivery logs, and 100 a day would have been plenty for this site's real
subscriber count for a long time. I traded those logs away in the same week
whose other job is debugging deliverability. If that turns out to be the wrong
trade it is one class, which is what the `EmailSender` interface was for — and
being able to say *one class* rather than *a refactor* is the only reason the
interface earns its place.

### Q: What does the Brevo sender do that is not about Brevo? **[DEEP]**

Two things.

Connect and read timeouts, which are mandatory rather than defensive habit.
The mail pool is **one thread** behind a fifty-deep queue. A provider that
accepts a connection and then stops talking would, with the JDK's default of no
timeout, hold that thread forever — so a slow afternoon at the provider becomes
fifty lost emails and then silence, with no error anywhere. The timeout is what
turns that into fifty logged failures.

And a 4xx and a 5xx are the same failure: both throw one
`EmailDeliveryException`. The distinction between a permanent rejection and a
transient outage is only worth modelling once something retries, and I decided
not to retry at this volume. The thing to name when asked what comes next is a
transactional outbox — the pending email written in the same transaction as the
user, a job that sends and retries with backoff, and the row marked done — not
a `boolean` return value that a caller can ignore.

### Q: The welcome email is "sent in the same transaction that confirms the address". Is it? **[DEEP]**

The *decision* is made there. The send is still after commit, like every other
email in the application.

`RegistrationService.confirm` publishes the event from the one branch that
actually writes `email_verified_at`, and that precision is the whole mechanism.
Every other way of reaching the confirmation page returns before that write: an
account already confirmed, a mail client prefetching the link, a user clicking
it twice, an expired token. So "sent exactly once" is true with **no column
recording that it was sent** — `email_verified_at IS NOT NULL` is the record.
The test clicks one link three times and asserts one welcome email.

Two consequences worth volunteering. The five actions it carries are loaded
inside that transaction and travel in the event as DTO projections, because by
the time the listener runs there is no persistence context to load them from —
the same reason the event has never carried an entity. And the email contains
**no promise of a daily digest**, which is the sentence it most obviously wants,
because the digest job is not built yet and a welcome email that promises one
is a lie in somebody's inbox until it is.
