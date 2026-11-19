package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.adapter.out.outbox.RedemptionCutoffOutboxHandler;
import xyz.tcheeric.cashu.mint.admin.application.port.out.KeySetInventoryPort;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRedemptionCutoffPort;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OutboxRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.RedemptionCutoffRequestRepository.CutoffRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.out.RedemptionCutoffRequestRepository.Status;
import xyz.tcheeric.cashu.mint.admin.application.port.out.TransactionManager;
import xyz.tcheeric.cashu.mint.admin.application.service.ManageRedemptionCutoffInteractor;
import xyz.tcheeric.cashu.mint.admin.application.service.ManageRedemptionCutoffInteractor.CutoffRequestException;
import xyz.tcheeric.cashu.mint.admin.application.service.ManageRedemptionCutoffInteractor.Kind;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.OutboxMessage;

/**
 * A redemption cutoff is requested by one Operator, approved by another, delivered by the outbox,
 * and every step lands on the audit trail (cashu-mint#551).
 */
class RedemptionCutoffWorkflowTest {

    private static final UUID MINT_UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final MintId MINT = MintId.of(MINT_UUID);
    private static final String ACTIVE = "01f034be00000000";
    private static final String ARCHIVED = "01ef5b8300000000";
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");
    private static final Instant IN_14_DAYS = NOW.plus(Duration.ofDays(14));

    private JdbcRedemptionCutoffRequestRepository requests;
    private final List<OutboxMessage> outbox = new ArrayList<>();
    private final List<String> delivered = new ArrayList<>();
    private ManageRedemptionCutoffInteractor interactor;

    @BeforeEach
    void setUp() {
        requests = new JdbcRedemptionCutoffRequestRepository(H2TestDataSourceFactory.createDataSource());
        final KeySetInventoryPort inventory = new KeySetInventoryPort() {
            @Override
            public List<VaultKeySet> listByMint(final UUID mintId) {
                return List.of(new VaultKeySet(ACTIVE, "sat", NOW, false, 0),
                    new VaultKeySet(ARCHIVED, "sat", NOW.minusSeconds(86_400), true, 0));
            }

            @Override
            public List<Denomination> listDenominations(final UUID mintId, final String keySetId) {
                return List.of();
            }
        };
        final OutboxRepository outboxRepository = new OutboxRepository() {
            @Override
            public void append(final OutboxMessage message) {
                outbox.add(message);
            }

            @Override
            public List<OutboxMessage> findPending(final Instant availableBefore, final int limit) {
                return List.copyOf(outbox);
            }

            @Override
            public void markDispatched(final UUID eventId, final Instant dispatchedAt) {
            }

            @Override
            public void recordFailure(final UUID eventId, final Instant attemptAt, final Instant nextAttemptAt) {
            }
        };
        final TransactionManager inline = new TransactionManager() {
            @Override
            public <T> T execute(final Supplier<T> action) {
                return action.get();
            }
        };
        interactor = new ManageRedemptionCutoffInteractor(requests, inventory, outboxRepository, inline,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aCutoffCannotBeRequestedOnTheActiveKeyset() {
        assertThatThrownBy(() -> interactor.request(MINT, ACTIVE, IN_14_DAYS, "unseeded", "op-1"))
            .isInstanceOfSatisfying(CutoffRequestException.class, e -> assertThat(e.kind()).isEqualTo(Kind.CONFLICT));
        assertThat(requests.findByMintId(MINT)).isEmpty();
    }

    @Test
    void aCutoffNeedsAtLeastADayOfNotice() {
        assertThatThrownBy(() -> interactor.request(MINT, ARCHIVED, NOW.plus(Duration.ofHours(2)), "soon", "op-1"))
            .isInstanceOfSatisfying(CutoffRequestException.class, e -> assertThat(e.kind()).isEqualTo(Kind.INVALID));
    }

    @Test
    void theRequesterCannotApproveTheirOwnCutoff() {
        final CutoffRequest pending = interactor.request(MINT, ARCHIVED, IN_14_DAYS, "unseeded", "op-1");

        assertThatThrownBy(() -> interactor.approve(pending.requestId(), "op-1"))
            .isInstanceOfSatisfying(CutoffRequestException.class, e -> assertThat(e.kind()).isEqualTo(Kind.FORBIDDEN));
        assertThat(outbox).isEmpty();
    }

    @Test
    void anApprovedCutoffIsQueuedDeliveredAndAuditedStepByStep() {
        final CutoffRequest pending = interactor.request(MINT, ARCHIVED, IN_14_DAYS, "unseeded", "op-1");
        assertThat(pending.status()).isEqualTo(Status.PENDING_APPROVAL);
        assertThat(outbox).as("nothing reaches the mint before approval").isEmpty();

        final CutoffRequest approved = interactor.approve(pending.requestId(), "op-2");
        assertThat(approved.status()).isEqualTo(Status.APPROVED);
        assertThat(approved.decidedBy()).isEqualTo("op-2");
        assertThat(outbox).singleElement()
            .satisfies(m -> assertThat(m.eventType()).isEqualTo(ManageRedemptionCutoffInteractor.CUTOFF_APPROVED_EVENT));

        final MintRedemptionCutoffPort mint = (keysetId, redeemUntil, reason, setBy) ->
            delivered.add(keysetId + "@" + redeemUntil);
        final RedemptionCutoffOutboxHandler handler =
            new RedemptionCutoffOutboxHandler(requests, mint, Clock.fixed(NOW, ZoneOffset.UTC));
        handler.handle(outbox.get(0));
        handler.handle(outbox.get(0));

        assertThat(delivered).as("a redelivered message does not apply twice").containsExactly(ARCHIVED + "@" + IN_14_DAYS);
        assertThat(requests.findById(pending.requestId()).orElseThrow().status()).isEqualTo(Status.APPLIED);
        assertThat(requests.findAudit()).extracting(e -> e.action() + ":" + e.actor())
            .containsExactlyInAnyOrder("CUTOFF_REQUESTED:op-1", "CUTOFF_APPROVED:op-2", "CUTOFF_APPLIED:mint-admin");
    }

    @Test
    void aCutoffTheMintRefusesEndsFailedWithTheMintsReason() {
        final CutoffRequest pending = interactor.request(MINT, ARCHIVED, IN_14_DAYS, "unseeded", "op-1");
        interactor.approve(pending.requestId(), "op-2");
        final MintRedemptionCutoffPort mint = (keysetId, redeemUntil, reason, setBy) -> {
            throw new MintRedemptionCutoffPort.Rejected("mint answered 409: keyset_active");
        };

        new RedemptionCutoffOutboxHandler(requests, mint, Clock.fixed(NOW, ZoneOffset.UTC)).handle(outbox.get(0));

        final CutoffRequest failed = requests.findById(pending.requestId()).orElseThrow();
        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.outcome()).contains("keyset_active");
    }

    @Test
    void aRejectedRequestIsNeverDelivered() {
        final CutoffRequest pending = interactor.request(MINT, ARCHIVED, IN_14_DAYS, "unseeded", "op-1");

        interactor.reject(pending.requestId(), "op-2", "window too short");

        assertThat(requests.findById(pending.requestId()).orElseThrow().status()).isEqualTo(Status.REJECTED);
        assertThat(outbox).isEmpty();
        assertThatThrownBy(() -> interactor.approve(pending.requestId(), "op-3"))
            .isInstanceOfSatisfying(CutoffRequestException.class, e -> assertThat(e.kind()).isEqualTo(Kind.CONFLICT));
    }
}
