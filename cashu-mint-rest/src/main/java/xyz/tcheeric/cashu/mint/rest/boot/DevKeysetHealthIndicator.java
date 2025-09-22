package xyz.tcheeric.cashu.mint.rest.boot;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.common.ActiveKeySet;
import xyz.tcheeric.cashu.mint.proto.nut.NUT02;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevKeysetHealthIndicator implements HealthIndicator {

    private final MintLoadService mintLoadService;

    @Override
    public Health health() {
        try {
            List<ActiveKeySet> active = NUT02.activeKeySets(mintLoadService);
            if (active == null || active.isEmpty()) {
                return Health.down()
                        .withDetail("reason", "no_active_keysets")
                        .withDetail("hint", "Generate preload JSON and ensure dev profile is active")
                        .build();
            }
            Set<String> units = active.stream().map(ActiveKeySet::getUnit).collect(Collectors.toSet());
            return Health.up()
                    .withDetail("activeKeysets", active.size())
                    .withDetail("units", units)
                    .build();
        } catch (CashuErrorException e) {
            return Health.down(e).withDetail("reason", "keyset_query_failed").build();
        }
    }
}

