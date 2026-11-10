package com.fivegoodmins.fivegoodmins.subscription;

import com.fivegoodmins.fivegoodmins.action.Category;
import com.fivegoodmins.fivegoodmins.action.Region;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What page 13 of the frontend brief posts, and what it redisplays: the
 * categories and regions a user follows, and whether they want the digest.
 *
 * <p><strong>A JavaBean rather than a record, for the reason
 * {@code RegistrationForm} is one</strong> -- Thymeleaf's {@code th:field}
 * reads the backing object through {@code java.beans.Introspector}, which does
 * not recognise a record accessor as a getter. This form is redisplayed on
 * every GET with the stored selection already in it, which is the case that
 * needs the getters.
 *
 * <p><strong>No {@code @Size} on either set, and both lower bounds are
 * zero.</strong> This is where the preferences page differs most from the
 * submit form, which caps an action at three categories and three regions
 * (ADR-009, ADR-017). Those caps exist to stop a poster tagging one petition
 * into every corner of the site; nothing of the kind applies to a reader
 * choosing what to be told about. Ten categories is the whole vocabulary and a
 * perfectly ordinary choice, and <strong>zero is the default and means the
 * same thing</strong> -- an empty selection is read as every value, not as
 * none ({@link SubscriptionFilter}). So there is no number here to validate
 * against, and an empty POST is a valid save rather than an error.
 *
 * <p><strong>The only input that can fail is a value outside the enum</strong>,
 * and Spring's own conversion rejects that before any validation would run:
 * a POST naming {@code category=BANANA} is a binding error on the field. That
 * cannot come from the rendered page, whose checkboxes are built from
 * {@code TaxonomyModel}'s lists, so the case is a hand-made request rather
 * than a user mistake -- which is why it gets the binding error's generic
 * treatment and no message of its own in the bundle. The database says the
 * same thing a second time through {@code ck_subscription_category_value},
 * for the paths that never pass through here.
 *
 * <p>Sets rather than lists, mirroring the collections they are saved into:
 * a duplicated checkbox value collapses instead of violating
 * {@code pk_subscription_category}, and the primary key means the table could
 * not hold the duplicate anyway.
 */
public class SubscriptionForm {

    /**
     * Initialised to an empty set rather than left null, because that is what
     * a POST with no checkboxes ticked has to produce.
     *
     * <p>A browser sends nothing at all for an unchecked checkbox group -- not
     * an empty value, no parameter -- so Spring leaves the property untouched.
     * Null here would then mean "unticked everything", which is a legitimate
     * save, and it would reach {@link SubscriptionService} as a null to be
     * guarded against. An empty set means it already.
     *
     * <p>{@link LinkedHashSet}, so a redisplayed form and a re-saved selection
     * keep the order the page offered rather than acquiring a hash order.
     */
    private Set<Category> categories = new LinkedHashSet<>();

    private Set<Region> regions = new LinkedHashSet<>();

    /**
     * Defaults to false, which is the opposite of the column's default, and is
     * correct here for the same reason the sets default to empty: an unchecked
     * checkbox sends no parameter. A GET fills this in from the stored value
     * before rendering, so the box a user sees is ticked when their digest is
     * on; what this initialiser governs is only what an unticked POST means,
     * and an unticked digest box means off.
     */
    private boolean digestEnabled;

    public Set<Category> getCategories() {
        return categories;
    }

    public void setCategories(Set<Category> categories) {
        this.categories = categories == null ? new LinkedHashSet<>() : categories;
    }

    public Set<Region> getRegions() {
        return regions;
    }

    public void setRegions(Set<Region> regions) {
        this.regions = regions == null ? new LinkedHashSet<>() : regions;
    }

    public boolean isDigestEnabled() {
        return digestEnabled;
    }

    public void setDigestEnabled(boolean digestEnabled) {
        this.digestEnabled = digestEnabled;
    }
}
