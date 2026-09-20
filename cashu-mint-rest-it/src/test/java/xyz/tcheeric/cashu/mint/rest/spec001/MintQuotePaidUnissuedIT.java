package xyz.tcheeric.cashu.mint.rest.spec001;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import xyz.tcheeric.cashu.mint.jpa.InvariantGaugePoller;
import xyz.tcheeric.cashu.mint.jpa.entity.MintQuoteEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.MintQuoteJpaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #460 — the Paid-Unissued invariant, against a live Postgres.
 *
 * <p>A quote in {@code PAID} has had the customer's money settle and accepted.
 * Only an inbound client mint request advances it, so a client that never comes
 * back leaves the payment taken and nothing issued. Two such quotes sat on
 * staging for three weeks with no gauge and no alert.
 *
 * <p>This is the one money-at-risk invariant with no reconciler behind it, and
 * that is not an omission: issuing needs the client's blinded outputs, which
 * the mint never persists. So the gauge is the entire mechanism rather than a
 * cross-check on one, and these tests are the only thing standing between a
 * broken query and a silent three-week stranding.
 */
class MintQuotePaidUnissuedIT extends AbstractMintDurableIT {

    /** Comfortably past the default 1h TTL, so a counted row is counted on its age. */
    private static final Duration WELL_PAST_TTL = Duration.ofHours(6);

    /** Inside the TTL: a client could still be mid-flight. */
    private static final Duration WITHIN_TTL = Duration.ofMinutes(1);

    @Autowired
    MintQuoteJpaRepository mintQuotes;

    @Autowired
    InvariantGaugePoller poller;

    /**
     * Used only to backdate {@code updated_at}. {@code @PrePersist} stamps it
     * with {@code now()} on every write, so the age this invariant measures
     * cannot be set through the entity — and adding a test-only setter to
     * production code to work around that would be worse than one line of SQL
     * here.
     */
    @Autowired
    @Qualifier("mintJpaDataSource")
    javax.sql.DataSource mintDataSource;

    @BeforeEach
    void clean() {
        mintQuotes.deleteAll();
    }

    /**
     * Seeds a quote in {@code state} whose last write was {@code age} ago.
     *
     * <p>{@code updated_at} is set explicitly because the invariant measures
     * time in state, and {@code @PreUpdate} would otherwise stamp it now.
     */
    private String seed(LifecycleState state, Duration age) {
        String quoteId = UUID.randomUUID().toString();
        MintQuoteEntity quote = new MintQuoteEntity();
        quote.setQuoteId(quoteId);
        quote.setAmount(300L);
        quote.setUnit("sat");
        quote.setMintUrl("https://mint.example");
        quote.setPaymentMethod("bolt11");
        quote.setInvoiceId(quoteId);
        quote.setLifecycleState(state);
        quote.setRequestHash("0".repeat(64));
        Instant when = Instant.now().minus(age);
        quote.setCreatedAt(when);
        quote.setUpdatedAt(when);
        mintQuotes.save(quote);
        mintQuotes.flush();
        new JdbcTemplate(mintDataSource).update(
                "UPDATE mint_quote SET created_at = ?, updated_at = ? WHERE quote_id = ?",
                java.sql.Timestamp.from(when), java.sql.Timestamp.from(when), quoteId);
        return quoteId;
    }

    private long gauge() {
        return mintQuotes.countPaidUnissued(Instant.now().minus(Duration.ofHours(1)));
    }

    /** The defect: money accepted, nothing issued, nobody coming back for it. */
    @Test
    void countsAQuoteLeftInPaidPastTheTtl() {
        seed(LifecycleState.PAID, WELL_PAST_TTL);

        assertThat(gauge())
                .as("a quote paid six hours ago with no issuance is stranded")
                .isEqualTo(1L);
    }

    /**
     * A client mid-flight must not page anyone. Without this bound the gauge
     * would read non-zero for every payment in the seconds between the webhook
     * and the mint request, which is every healthy payment there is.
     */
    @Test
    void ignoresAQuotePaidMomentsAgo() {
        seed(LifecycleState.PAID, WITHIN_TTL);

        assertThat(gauge())
                .as("a quote paid a minute ago has a client still coming")
                .isZero();
    }

    /**
     * Only {@code PAID} means money taken and nothing issued. Counting any
     * other state would make the alert fire on healthy traffic, and an alert
     * that cries wolf is one nobody reads when it matters.
     */
    @Test
    void ignoresEveryOtherLifecycleState() {
        for (LifecycleState state : LifecycleState.values()) {
            if (state != LifecycleState.PAID) {
                seed(state, WELL_PAST_TTL);
            }
        }

        assertThat(gauge())
                .as("states other than PAID are not money-taken-nothing-issued")
                .isZero();
    }

    /**
     * The alert must clear when the client finally returns and the quote is
     * issued. A gauge that cannot come back down pages forever and gets muted,
     * which is the same as not having it.
     */
    @Test
    void clearsOnceTheQuoteIsIssued() {
        String quoteId = seed(LifecycleState.PAID, WELL_PAST_TTL);
        assertThat(gauge()).isEqualTo(1L);

        // The client returns: PAID -> ISSUING -> ISSUED, as MintTask drives it.
        assertThat(mintQuotes.casLifecycle(quoteId, LifecycleState.PAID, LifecycleState.ISSUING))
                .isEqualTo(1);
        assertThat(mintQuotes.casLifecycle(quoteId, LifecycleState.ISSUING, LifecycleState.ISSUED))
                .isEqualTo(1);

        assertThat(gauge())
                .as("an issued quote is no longer money owed")
                .isZero();
    }

    /**
     * The poller must actually run this query into the gauge. The query being
     * right is not the same as it being wired, and this invariant has no
     * reconciler to notice if the poller never calls it.
     */
    @Test
    void thePollerRunsTheInvariantWithoutThrowing() {
        seed(LifecycleState.PAID, WELL_PAST_TTL);

        poller.pollTick();

        assertThat(gauge())
                .as("the poll must not have disturbed the underlying data")
                .isEqualTo(1L);
    }
}
