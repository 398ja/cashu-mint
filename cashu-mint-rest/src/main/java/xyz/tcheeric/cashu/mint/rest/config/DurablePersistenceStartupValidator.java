package xyz.tcheeric.cashu.mint.rest.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Boot-time gate requiring the durable persistence layer outside the {@code local} and
 * {@code test} profiles.
 *
 * <h2>What breaks without it</h2>
 *
 * <p>{@code cashu.mint.jpa.enabled} defaults to {@code false} so that unit-test boots and dev
 * profiles work without Postgres. That default is right for those cases and wrong for every
 * other: with JPA off, {@code MintIntegrityContextInstaller} never runs, so the melt saga
 * repository and the swap hold repository are absent and {@code MeltTask} silently takes
 * {@code executeLegacy}.
 *
 * <p>That legacy path calls {@code gateway.pay(quoteId)} and only afterwards persists and
 * invalidates the inputs. Nothing checks spent state before the money leaves. The only thing
 * serialising two concurrent melts of the same proof is an in-process {@code ReentrantLock},
 * which protects a single JVM and nothing else. An already-spent proof presented to
 * {@code /v1/melt} therefore buys a paid Lightning invoice, and any horizontally scaled
 * deployment loses even the mutex.
 *
 * <p>The reference {@code docker-compose.prod.yml} did not set {@code CASHU_MINT_JPA_ENABLED},
 * so the shipped production topology ran in exactly this configuration. A default that is safe
 * for tests and unsafe for production needs a gate at the boundary, which is this class.
 *
 * <p>Deployments that genuinely want the legacy path can set
 * {@code cashu.mint.jpa.require-in-production=false}, which logs loudly.
 */
@Slf4j
@Component
@Profile("!local & !test")
@RequiredArgsConstructor
public class DurablePersistenceStartupValidator {

    @Value("${cashu.mint.jpa.enabled:false}")
    private boolean jpaEnabled;

    @Value("${cashu.mint.jpa.require-in-production:true}")
    private boolean required;

    @PostConstruct
    void enforceDurablePersistence() {
        if (jpaEnabled) {
            log.info("Durable persistence enabled: melt runs the saga path with a "
                    + "database-backed hold on its inputs.");
            return;
        }
        if (!required) {
            log.warn("Durable persistence DISABLED and the requirement waived by "
                    + "cashu.mint.jpa.require-in-production=false. Melt will take the legacy path, "
                    + "which pays the Lightning invoice BEFORE recording the inputs as spent and "
                    + "serialises concurrent melts only within a single JVM. An already-spent "
                    + "proof can therefore result in a paid invoice.");
            return;
        }
        throw new IllegalStateException(
                "cashu.mint.jpa.enabled must be true outside the local and test profiles. "
                        + "With it disabled the melt saga and swap holds are absent, so melt pays "
                        + "the invoice before checking whether its inputs were already spent. "
                        + "Set CASHU_MINT_JPA_ENABLED=true and MINT_DB_URL, or set "
                        + "cashu.mint.jpa.require-in-production=false to accept the risk "
                        + "deliberately.");
    }
}
