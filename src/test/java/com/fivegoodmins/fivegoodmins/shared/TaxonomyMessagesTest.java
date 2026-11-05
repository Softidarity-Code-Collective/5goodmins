package com.fivegoodmins.fivegoodmins.shared;

import com.fivegoodmins.fivegoodmins.action.ActionStatus;
import com.fivegoodmins.fivegoodmins.action.ActionType;
import com.fivegoodmins.fivegoodmins.action.Category;
import com.fivegoodmins.fivegoodmins.action.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.context.MessageSource;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Every constant of the four closed vocabularies has display text in both
 * languages.
 *
 * <p><strong>Why this is not covered by {@link MessageBundlesTest}.</strong>
 * That one proves the two bundles say the same things as each other; it cannot
 * notice that both are missing {@code region.KYUSTENDIL}, because a key absent
 * from both is still a key absent from both symmetrically. The thing that
 * knows which keys must exist is the Java enum, and this is the only test that
 * reads it.
 *
 * <p>The failure it prevents is not cosmetic. Locale fallback is off
 * (MessageSourceConfig), so an unresolvable key is a {@code
 * NoSuchMessageException} rather than a blank -- which means adding a region,
 * a category or a status without its two lines is a 500 on the filter sidebar,
 * the submit form and the admin queue at once. The enums are closed sets that
 * change maybe twice in the project's life, and this is the test that makes
 * those two times safe.
 *
 * <p>Also the reason the key convention is {@code <prefix>.<CONSTANT>}: it
 * lets a template build the key from the value
 * ({@code #{'region.' + ${region}}}) and lets this test build it from the
 * enum. A hand-written mapping in either place would be a third list to keep
 * in step.
 *
 * <p>No Spring context, for the same reason {@code MessageBundlesTest} has
 * none: the configuration class is a plain object and this is two sets of
 * strings.
 */
class TaxonomyMessagesTest {

    private static final Locale BULGARIAN = Locale.forLanguageTag("bg");
    private static final Locale ENGLISH = Locale.forLanguageTag("en");

    private final MessageSource messages = new MessageSourceConfig().messageSource();

    /**
     * The four vocabularies and the key prefix each one is rendered through.
     * {@code action.cta.} is a fifth entry over the same enum as
     * {@code action.type.}: the outbound button needs a label per type
     * ("Sign the petition" is wrong on a survey), and a type added without one
     * breaks the detail page rather than a badge.
     */
    private static Stream<Object[]> vocabularies() {
        return Stream.of(
                new Object[]{"action.type.", ActionType.values()},
                new Object[]{"action.cta.", ActionType.values()},
                new Object[]{"action.status.", ActionStatus.values()},
                new Object[]{"category.", Category.values()},
                new Object[]{"region.", Region.values()});
    }

    @TestFactory
    List<DynamicTest> everyConstantHasDisplayTextInBothLanguages() {
        return vocabularies()
                .flatMap(vocabulary -> Arrays.stream((Enum<?>[]) vocabulary[1])
                        .map(constant -> (String) vocabulary[0] + constant.name()))
                .map(key -> DynamicTest.dynamicTest(key, () -> {
                    assertThatNoException()
                            .as("%s resolves in Bulgarian", key)
                            .isThrownBy(() -> messages.getMessage(key, null, BULGARIAN));
                    assertThatNoException()
                            .as("%s resolves in English", key)
                            .isThrownBy(() -> messages.getMessage(key, null, ENGLISH));
                }))
                .toList();
    }

    @Test
    void theDisplayTextIsNeverTheConstantItself() {
        // The mistake this catches is a placeholder line left behind --
        // region.VIDIN=VIDIN resolves, so the test above passes, and the page
        // shows an English identifier to a Bulgarian reader. ADR-008 is that
        // the constant is never displayed.
        vocabularies().forEach(vocabulary -> {
            for (Enum<?> constant : (Enum<?>[]) vocabulary[1]) {
                String key = vocabulary[0] + constant.name();
                assertThat(messages.getMessage(key, null, BULGARIAN))
                        .as("%s in Bulgarian", key)
                        .isNotEqualTo(constant.name());
            }
        });
    }

    @Test
    void theBulgarianTextIsActuallyBulgarian() {
        // Cyrillic, not a copy of the English line someone meant to come back
        // to. Region names are the likely offender, since several of them are
        // near-identical transliterations.
        for (Region region : Region.values()) {
            assertThat(messages.getMessage("region." + region, null, BULGARIAN))
                    .as("region.%s in Bulgarian", region)
                    .containsPattern("\\p{IsCyrillic}");
        }
    }
}
