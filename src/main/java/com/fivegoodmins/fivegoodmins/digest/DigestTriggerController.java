package com.fivegoodmins.fivegoodmins.digest;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs the daily digest now, in development, so that the email can be looked
 * at.
 *
 * <p><strong>{@code @Profile("dev")}, so it does not exist in production.</strong>
 * The deployed jar starts with no profile -- pom.xml activates {@code dev} for
 * {@code spring-boot:run} only -- so this controller is never registered there
 * and the path is a 404 on the live site. That is the same argument, and the
 * same arrangement, as {@code shared/dev/StyleGuideController}: the
 * {@code /dev/**} prefix is permitted unconditionally in
 * {@code SecurityConfig} because a security rule that changes shape per profile
 * is a rule nobody can read off that file, and permitting a path nothing serves
 * costs nothing.
 *
 * <p><strong>Why this is worth a class rather than editing the cron.</strong>
 * The job fires at 07:00 Europe/Sofia, and the digest is the one email in this
 * application that cannot be produced by using the site -- registering
 * confirms an address and sends two emails, but nothing a developer or a
 * designer can do in a browser causes a digest. Without this, looking at the
 * flagship email means changing a cron expression, restarting, and remembering
 * to change it back. The frontend brief asks for this email to survive Gmail,
 * Outlook and Apple Mail (§4); that is not checkable against a template nobody
 * can render.
 *
 * <p><strong>It takes no lock, deliberately.</strong> {@code DigestJob} does,
 * because two containers share a schedule during a Railway deploy. This is one
 * developer pressing a button on their own machine, and the useful behaviour
 * there is for the digest to run rather than to report that something else
 * holds the lock. The watermark's own guard still applies -- it only moves
 * forward -- so pressing this twice sends one digest and then nothing.
 *
 * <p><strong>What it does not do: reset anybody's watermark.</strong> A freshly
 * confirmed account's watermark is {@code now} by design (ADR-005), so its
 * first digest is empty and this endpoint reports zero sent. Backdating it is a
 * one-line {@code UPDATE} and belongs in a developer's psql session rather than
 * in an endpoint, because an endpoint that rewinds watermarks is an endpoint
 * that re-sends the back catalogue to everybody, and the only thing standing
 * between it and production would be this annotation.
 *
 * <p><strong>CSRF applies, which makes the curl two steps rather than one.</strong>
 * It is not exempted: a path that skips CSRF because it was inconvenient is the
 * kind of exemption that gets copied to a path where it matters. Fetch a token
 * from any page first.
 *
 * <pre>
 * docker compose exec -T db psql -U app -d fivegoodmins \
 *   -c "update app_user set last_digest_sent_at = now() - interval '1 year'"
 *
 * CSRF=$(curl -s -c /tmp/jar localhost:8080/login \
 *   | grep -o 'name="_csrf" value="[^"]*"' | sed 's/.*value="//;s/"//')
 * curl -s -b /tmp/jar -X POST localhost:8080/dev/digest -H "X-CSRF-TOKEN: $CSRF"
 *
 * open http://localhost:8025
 * </pre>
 *
 * <p>It answers with the run's four counts, so
 * {@code {"subscribers":1,"sent":1,"nothingNew":0,"failed":0}} is the whole
 * report. A freshly confirmed account answers {@code "nothingNew":1} until its
 * watermark is rewound, which is what the {@code UPDATE} above is for.
 *
 * <p>{@code @RestController} and a {@link DigestRun} body rather than a page:
 * the output is four numbers for a terminal, and the thing worth looking at is
 * in Mailpit.
 *
 * <p>POST rather than GET, because it sends email. A GET would be fetched by
 * every browser prefetcher and link scanner pointed at it -- the same reasoning
 * as the unsubscribe confirmation, for the same reason.
 */
@Profile("dev")
@RestController
class DigestTriggerController {

    private final DigestService digests;

    DigestTriggerController(DigestService digests) {
        this.digests = digests;
    }

    @PostMapping(path = "/dev/digest", produces = MediaType.APPLICATION_JSON_VALUE)
    DigestRun sendNow() {
        return digests.sendDailyDigest();
    }
}
