package xyz.tcheeric.cashu.mint.jpa.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.jpa.MeltSagaReconciler;
import xyz.tcheeric.cashu.mint.jpa.SwapHoldReconciler;
import xyz.tcheeric.cashu.mint.jpa.VoucherFundingReconciler;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.MintQuoteJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.SwapHoldJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherQuoteJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;

/**
 * Issue #461 — every value-bearing state machine must either sweep its
 * non-terminal states on a schedule, or say in writing why it cannot strand.
 *
 * <p>#459 and #460 were the same defect twice: a transition that must happen,
 * left to something that might not happen, with nothing reconciling the
 * difference. The audit that followed produced a rule with no counter-example
 * in this codebase — <em>every machine with a scheduled reconciler sits at
 * zero stranded rows, and every machine without one is stranded</em> — so the
 * rule is worth enforcing rather than remembering.
 *
 * <h2>Why the obvious check would have certified the bug</h2>
 *
 * <p>The natural rule is "every non-terminal state is mentioned by some
 * reconciler". It would have passed green while #459 was live: {@code
 * UNFUNDED} appears in {@code attachFundingAndAdvance}, the method the
 * <em>client</em> calls. A mention-based check finds it and reports coverage.
 *
 * <p>What discriminates is a <strong>time-bounded</strong> query: a method
 * taking an {@link Instant} cutoff and returning a collection. No request path
 * needs "find everything older than X", because a client already knows which
 * row it is asking about. Only a sweep does.
 *
 * <h2>The exemption list is the point</h2>
 *
 * <p>A rule with no exemption mechanism gets deleted the first time it is
 * inconvenient; one with unexplained exemptions becomes decorative. Each entry
 * here carries a reason, because writing that sentence is the thinking that
 * was missing in #459.
 *
 * <p>"No correct resolution exists" is a first-class reason. #460 is the
 * worked example: a {@code PAID} mint quote cannot be swept, because issuance
 * needs the client's blinded outputs and the mint never stores them. Expiring
 * it would bar the customer from money already taken. The honest answer is a
 * gauge and an alert, not a reconciler, and this rule must be able to say so
 * without anyone being tempted to write a sweep that does damage.
 */
@DisplayName("#461 — non-terminal states are swept on a schedule, or exempt with a reason")
class ReconcilerCoverageArchTest {

    /**
     * A state machine, its repository port, and the scheduled class that
     * sweeps it. Adding a machine here without a sweep or an exemption fails.
     */
    private record Machine(String name, Class<?> stateEnum, Class<?> repository, Class<?> scheduler) {
    }

    private static final List<Machine> MACHINES = List.of(
            new Machine("MeltSagaState", MeltSagaState.class,
                    MeltSagaJpaRepository.class, MeltSagaReconciler.class),
            new Machine("VoucherLifecycleState", VoucherLifecycleState.class,
                    VoucherQuoteJpaRepository.class, VoucherFundingReconciler.class),
            new Machine("SwapHoldPhase", SwapHoldPhase.class,
                    SwapHoldJpaRepository.class, SwapHoldReconciler.class),
            new Machine("MintQuote.LifecycleState", MintQuote.LifecycleState.class,
                    MintQuoteJpaRepository.class, null));

    /**
     * Non-terminal states that need no sweep, each with the reason it cannot
     * strand. Keyed {@code Enum.CONSTANT}.
     */
    private static final Map<String, String> EXEMPT = new LinkedHashMap<>(Map.of(
            "VoucherLifecycleState.ISSUING",
            "sub-second transient inside one MintTask call; a stall is a crash, and the "
                    + "ISSUING->FAILED path covers it rather than a sweep",
            "MintQuote.LifecycleState.ISSUING",
            "same shape as VoucherLifecycleState.ISSUING: transient within a single call",
            "MintQuote.LifecycleState.UNPAID",
            "no money has been taken, so there is nothing to strand; an abandoned quote "
                    + "simply expires",
            "MintQuote.LifecycleState.PENDING",
            "the provider has acknowledged an intent but not settled it; the webhook drives "
                    + "the transition and a never-settled intent owes the customer nothing",
            "MintQuote.LifecycleState.PAID",
            "NO CORRECT RESOLUTION EXISTS (#460). Issuance needs the client's blinded "
                    + "outputs and the mint never persists them, so nothing can complete this "
                    + "without the client returning. Expiring it would permanently bar the "
                    + "customer from money already taken, because issuance CASes from PAID. "
                    + "The remedy is cashu_mint_quote_paid_unissued plus an alert, and a "
                    + "reconciler here would do damage rather than prevent it.",
            "SwapHoldPhase.SIGNING",
            "transient within one swap call, and SwapHoldReconciler sweeps HELD, which is "
                    + "the phase a crashed swap actually rests in"));

