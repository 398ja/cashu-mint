package xyz.tcheeric.cashu.mint.admin.rest.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperationalControlRepository;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.rest.dto.dashboard.DashboardSummaryResponse;

/**
 * Aggregates dashboard summary data from mint and operational control repositories.
 */
@Service
public class AdminDashboardService {

    private final MintRepository mintRepository;
    private final OperationalControlRepository controlRepository;

    public AdminDashboardService(final MintRepository mintRepository,
                                 final OperationalControlRepository controlRepository) {
        this.mintRepository = Objects.requireNonNull(mintRepository);
        this.controlRepository = Objects.requireNonNull(controlRepository);
    }

    public DashboardSummaryResponse getSummary() {
        final List<MintAggregate> mints = mintRepository.findAll();
        final Map<String, Long> mintsByState = mints.stream()
                .collect(Collectors.groupingBy(
                        m -> m.lifecycleState().value().name(),
                        LinkedHashMap::new,
                        Collectors.counting()));

        final long activeControls = mints.stream()
                .flatMap(m -> controlRepository.findByMintId(m.mintId()).stream())
                .filter(c -> "SCHEDULED".equals(c.status()) || "IN_PROGRESS".equals(c.status()))
                .count();

        return new DashboardSummaryResponse(mintsByState, activeControls);
    }
}
