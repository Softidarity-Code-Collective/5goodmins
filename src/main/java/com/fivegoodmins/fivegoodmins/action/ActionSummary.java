package com.fivegoodmins.fivegoodmins.action;

/**
 * An action as a list reads it: a DTO projection, never the entity.
 *
 * <p>ADR-003's rule is that entities do not leave the service layer and that
 * list views use projections. The reason is visible in this record's shape --
 * it carries four columns, so the query behind it reads four columns, and
 * nothing it is handed to can lazily reach {@code action.author} or
 * {@code action.categories} and find itself outside a transaction with
 * {@code open-in-view=false}. A template holding an entity is a
 * {@code LazyInitializationException} waiting for a designer to add one field.
 *
 * <p><strong>No categories or regions, and that is a choice with a date on
 * it.</strong> The badges the frontend brief asks for live in two element
 * collections, so adding them means either a query per action or a join that
 * returns an action once per tag -- the duplication ADR-005 avoids with
 * {@code EXISTS} in the digest. The welcome email is unpersonalized chrome and
 * does not need them (ADR-005); the digest and page 1 do, and they can pay for
 * a richer projection when they are built.
 *
 * <p><strong>{@code excerpt} is bounded in SQL, not the whole
 * {@code description} column.</strong> The first version read the column whole
 * and abbreviated it in the template, which is defensible at one read per
 * account verification and stops being defensible the moment the digest reuses
 * this shape — one email per subscriber per day, each re-reading up to 5,000
 * characters per action to display 140 of them. Deciding it now rather than
 * when the digest is written is the cheaper order.
 *
 * <p>{@link #EXCERPT_CHARS} is defined once and reaches the query as a bound
 * parameter through {@code ActionQueries}. It was briefly written twice, with
 * the duplication defended as unavoidable because "JPQL cannot read a
 * constant" and excused by analogy to {@code @Column(length)} against the
 * migration (§2). The analogy does not hold: that duplication really is forced
 * by {@code ddl-auto=validate}, and this one was a choice. A duplication
 * documented as inevitable when it was not is a worse answer than either.
 *
 * <p>What does remain in two places is the <em>display</em> length, which each
 * view picks for itself — and the invariant that goes with it: this read bound
 * must stay comfortably above every display length, or a view abbreviates text
 * SQL had already cut and loses the ellipsis that says it was cut.
 *
 * @param id         the row's identity, for a link or a test.
 * @param title      the action's own title, shown whole.
 * @param actionType labelled from the message bundle under
 *                   {@code actionType.<constant>} (ADR-008, ADR-017), never
 *                   from a string in Java.
 * @param excerpt    the first {@link #EXCERPT_CHARS} characters of the
 *                   description. Already cut, so a view may abbreviate it
 *                   further but must never assume it is complete.
 */
public record ActionSummary(Long id, String title, ActionType actionType, String excerpt) {

    /**
     * How much of {@code description} the query reads, applied by
     * {@code ActionQueries} and bound into the query as a parameter. Must stay
     * above every display length that abbreviates this value.
     */
    public static final int EXCERPT_CHARS = 200;
}
