package com.fivegoodmins.fivegoodmins.user;

import com.fivegoodmins.fivegoodmins.action.Category;
import com.fivegoodmins.fivegoodmins.action.Region;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254)
    private String email;
    // Uniqueness is uq_app_user_email in the Flyway migration. unique = true
    // here would be DDL-generation metadata only -- ddl-auto=validate never
    // reads it -- so stating it would imply Hibernate enforces something it
    // does not.

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Column(columnDefinition = "text")
    private String bio;

    @Column(name = "website_url", length = 500)
    private String websiteUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.USER;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "poster_verified_at")
    private Instant posterVerifiedAt;

    @Column(name = "last_digest_sent_at", nullable = false)
    private Instant lastDigestSentAt;

    @Column(name = "digest_enabled", nullable = false)
    private boolean digestEnabled = true;
    // Defaults to true in the field as well as in the migration, and the two
    // are not redundant: the column default covers rows written by SQL, this
    // covers rows Hibernate inserts -- which is every registration, because
    // Hibernate names every mapped column in its INSERT and would otherwise
    // send false. A new account must be subscribed (ADR-023).

    @Column(nullable = false, length = 2)
    private String locale = "bg";

    // -----------------------------------------------------------------------
    // What this user follows (ADR-023). Two element collections on an entity
    // in user/, for a feature that lives in subscription/ -- which is a bent
    // package boundary and a deliberate one.
    //
    // JPA requires the entity owning an @ElementCollection to be the one whose
    // primary key the collection table references, and both tables are keyed
    // on app_user.id. The alternative was a `subscription` row per user to own
    // them, rejected in ADR-023 because that row would not exist until someone
    // visited the preferences page, which puts "what does an absent row mean?"
    // into the nightly job's subscriber query. Everything else about these two
    // sets -- the defaulting, the preferences page, the matching rule -- is in
    // subscription/ and digest/.
    //
    // LAZY, and nothing on a request path may touch them. AppUserDetailsService
    // loads this entity on every authenticated request, with
    // open-in-view=false: a template reaching for these would throw
    // LazyInitializationException, which is the point (ADR-013). The digest
    // does not load this entity at all -- it reads projections -- and the
    // preferences page loads them inside its transaction.
    //
    // An EMPTY SET MEANS EVERY VALUE, not none (ADR-023). That substitution
    // happens in SubscriptionService, not here: an accessor that returned the
    // whole enum for an empty collection would be a getter that lies about the
    // rows, and the preferences page needs the real selection in order to tick
    // the right boxes.
    // -----------------------------------------------------------------------

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "subscription_category",
            joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "category", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private Set<Category> subscribedCategories = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "subscription_region",
            joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "region", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private Set<Region> subscribedRegions = new LinkedHashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppUser() { }   // required by JPA, not for your code

    /**
     * @param email   already lowercased and trimmed by the caller; the database
     *                verifies it with ck_app_user_email_lowercase rather than
     *                converting it (the application normalizes, the database
     *                verifies).
     * @param now     the digest watermark's starting point, so the first digest
     *                contains only actions published after the account existed
     *                (ADR-005). Passed in rather than read from Instant.now()
     *                so a test can inject a fixed Clock.
     */
    public AppUser(String email, String passwordHash, String displayName, Instant now) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.lastDigestSentAt = now;
    }

    /**
     * The canonical form of a login address: trimmed, and lowercased under
     * {@link Locale#ROOT}.
     *
     * <p>Static and here, on the entity whose constructor states the
     * contract, because two separate paths have to agree on it exactly --
     * registration, which writes the row, and
     * {@code AppUserDetailsService}, which looks it up. The database holds
     * them to it with ck_app_user_email_lowercase and matches on plain
     * equality through uq_app_user_email, so a path that normalizes
     * differently does not produce a near-miss: it produces a registration
     * that fails on a CHECK, or a login that matches no row.
     *
     * <p>{@code Locale.ROOT} is not decoration. Under a Turkish locale
     * {@code "I".toLowerCase()} is "\u0131", so an address containing I would
     * normalize to something uq_app_user_email cannot match -- a login that
     * works or not depending on the server's default locale.
     *
     * <p>Null becomes the empty string rather than throwing. Both callers
     * receive this from a form, where absent and blank are the same mistake,
     * and an empty address matches no row and fails validation.
     */
    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AppUser that)) return false;
        return id != null && id.equals(that.getId());   // getId(), not that.id
    }

    @Override
    public int hashCode() {
        return AppUser.class.hashCode();
    }

    public boolean isEmailVerified() { return emailVerifiedAt != null; }

    public void markEmailVerified(Instant now) {
        if (emailVerifiedAt == null) this.emailVerifiedAt = now;
    }

    // -----------------------------------------------------------------------
    // Accessors. No setId (the database assigns it), no setCreatedAt or
    // setUpdatedAt: @CreationTimestamp / @UpdateTimestamp own those columns and
    // overwrite anything a setter wrote at flush time.
    // -----------------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    /** The caller lowercases and trims; see the constructor. */
    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public void setWebsiteUrl(String websiteUrl) {
        this.websiteUrl = websiteUrl;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public Instant getPosterVerifiedAt() {
        return posterVerifiedAt;
    }

    public void setPosterVerifiedAt(Instant posterVerifiedAt) {
        this.posterVerifiedAt = posterVerifiedAt;
    }

    public Instant getLastDigestSentAt() {
        return lastDigestSentAt;
    }

    public void setLastDigestSentAt(Instant lastDigestSentAt) {
        this.lastDigestSentAt = lastDigestSentAt;
    }

    public boolean isDigestEnabled() {
        return digestEnabled;
    }

    /**
     * The preferences page writes this, and so does the unsubscribe link --
     * which is the only one of the two that works without logging in
     * (ADR-005).
     *
     * <p>A plain setter rather than {@code subscribe()} / {@code unsubscribe()}
     * named methods, unlike {@link com.fivegoodmins.fivegoodmins.action.Action}'s
     * lifecycle. The rule in those is that {@code published_at} must be
     * written exactly once ever, because the digest watermark treats it as
     * immutable, so a setter would make the pattern unsound. Nothing of that
     * kind is true here: this is a boolean a user may flip as often as they
     * like, in both directions, and {@code setEnabled} and {@code setLocale}
     * next to it are the same shape for the same reason.
     *
     * <p>The watermark itself is the field on this entity that does carry an
     * invariant, and it is deliberately not written through
     * {@link #setLastDigestSentAt} by the digest -- see
     * {@code DigestSubscriberRepository.advanceWatermark}, which moves it in
     * one guarded statement so that it can only ever go forward.
     */
    public void setDigestEnabled(boolean digestEnabled) {
        this.digestEnabled = digestEnabled;
    }

    /**
     * The categories this user follows, as stored. Possibly empty, which means
     * every category rather than none -- {@code SubscriptionService} is where
     * that substitution happens, because the preferences page needs the real
     * selection and the digest needs the effective one (ADR-023).
     *
     * <p>Mutable, and the preferences page replaces its contents rather than
     * assigning a new set, so Hibernate tracks the collection it created.
     */
    public Set<Category> getSubscribedCategories() {
        return subscribedCategories;
    }

    /** See {@link #getSubscribedCategories()}. */
    public Set<Region> getSubscribedRegions() {
        return subscribedRegions;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
