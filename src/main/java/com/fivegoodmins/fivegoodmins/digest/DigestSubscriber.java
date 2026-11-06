package com.fivegoodmins.fivegoodmins.digest;

import java.time.Instant;
import java.util.Locale;

/**
 * One account the nightly job may mail: a projection of the five columns the
 * digest needs out of {@code app_user}, never the entity (ADR-003).
 *
 * <p><strong>Five columns rather than an {@code AppUser}, and at this volume
 * that is about correctness more than speed.</strong> The entity carries two
 * lazy element collections ({@code subscribed_category},
 * {@code subscribed_region}) and with {@code open-in-view=false} the job runs
 * outside any view; loading three hundred of them to read an address would
 * also be the N+1 that ADR-013 turns the setting off to expose. The
 * subscription sets are fetched deliberately, through
 * {@code SubscriptionService}, which is where "an empty selection means
 * everything" is decided.
 *
 * @param id     {@code app_user.id}. What the log lines name, and what the
 *               unsubscribe token signs.
 * @param email  the recipient, already lowercased and normalized by
 *               registration.
 * @param displayName shown in the greeting, like the other three emails'.
 *               User-supplied, so it appears in a body and never in the
 *               subject -- a display name with a newline in it would be header
 *               injection ({@code EmailMessage}).
 * @param locale the recipient's stored language, as the two characters the
 *               column holds. The nightly job has no request and no cookie,
 *               which is the whole reason {@code app_user.locale} exists
 *               (ADR-005) -- the alternative is a digest in whichever language
 *               the container defaulted to. Kept as a {@code String} because a
 *               JPQL constructor expression matches the column's own type, and
 *               a record declaring {@link Locale} here would simply fail to
 *               resolve a constructor; {@link #language()} is the conversion.
 * @param watermark {@code last_digest_sent_at}: the exclusive lower bound on
 *               {@code published_at} for this subscriber. Read here and passed
 *               back to {@code advanceWatermark} only after a send succeeds --
 *               never recomputed, and never advanced to {@code now}.
 */
record DigestSubscriber(Long id, String email, String displayName, String locale, Instant watermark) {

    /**
     * The stored language as a {@link Locale}, for the composer and the
     * subject lookup.
     *
     * <p>Safe to read as a language tag because {@code ck_app_user_locale}
     * restricts the column to {@code bg} and {@code en}. Without that
     * constraint an unexpected value would resolve to {@link Locale#ROOT}, and
     * with locale fallback off ({@code MessageSourceConfig}) the subject lookup
     * would then throw {@code NoSuchMessageException} on the mail pool rather
     * than fall back to anything -- a digest that fails to render for one
     * subscriber. Same reasoning, same one-line conversion, as
     * {@code RegistrationService.localeOf}.
     */
    Locale language() {
        return Locale.forLanguageTag(locale);
    }
}
