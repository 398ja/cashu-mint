package xyz.tcheeric.cashu.mint.rest.boot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.common.ActiveKeySet;
import xyz.tcheeric.cashu.mint.proto.nut.NUT02;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@Profile("dev")
// After VaultPreloadSeeder, so a seeded keyset is not reported as missing.
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class DevKeysetStartupCheck implements ApplicationRunner {

    private final MintLoadService mintLoadService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<ActiveKeySet> active = NUT02.activeKeySets(mintLoadService);
            if (active == null || active.isEmpty()) {
                log.warn("No active keysets detected in dev profile. Ensure preload JSON exists at scripts/preload-test-data.json or set MINT_PRELOAD_JSON_INPUT, then restart.");
            } else {
                Set<String> units = active.stream().map(ActiveKeySet::getUnit).collect(Collectors.toSet());
                log.info("Active keysets available (count={} units={})", active.size(), units);
            }
        } catch (CashuErrorException e) {
            log.warn("Dev keyset startup check failed to query active keysets", e);
        }
    }
}
