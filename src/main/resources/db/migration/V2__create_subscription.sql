-- V2__create_subscription.sql
--
-- What a user follows, and whether they want the daily digest at all
-- (ADR-005, ADR-022).
--
-- Conventions are V1's (ARCHITECTURE.md §2): singular snake_case, every
-- constraint named, enums as varchar + CHECK rather than a native ENUM type,
-- timestamptz nowhere here because none of this is a point in time.
--
-- Flyway wraps the file in one transaction, so either all four statements
-- apply or none does.
--
-- THE SHAPE, AND WHAT WAS REJECTED (ADR-022). Two collection tables keyed on
-- the user, plus one boolean on app_user. The alternative was a `subscription`
-- row per user owning both collections and the flag, and it was rejected for
-- one reason: that row would not exist until a user visited the preferences
-- page, so every read -- the nightly job's subscriber query above all -- would
-- have to decide what an absent row means and say so in a LEFT JOIN with a
-- coalesce. A NOT NULL column with a DEFAULT answers that question once, in
-- the schema, for every row that exists today and every row added later.
--
-- The cost is on the Java side and is recorded in ADR-022: JPA requires the
-- entity owning an @ElementCollection to be the one whose primary key the
-- collection table references, so these two tables hang off AppUser in
-- `user/` while everything else about them lives in `subscription/`.


-- ---------------------------------------------------------------------------
-- 1. The digest switch
-- ---------------------------------------------------------------------------

ALTER TABLE app_user
    ADD COLUMN digest_enabled boolean NOT NULL DEFAULT true;

-- DEFAULT true, which is a product decision and not a convenience.
--
-- The site's stated purpose is a daily digest and the welcome email now says
-- so, so a registrant who never opens the preferences page has still asked for
-- it; defaulting to false would make the flagship feature invisible to
-- everybody who did not go looking for a settings page. The consent is the
-- registration itself, the privacy policy says the address is used for the
-- digest, and every digest carries a one-click unsubscribe that works without
-- logging in (ADR-005) -- which is the thing the EU actually requires.
--
-- The DEFAULT also backfills every row that already exists, which is the
-- behaviour wanted here: the accounts registered before this migration signed
-- up for the same product. A column added NULL-able and populated later would
-- have left them silently unsubscribed.
--
-- NOT NULL because there is no third state. "Has not decided yet" is not a
-- thing this column can usefully record: the job has to either send or not.


-- ---------------------------------------------------------------------------
-- 2. subscription_category   (zero to ten per user, ADR-017)
-- ---------------------------------------------------------------------------
--
-- Named for the feature that owns it rather than app_user_category, because
-- `subscription/` is the package it belongs to (ADR-003) and because
-- app_user_category reads as a role grant. The key is still user_id.
--
-- ZERO is a legal and meaningful number here, unlike action_category where one
-- to three is the rule. An empty set means "no filter", which the digest reads
-- as every category rather than as none -- see ADR-022 for why that direction
-- and not the other, and for where the substitution happens. There is
-- therefore no minimum to enforce and no maximum either: ten rows is the whole
-- vocabulary, which is a complete selection rather than an abusive one.

CREATE TABLE subscription_category (
    user_id  bigint      NOT NULL,
    category varchar(30) NOT NULL,

    CONSTRAINT pk_subscription_category PRIMARY KEY (user_id, category),
    -- The composite PK gives uniqueness for free: a category cannot be
    -- followed twice. It is also the index the per-user read needs, because
    -- user_id is its leading column -- which is why, unlike action_category,
    -- there is no second index below. Nothing ever asks "who follows NATURE?";
    -- the digest asks "what does this user follow?", and that is this index.

    CONSTRAINT fk_subscription_category_user FOREIGN KEY (user_id)
        REFERENCES app_user (id) ON DELETE CASCADE,
    -- Cascade in the database, never in JPA (ADR-015). Account deletion is a
    -- hard delete, and these rows go with it without Hibernate loading one.

    CONSTRAINT ck_subscription_category_value
        CHECK (category IN ('NATURE', 'ANIMALS', 'CHILDREN', 'HEALTHCARE',
                            'DISABILITY', 'LOCAL_COMMUNITIES', 'SOCIAL',
                            'HUMAN_RIGHTS', 'LABOUR_RIGHTS', 'POLITICAL'))
    -- The same ten as ck_action_category_value, deliberately duplicated rather
    -- than factored into a shared domain or a lookup table (§4 rejects lookup
    -- tables for the taxonomy). Adding a category is already an enum constant
    -- plus two bundle lines plus a migration; this makes it two CHECK
    -- constraints in that migration, and the pair is findable by grep.
);


-- ---------------------------------------------------------------------------
-- 3. subscription_region   (zero to thirty per user, ADR-009)
-- ---------------------------------------------------------------------------

CREATE TABLE subscription_region (
    user_id bigint      NOT NULL,
    region  varchar(30) NOT NULL,

    CONSTRAINT pk_subscription_region PRIMARY KEY (user_id, region),

    CONSTRAINT fk_subscription_region_user FOREIGN KEY (user_id)
        REFERENCES app_user (id) ON DELETE CASCADE,

    CONSTRAINT ck_subscription_region_value
        CHECK (region IN (
            -- Bulgaria's 28 oblasti, as in ck_action_region_value.
            'BLAGOEVGRAD', 'BURGAS', 'VARNA', 'VELIKO_TARNOVO', 'VIDIN',
            'VRATSA', 'GABROVO', 'DOBRICH', 'KARDZHALI', 'KYUSTENDIL',
            'LOVECH', 'MONTANA', 'PAZARDZHIK', 'PERNIK', 'PLEVEN',
            'PLOVDIV', 'RAZGRAD', 'RUSE', 'SILISTRA', 'SLIVEN',
            'SMOLYAN', 'SOFIA_CITY', 'SOFIA_PROVINCE', 'STARA_ZAGORA',
            'TARGOVISHTE', 'HASKOVO', 'SHUMEN', 'YAMBOL',
            -- Plus the two that are not oblasti. Following NATIONAL is the
            -- ordinary case for someone who wants countrywide campaigns and
            -- nothing local, and it is a subscription like any other: the
            -- digest matches a subscriber's region against the action's, so an
            -- action tagged NATIONAL reaches exactly the people who asked for
            -- it, and is not broadcast to everyone (ADR-022).
            'NATIONAL', 'INTERNATIONAL'))
);


-- ---------------------------------------------------------------------------
-- 4. The index the nightly job reads
-- ---------------------------------------------------------------------------
--
-- The digest's first query is "every verified, enabled, subscribed account",
-- which on a table of any size is a sequential scan with three predicates. A
-- partial index on the one column that is not in the WHERE clause -- id, which
-- the job orders by so the run is reproducible -- gives the whole subscriber
-- list from the index alone.
--
-- Partial rather than a plain index on digest_enabled: the overwhelming
-- majority of rows are true (it is the default), so an index on that column
-- would be an index Postgres declines to use. What makes this one worth having
-- is that it also excludes the unverified and the banned, so its size is the
-- number of accounts that will actually be mailed, and its condition is the
-- query's condition exactly.
--
-- Honest note on scale: at MVP volumes the sequential scan would be fine, and
-- the Brevo free tier caps the whole job at 300 sends a day anyway (ADR-005).
-- This is here because the predicate is fixed and the index is one line, not
-- because anything has been measured as slow.
CREATE INDEX idx_app_user_digest_subscriber ON app_user (id)
    WHERE digest_enabled AND enabled AND email_verified_at IS NOT NULL;
