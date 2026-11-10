package com.fivegoodmins.fivegoodmins.subscription;

import com.fivegoodmins.fivegoodmins.action.Category;
import com.fivegoodmins.fivegoodmins.action.Region;
import com.fivegoodmins.fivegoodmins.user.AppUser;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Set;

/**
 * This feature's reads and writes against {@code app_user} and its two
 * subscription collection tables.
 *
 * <p><strong>{@code Repository}, not {@code JpaRepository}, and that is the
 * reason this interface may exist at all.</strong> {@code AppUser} already has
 * a repository in {@code user/}, and a second {@code JpaRepository} over the
 * same entity would hand this package {@code deleteAll}, {@code findAll} and
 * the rest -- every way there is to write to the user table, offered to the
 * feature that only needs to know what somebody follows. Spring Data's
 * {@code Repository} is the empty marker interface: what this package can do
 * to {@code app_user} is exactly the four methods below. {@code digest/} holds
 * a third one on the same terms, and the narrowness is what keeps three
 * interfaces over one table from being three ways to bypass each other.
 *
 * <p>Nothing here returns {@code AppUser} except {@link #findById}, which the
 * preferences page needs in order to mutate a managed entity inside its own
 * transaction. The entity does not leave {@link SubscriptionService}
 * (ADR-003).
 */
interface SubscriptionRepository extends Repository<AppUser, Long> {

    /**
     * The entity, for the one operation that writes the collections.
     *
     * <p>Element collections cannot be updated without a managed owner:
     * Hibernate replaces them by deleting the rows for that key and inserting
     * the new ones, which it only does for a collection it is tracking. A
     * bulk statement is not available for the same reason -- JPQL has no
     * syntax for writing to an element collection.
     */
    Optional<AppUser> findById(Long id);

    /**
     * The categories one user follows, without loading the user.
     *
     * <p>A join over the element collection returning the values themselves,
     * rather than {@code findById(...).getSubscribedCategories()}: the digest
     * asks this once per subscriber per night and has no use for the other
     * eighteen columns of {@code app_user}, and reading the entity in order to
     * touch a lazy collection is two queries where this is one. It also keeps
     * the entity inside the service layer by never producing one
     * (ADR-003).
     *
     * <p>Returns the rows as they are: <strong>possibly empty, which means
     * every category</strong>. The substitution is
     * {@link SubscriptionFilter#of}, deliberately not here -- a query that
     * invented ten rows nobody stored would be a lie about the table, and the
     * preferences page needs the real selection in order to tick the right
     * boxes.
     */
    @Query("select c from AppUser u join u.subscribedCategories c where u.id = :userId")
    Set<Category> findCategories(@Param("userId") Long userId);

    /** The regions one user follows. See {@link #findCategories}. */
    @Query("select r from AppUser u join u.subscribedRegions r where u.id = :userId")
    Set<Region> findRegions(@Param("userId") Long userId);

    /**
     * Turns the daily digest on or off for one account, and reports whether
     * there was an account to turn it off for.
     *
     * <p><strong>A bulk statement because the unsubscribe link must not need
     * the entity.</strong> That path is unauthenticated (ADR-005): it holds a
     * signed user id and nothing else, so loading an {@code AppUser} would be
     * fetching an entity in order to flip one boolean on it -- and the row may
     * not exist at all, since an unsubscribe token is signed for ten years and
     * the account behind it can have been deleted (ADR-015) or purged
     * (ADR-002) in the meantime.
     *
     * <p><strong>The return value is how that absence is detected.</strong>
     * Zero rows means no such account, which the controller renders as a dead
     * link rather than as a confirmation -- telling somebody they have been
     * unsubscribed when no row was touched is the one wrong answer this page
     * can give. One row means done. There is no third outcome: the statement
     * is unconditional in {@code digest_enabled}, so unsubscribing an account
     * that is already unsubscribed still reports one row, which is what makes
     * a second click on the same link idempotent rather than a dead link
     * (the property {@code SignedTokenService} relies on for having no token
     * table).
     *
     * <p><strong>It does not move {@code updated_at},</strong> because a bulk
     * JPQL statement is executed by the database and Hibernate's
     * {@code @UpdateTimestamp} never runs. Acceptable, and for the reason
     * ADR-019 gives when it exempts those two columns from the clock rule:
     * {@code created_at} and {@code updated_at} are audit metadata that
     * nothing in this application branches on. Worth knowing before anything
     * ever does -- the day a feature reads {@code updated_at} is the day this
     * statement has to set it explicitly, or stop being a bulk statement.
     *
     * <p>No {@code flushAutomatically} and no {@code clearAutomatically},
     * unlike the bulk delete in {@code AppUserRepository}. Both callers here
     * run in a transaction containing this statement and nothing else, and
     * neither has an {@code AppUser} loaded: the preferences page writes
     * through the managed entity instead, and the unsubscribe path never loads
     * one. If a third caller ever arrives holding a user, {@code
     * clearAutomatically} -- or not calling this from there -- is the fix.
     */
    @Modifying
    @Query("update AppUser u set u.digestEnabled = :enabled where u.id = :userId")
    int setDigestEnabled(@Param("userId") Long userId, @Param("enabled") boolean enabled);
}