    @Test
    @DisplayName("every non-terminal state is swept on a schedule or exempt with a written reason")
    void everyNonTerminalStateIsSweptOrExemptWithAReason() {
        List<String> unaccounted = new ArrayList<>();

        for (Machine machine : MACHINES) {
            for (Object constant : machine.stateEnum().getEnumConstants()) {
                if (isTerminal(constant)) {
                    continue;
                }
                String key = machine.name() + "." + ((Enum<?>) constant).name();
                if (EXEMPT.containsKey(key)) {
                    continue;
                }
                if (machine.scheduler() == null || sweepMethods(machine.repository()).isEmpty()) {
                    unaccounted.add(key);
                }
            }
        }

        assertThat(unaccounted)
                .as("""
                        A non-terminal state with no scheduled sweep is #459 again: a transition \
                        that must happen, left to something that might not. Either add a \
                        time-bounded sweep called by a @Scheduled class, or add an entry to \
                        EXEMPT saying why this state cannot strand. "No correct resolution \
                        exists" is an acceptable reason — see MintQuote.LifecycleState.PAID — \
                        but it has to be written down.""")
                .isEmpty();
    }

    /**
     * The discriminating check, and the reason this test is not the naive one:
     * a sweep takes a time cutoff and returns a collection. Deleting the
     * {@code Instant} parameter from a reconciler's query turns it into a
     * request-path lookup, and this notices.
     */
    @Test
    @DisplayName("each reconciled machine exposes a time-bounded sweep, not just a lookup")
    void reconciledMachinesExposeATimeBoundedSweep() {
        Map<String, String> missing = new LinkedHashMap<>();

        for (Machine machine : MACHINES) {
            if (machine.scheduler() == null) {
                continue;
            }
            if (sweepMethods(machine.repository()).isEmpty()) {
                missing.put(machine.name(),
                        machine.repository().getSimpleName()
                                + " exposes no collection-returning query keyed on state or on "
                                + "a time cutoff, so " + machine.scheduler().getSimpleName()
                                + " has nothing it could sweep with");
            }
        }

        assertThat(missing)
                .as("a scheduled reconciler with no time-bounded query cannot find rows it was "
                        + "not told about, which is the only thing that distinguishes a swept "
                        + "machine from a stranded one")
                .isEmpty();
    }

    @Test
    @DisplayName("every exemption names a real non-terminal state, and gives a reason")
    void exemptionsAreRealStatesWithRealReasons() {
        Set<String> known = new LinkedHashSet<>();
        for (Machine machine : MACHINES) {
            for (Object constant : machine.stateEnum().getEnumConstants()) {
                if (!isTerminal(constant)) {
                    known.add(machine.name() + "." + ((Enum<?>) constant).name());
                }
            }
        }

        assertThat(known)
                .as("an exemption for a state that no longer exists, or that has since become "
                        + "terminal, is stale and hides whatever replaced it")
                .containsAll(EXEMPT.keySet());

        List<String> weak = EXEMPT.entrySet().stream()
                .filter(e -> e.getValue() == null || e.getValue().length() < 40)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        assertThat(weak)
                .as("the reason is the artifact: a name with no explanation is how an exemption "
                        + "list becomes decorative")
                .isEmpty();
    }

    private static boolean isTerminal(Object enumConstant) {
        try {
            Method m = enumConstant.getClass().getMethod("isTerminal");
            return (boolean) m.invoke(enumConstant);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    enumConstant.getClass().getSimpleName()
                            + " must expose isTerminal() so this rule can compute over it (#461)",
                    e);
        }
    }

    /**
     * Bulk reads a scheduler can sweep with: a collection-returning query
     * keyed on state or on a time cutoff.
     *
     * <p>Both shapes count. Pushing the cutoff into SQL scales better and is
     * what #459 shipped, but filtering in memory against a TTL is still a
     * sweep, and {@code MeltSagaReconciler} — the machine with zero stranded
     * rows — does exactly that.
     */
    private static List<Method> sweepMethods(Class<?> repository) {
        return Arrays.stream(repository.getMethods())
                .filter(m -> Iterable.class.isAssignableFrom(m.getReturnType()))
                .filter(m -> Arrays.stream(m.getParameterTypes())
                        .anyMatch(t -> t == Instant.class || Enum.class.isAssignableFrom(t)))
                .collect(Collectors.toList());
    }
}
