package com.fivegoodmins.fivegoodmins.shared.email;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.ISpringTemplateEngine;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a template, a model and a recipient's locale into an {@link
 * EmailMessage}.
 *
 * <p>Separate from both the sender and the feature that wants the email, for
 * two reasons worth stating. It keeps every user-facing string in the bundle
 * and the templates (ADR-008) rather than letting a convenient
 * {@code "Здравейте, " + name} appear in Java the first time one is in a
 * hurry. And it is the only place that knows an email is two bodies, so the
 * three emails of the MVP cannot each decide differently.
 *
 * <p><strong>The locale is the recipient's, from {@code app_user.locale}.</strong>
 * Not {@code LocaleContextHolder}, which is the interface language of whoever
 * happens to be making the request, and not the server default: the nightly
 * digest has no request at all (ADR-005), and an after-commit listener runs on
 * a pool thread that inherits nothing. Passing it in explicitly is what makes
 * those two cases the same code.
 */
@Component
public class EmailComposer {

    /**
     * The variable every template gets without asking: the site's own URL, for
     * the footer link that each of the three emails carries.
     *
     * <p>Added here rather than by each caller because it is the same value
     * every time and comes from configuration, not from the event -- and a
     * footer link that is missing from one email because one model forgot it is
     * the sort of defect nobody notices for a month.
     */
    private static final String SITE_URL = "siteUrl";

    private final ISpringTemplateEngine templates;
    private final MessageSource messages;
    private final EmailProperties properties;

    EmailComposer(ISpringTemplateEngine templates, MessageSource messages, EmailProperties properties) {
        this.templates = templates;
        this.messages = messages;
        this.properties = properties;
    }

    /**
     * @param model variables for both bodies. The same model renders each, so a
     *              variable the HTML uses and the text forgets is a difference
     *              in content rather than a difference in what was available.
     * @throws org.thymeleaf.exceptions.TemplateInputException     if either
     *              template is missing -- loud on purpose; a missing plain-text
     *              part is a half-built email, not a degraded one
     * @throws org.springframework.context.NoSuchMessageException if the subject
     *              key is absent for this locale
     */
    public EmailMessage compose(String to, Locale locale, EmailTemplate template, Map<String, Object> model) {
        return compose(to, locale, template, model, null);
    }

    /**
     * The same, for an email whose subject takes {@code MessageFormat}
     * arguments.
     *
     * <p><strong>Added for the digest, and for a reason that is about mail
     * clients rather than about copy.</strong> The other three emails have
     * fixed subjects and are sent once each. The digest goes to the same
     * address every morning, and Gmail, Outlook and Apple Mail all thread
     * consecutive messages that share a subject -- so a constant subject means
     * a month of digests collapsing into one collapsed conversation, with
     * yesterday's unread. Putting the number of new actions in the subject
     * makes it vary, and is also the one piece of information that decides
     * whether this particular morning's email is worth opening.
     *
     * <p>An overload rather than a changed signature, so the three existing
     * callers are untouched and the common case stays the shorter call.
     *
     * @param subjectArguments arguments for the subject key, or null for none.
     *                         <strong>Never user-supplied text:</strong> the
     *                         subject is the one field in a message where a
     *                         line break ends the field and begins a new
     *                         header, which is why {@code EmailMessage}
     *                         rejects one. A count is safe; a display name is
     *                         not, and belongs in a body where the template
     *                         engine escapes it.
     */
    public EmailMessage compose(String to, Locale locale, EmailTemplate template,
                                Map<String, Object> model, Object[] subjectArguments) {
        Map<String, Object> variables = new HashMap<>(model);
        variables.put(SITE_URL, properties.baseUrl());
        Context context = new Context(locale, variables);
        return new EmailMessage(
                to,
                messages.getMessage(template.subjectKey(), subjectArguments, locale),
                templates.process(template.name(), context),
                templates.process(template.textName(), context));
    }
}
