package com.fivegoodmins.fivegoodmins.digest;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.action.ActionType;
import com.fivegoodmins.fivegoodmins.action.Category;
import com.fivegoodmins.fivegoodmins.action.Region;
import com.fivegoodmins.fivegoodmins.subscription.SubscriptionFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Proves that {@link DigestQueries#digestFor} really does run its three queries
 * inside one transaction that it starts itself.
 *
 * <p><strong>This test exists because the claim was previously only a
 * javadoc.</strong> {@code digestFor} carries
 * {@code @Transactional(readOnly = true)} and its documentation uses that to
 * promise the badge queries see the same snapshot as the query that chose the
 * ids. Two things made that worth proving rather than asserting. The method was
 * package-private, and Spring's proxy-based AOP advises a non-public method only
 * under class-based (CGLIB) proxies -- Boot's default, so it did work, but not
 * what the reference documentation promises, and it reverts silently under
 * {@code spring.aop.proxy-target-class=false}. And this feature had already
 * shipped one bug of exactly that shape: a {@code @Modifying} query with no
 * transaction, which no test could see because every other test supplied one.
 *
 * <p>A second unproven transaction assumption in the same feature was the wrong
 * note to end on, so: the method is {@code public}, and this asserts the
 * boundary from the inside.
 *
 * <p><strong>How it reads the boundary.</strong>
 * {@code TransactionSynchronizationManager.getCurrentTransactionName()} returns
 * the name of the transaction definition that actually began the current
 * transaction, which for an annotation-driven boundary is the fully qualified
 * method. So if {@code digestFor} starts it, every one of the three repository
 * calls reports {@code ...DigestQueries.digestFor}. If it does not, each
 * repository call starts its own -- Spring Data's {@code SimpleJpaRepository} is
 * itself {@code @Transactional(readOnly = true)} -- and the names come back as
 * the repository's methods instead. That distinction is the whole point: "a
 * transaction is active" would be true either way, and is not the claim.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional} on the class. An
 * ambient test transaction would be the outer boundary, every name would be the
 * test's, and this would pass whatever {@code digestFor} was annotated with --
 * the same blind spot that hid the watermark bug.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DigestQueriesTransactionTest {

    private static final Instant NOW = Instant.parse("2026-11-05T07:00:00Z");

    private static final String EXPECTED_BOUNDARY =
            "com.fivegoodmins.fivegoodmins.digest.DigestQueries.digestFor";

    @Autowired DigestQueries digests;

    /**
     * Stubbed, not spied, and not running the real SQL.
     *
     * <p>A spy was the first attempt and cannot work: Spring Data hands over an
     * interface proxy, so {@code callRealMethod} reports "cannot call abstract
     * real method on java object". It is also unnecessary. What creates the
     * boundary is the transactional proxy around {@code digestFor}, not
     * anything the repository returns -- so stubs measure it exactly, and
     * {@code DigestQueriesTest} is where the SQL itself is tested against a
     * real database.
     *
     * <p>{@code findMatching} returns one fabricated row on purpose: an empty
     * result makes {@code digestFor} return before the badge queries, and then
     * only one of the three boundaries would be observed. One row is what makes
     * this test able to say "all three".
     */
    @MockitoBean
    DigestActionRepository actions;

    private final List<String> boundaries = new ArrayList<>();

    private void recordTransactionNames() {
        when(actions.findMatching(any(), any(), any(), any(), any(), anyInt()))
                .thenAnswer(call -> {
                    boundaries.add(TransactionSynchronizationManager.getCurrentTransactionName());
                    return List.of(new DigestActionRow(1L, "Deistvie", ActionType.PETITION, "Opisanie"));
                });

        when(actions.findCategories(any())).thenAnswer(call -> {
            boundaries.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return List.of();
        });

        when(actions.findRegions(any())).thenAnswer(call -> {
            boundaries.add(TransactionSynchronizationManager.getCurrentTransactionName());
            return List.of();
        });
    }

    @Test
    void startsOneTransactionOfItsOwnAndRunsEveryQueryInIt() {
        recordTransactionNames();

        // No assertions about content: this is about the boundary, not the
        // result. The stub returns one row so that the two badge queries run
        // too, which is what makes "all three in one transaction" observable
        // rather than just "the first one was in a transaction".
        digests.digestFor(
                NOW.minus(365, ChronoUnit.DAYS),
                NOW.minus(5, ChronoUnit.MINUTES),
                NOW,
                new SubscriptionFilter(EnumSet.allOf(Category.class), EnumSet.allOf(Region.class)));

        // All three queries, in order: the matching query then the two badge
        // queries. If this is 1, digestFor returned early and the test is not
        // measuring what it claims to.
        assertThat(boundaries).as("the queries digestFor issued").hasSize(3);

        // The assertion that matters. Every query names digestFor as the
        // boundary, so they are all in the one transaction it opened -- not one
        // transaction each, which is what Spring Data would otherwise give them.
        assertThat(boundaries)
                .as("each query's transaction boundary")
                .allSatisfy(name -> assertThat(name).isEqualTo(EXPECTED_BOUNDARY));
    }

    @Test
    void isNotRelyingOnACallerToSupplyTheTransaction() {
        recordTransactionNames();

        // The negative half, and the reason this class has no @Transactional:
        // called with nothing around it, the boundary still exists. Before
        // digestFor was annotated -- or if the annotation stops being applied --
        // this comes back as the repository's own method name and fails here
        // rather than in production, nightly, as an entry with no badges.
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("no ambient transaction before the call")
                .isFalse();

        digests.digestFor(
                NOW.minus(365, ChronoUnit.DAYS),
                NOW.minus(5, ChronoUnit.MINUTES),
                NOW,
                new SubscriptionFilter(EnumSet.allOf(Category.class), EnumSet.allOf(Region.class)));

        assertThat(boundaries).first().isEqualTo(EXPECTED_BOUNDARY);

        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("and none left open afterwards")
                .isFalse();
    }
}
