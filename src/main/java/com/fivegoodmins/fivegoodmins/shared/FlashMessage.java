package com.fivegoodmins.fivegoodmins.shared;

import java.util.Locale;

/**
 * One message carried across a redirect, for the flash area in the layout
 * (frontend brief, sections 5 and 6).
 *
 * <p>A controller puts one on the model under the name {@code flash} --
 * {@code attributes.addFlashAttribute("flash", new FlashMessage(...))} --
 * and {@code fragments/flash.html}, called from the layout, renders it.
 *
 * <p><strong>A message key, never a message.</strong> The text is resolved in
 * the template, which is the only place that knows the request locale --
 * resolving it here would mean a controller holding a {@code MessageSource}
 * and the bundle rule (ADR-008) surviving on everyone remembering it.
 *
 * <p>Flash scope, so the message survives exactly one redirect and is gone
 * from the session afterwards. Putting it in the normal model instead would
 * lose it at the redirect; putting it in the session by hand would leave it
 * to be shown twice.
 *
 * <p><strong>No {@code success()} / {@code error()} / {@code info()} helpers
 * here, deliberately.</strong> They existed and nothing called them: no
 * controller in the application currently redirects with something to say.
 * The obvious-looking candidate is the registration redirect, and it is the
 * one place this must never go -- both outcomes of that form render an
 * identical page on purpose (ADR-005), so a flash message that differed
 * between them would rebuild the account-enumeration oracle the whole design
 * avoids. The helpers come back with the first controller that has a message
 * to carry.
 *
 * @param level      which of the three the layout renders; also the message
 *                   key for the screen-reader label, as {@code flash.<level>.label}
 * @param messageKey the bundle key of the text itself
 * @param arguments  MessageFormat arguments for that key, usually none
 */
public record FlashMessage(Level level, String messageKey, Object... arguments) {

    /** The three the design system has to cover. */
    public enum Level {
        SUCCESS, ERROR, INFO;

        /**
         * Lowercased for the key and for a CSS class, so neither is built in
         * the template.
         *
         * <p>{@code Locale.ROOT}, not the default locale. On a JVM running
         * with a Turkish locale {@code "INFO".toLowerCase()} is
         * {@code "ınfo"} with a dotless i, which makes the key
         * {@code flash.ınfo.label}, which does not exist, which is a
         * {@code NoSuchMessageException} and a 500 -- on the page that was
         * trying to tell the user something went well. Same trap as the
         * address normalization in the registration service, same fix.
         */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
