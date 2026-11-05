package com.fivegoodmins.fivegoodmins.action;

/**
 * The public URL of the action listing, in one place.
 *
 * <p>{@code /actions} serves the listing — a structural placeholder for page 1
 * of the frontend brief, which also calls that page the home page. A named path
 * as well as the eventual root, so an email can link to the listing without
 * depending on what {@code /} turns out to be.
 *
 * <p><strong>There is deliberately no {@code detail(id)} here.</strong> An
 * earlier version had one, and the welcome email built a link per action from
 * it — five links to a page that is page 2 of the brief and does not exist, in
 * the first email a confirmed user receives. The constant was defended as
 * fixing the shape page 2 would have to map, which is true and is not worth a
 * delivered dead link: the email now lists the actions as text and carries one
 * working link to the listing. Page 2 adds both the route and the constant,
 * together, with nothing already in anybody's mailbox depending on the answer.
 */
public final class ActionPaths {

    public static final String LIST = "/actions";

    private ActionPaths() {
    }
}
