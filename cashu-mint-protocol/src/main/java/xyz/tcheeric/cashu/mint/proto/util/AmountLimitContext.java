package xyz.tcheeric.cashu.mint.proto.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;

/**
 * Publishes the one {@link AmountLimitPolicy} to the static NUT-04 / NUT-05
 * entry points, which cannot take constructor dependencies.
 *
 * <p>The installed policy is the same object {@code DefaultMintInfoService}
 * advertises from, so the enforced limits and the advertised limits cannot be
 * different numbers. When no policy is installed — legacy unit-test contexts
 * that never start Spring — the accessor falls back to a policy over default
 * capabilities rather than skipping enforcement, because an unenforced limit is
 * the defect issue #390 exists to close.
 *
 * <p>Mirrors the {@code MintIntegrityContext} service-locator pattern already
 * used by the protocol module.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AmountLimitContext {

    private static volatile AmountLimitPolicy policy;

    /**
     * Installs the policy the mint and melt paths enforce. Idempotent.
     *
     * @param amountLimitPolicy the policy derived from advertised capabilities
     */
    public static void install(@NonNull AmountLimitPolicy amountLimitPolicy) {
        policy = amountLimitPolicy;
    }

    /**
     * Returns the policy in force, never null.
     *
     * @return the installed policy, or one over default capabilities
     */
    public static AmountLimitPolicy policy() {
        AmountLimitPolicy installed = policy;
        if (installed == null) {
            installed = new AmountLimitPolicy(new MintCapabilityProperties());
            policy = installed;
        }
        return installed;
    }

    /** Resets the context. Test-only. */
    public static void clear() {
        policy = null;
    }
}
