-- V101__unsubscribe_seed_accounts.sql
--
-- Turns the daily digest off for the two demo accounts V100 creates.
--
-- WHY THIS EXISTS. V2 adds app_user.digest_enabled with DEFAULT true, which is
-- right for real registrants and wrong for these two rows. The seeded authors
-- are `@seed.example` addresses -- RFC 2606 reserves the domain precisely so
-- that it can never receive mail (ADR-021) -- and both are created with
-- email_verified_at set, so without this they match the digest's subscriber
-- query exactly: verified, enabled, subscribed.
--
-- The consequence that makes it worth a migration rather than a shrug: the
-- deployed site would attempt two sends a night, forever, to a domain with no
-- MX record. Those are hard bounces, and a hard bounce rate is the single
-- fastest way to damage the sending reputation ADR-005 spends a week building
-- -- which is the reputation every real subscriber's digest then depends on.
-- Brevo counts them against the 300-a-day allowance too, and will suspend a
-- sender whose bounce rate climbs. Two addresses is a small number and a
-- permanent, daily, self-inflicted one.
--
-- WHY IT IS NOT IN V2. Flyway orders by version across every configured
-- location, so V2 runs before V100 and an UPDATE there would match no rows.
-- The rule also belongs here on its own merits: it is a fact about the seeded
-- content, so it lives with the seeded content and is absent from the test
-- suite for the same reason V100 is (application.properties drops db/seed).
--
-- WHY NOT A FILTER IN THE QUERY. Excluding `@seed.example` inside the digest
-- would put knowledge of the demo data into the production send path, where it
-- would stay long after the demo rows are gone. The seed owns its own rows'
-- subscription state; the job stays ignorant of where a subscriber came from.
--
-- Numbered 101 because V100's own header says the next seed file is: more
-- content, or a correction to it, is a new version and never an edit to an
-- applied one.

UPDATE app_user
SET digest_enabled = false
WHERE email LIKE '%@seed.example';

-- LIKE on the suffix rather than the two addresses spelled out, so a V102 that
-- adds a third demo author inherits this instead of reintroducing the bounce.
-- No index for it and none wanted: this runs once, against two rows.
--
-- Deliberately not idempotent-by-guard and not needing to be -- a versioned
-- migration runs exactly once. The day these accounts should receive mail is
-- the day they stop being seed accounts.
