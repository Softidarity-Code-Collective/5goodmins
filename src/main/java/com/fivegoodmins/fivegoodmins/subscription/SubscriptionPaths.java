package com.fivegoodmins.fivegoodmins.subscription;

/**
 * The URL paths of the subscription feature, in one place because three files
 * that cannot see each other have to agree on them: the controllers here, the
 * digest's email templates in {@code digest/}, and {@code SecurityConfig} in
 * {@code shared.security}, which decides who may reach them.
 *
 * <p><strong>{@link #UNSUBSCRIBE} is a wire format, and it is the worst one in
 * the application to get wrong.</strong> The same rule as {@code AuthPaths} and
 * {@code TokenPurpose}, but with a longer tail: a verification link is dead in
 * seven days, whereas an unsubscribe link lives in every digest ever sent and
 * its token is signed for ten years
 * ({@code TokenPurpose.UNSUBSCRIBE}). Renaming this constant breaks the opt-out
 * in every digest sitting in every mailbox, which is the one link the EU
 * requires to keep working. The rename is a redirect from the old path, never
 * an edit here.
 *
 * <p>In {@code subscription} rather than {@code shared}, unlike
 * {@code AuthPaths}. {@code AuthPaths} is in {@code shared} because the
 * registration flow spans {@code user} and {@code shared.security}, which under
 * ADR-003 do not depend on each other. These paths have one owning feature, and
 * {@code SecurityConfig} already imports {@code ActionPaths} from
 * {@code action} for exactly this reason -- the web security configuration
 * naming the paths features expose is the dependency direction that does not
 * create a cycle.
 */
public final class SubscriptionPaths {

    /**
     * Page 13 of the frontend brief: pick categories and regions, toggle the
     * daily digest. GET renders, POST saves. Authenticated -- this is the
     * signed-in half of a decision whose signed-out half is
     * {@link #UNSUBSCRIBE}.
     */
    public static final String PREFERENCES = "/preferences";

    /**
     * Page 9 of the frontend brief, reached from a digest email and working
     * with no login at all. Carries {@link #TOKEN_PARAMETER}.
     *
     * <p>GET renders a confirmation, POST performs the unsubscribe. That is two
     * steps where ADR-005 originally specified one-click, and the reason is in
     * {@code UnsubscribeController}: mail clients and security scanners
     * prefetch links in email -- ADR-005 already relies on that being true when
     * it explains how the welcome email is sent exactly once -- and a GET that
     * unsubscribes would be performed by the prefetch rather than by the
     * reader.
     */
    public static final String UNSUBSCRIBE = "/unsubscribe";

    /**
     * The query parameter on {@link #UNSUBSCRIBE}, and the hidden field the
     * confirmation form posts back.
     *
     * <p>Spelled the same as {@code AuthPaths.TOKEN_PARAMETER} and deliberately
     * not shared with it. The two are independent wire formats that happen to
     * follow one convention: a verification link and an unsubscribe link can be
     * renamed separately, and a single constant would make them look like one
     * decision that has to move together. The duplication is one word; the
     * false coupling would be the kind that is only discovered by breaking
     * links already in mailboxes.
     */
    public static final String TOKEN_PARAMETER = "token";

    private SubscriptionPaths() {
    }
}
