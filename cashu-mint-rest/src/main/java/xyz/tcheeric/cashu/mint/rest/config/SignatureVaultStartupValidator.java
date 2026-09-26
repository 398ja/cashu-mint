package xyz.tcheeric.cashu.mint.rest.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

/**
 * Boot-time gate refusing a volatile signature vault outside the {@code local} and {@code test}
 * profiles (issue #491).
 *
 * <h2>What breaks without it</h2>
 *
 * <p>The signature vault is the mint's record of which blinded messages it has signed. It is
 * what refuses to sign the same {@code B_} twice and what NUT-09 restore answers from. The
 * in-memory fallback forgets all of it when the process stops, and each replica keeps its own
 * copy. After a restart the mint will sign an output a second time, producing two proofs of
 * which only one can be spent, and a wallet restoring from its seed gets nothing back for
 * outputs signed before the restart.
 *
 * <p>The durable vault is wired whenever {@code cashu.mint.jpa.enabled=true}, which
 * {@link DurablePersistenceStartupValidator} already requires in these profiles. This gate
 * checks the result rather than the property, so it also catches that requirement being waived
 * with {@code cashu.mint.jpa.require-in-production=false}. There is deliberately no waiver of
 * its own: no deployment is served by forgetting what it has signed.
 */
@Slf4j
@Component
// Same exemption as DurablePersistenceStartupValidator, and for the same reason:
// these profiles run without a datasource by design. See
// DurablePersistenceStartupValidatorProfileTest for why the list is exact.
@Profile("!local & !test & !websocket-test")
@RequiredArgsConstructor
public class SignatureVaultStartupValidator {

    private final SignatureVaultService signatureVaultService;

    @PostConstruct
    void requireDurableSignatureVault() {
        if (signatureVaultService.isDurable()) {
            log.info("Signature vault is durable ({}): signed outputs survive restarts and are "
                    + "shared across instances.", signatureVaultService.getClass().getSimpleName());
            return;
        }
        throw new IllegalStateException(
                "The signature vault (" + signatureVaultService.getClass().getSimpleName() + ") is "
                        + "in-memory. Outside the local and test profiles it would forget every "
                        + "signed output on restart, letting the same blinded message be signed "
                        + "twice and making NUT-09 restore return nothing. Set "
                        + "CASHU_MINT_JPA_ENABLED=true and MINT_DB_URL so the durable vault is "
                        + "wired.");
    }
}
