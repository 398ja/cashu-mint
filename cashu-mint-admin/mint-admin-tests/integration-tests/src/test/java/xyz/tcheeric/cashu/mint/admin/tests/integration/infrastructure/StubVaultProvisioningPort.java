package xyz.tcheeric.cashu.mint.admin.tests.integration.infrastructure;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import xyz.tcheeric.cashu.mint.admin.application.port.out.VaultProvisioningPort;

/**
 * Controllable test double for {@link VaultProvisioningPort}.
 *
 * <p>Records all method invocations and can be configured to throw
 * on {@link #provision} to simulate transient or permanent failures.
 */
public class StubVaultProvisioningPort implements VaultProvisioningPort {

    private final AtomicBoolean shouldFail = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Invocation> invocations = new CopyOnWriteArrayList<>();

    public record Invocation(String method, UUID mintId) {}

    @Override
    public void provision(final UUID mintId, final String unit, final List<Integer> denominations) {
        invocations.add(new Invocation("provision", mintId));
        if (shouldFail.get()) {
            throw new RuntimeException("Stub vault provisioning failure");
        }
    }

    @Override
    public RotationResult rotate(final UUID mintId, final String unit,
                                 final List<Integer> denominations, final String rotationId) {
        invocations.add(new Invocation("rotate", mintId));
        if (shouldFail.get()) {
            throw new RuntimeException("Stub vault rotation failure");
        }
        // Keyed on the rotation id, mirroring the real adapter, so a redelivered
        // message yields the same keyset rather than a second one.
        return new RotationResult("keyset-" + rotationId, List.of("keyset-previous"));
    }

    @Override
    public boolean isProvisioned(final UUID mintId) {
        invocations.add(new Invocation("isProvisioned", mintId));
        return false;
    }

    @Override
    public void archive(final UUID mintId) {
        invocations.add(new Invocation("archive", mintId));
    }

    @Override
    public void compensate(final UUID mintId) {
        invocations.add(new Invocation("compensate", mintId));
    }

    public void setShouldFail(final boolean fail) {
        shouldFail.set(fail);
    }

    public List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    public long countInvocations(final String method) {
        return invocations.stream().filter(i -> method.equals(i.method())).count();
    }

    public void reset() {
        shouldFail.set(false);
        invocations.clear();
    }
}
