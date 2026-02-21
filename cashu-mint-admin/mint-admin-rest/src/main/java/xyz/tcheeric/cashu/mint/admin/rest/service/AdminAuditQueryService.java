package xyz.tcheeric.cashu.mint.admin.rest.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRepository;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.rest.dto.audit.AuditEventResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.common.PagedResponse;

/**
 * Provides audit event querying from the audit_events table via the mint aggregate audit trail.
 */
@Service
public class AdminAuditQueryService {

    private final MintRepository mintRepository;

    public AdminAuditQueryService(final MintRepository mintRepository) {
        this.mintRepository = Objects.requireNonNull(mintRepository);
    }

    public PagedResponse<AuditEventResponse> listAuditEvents(final String mintId,
                                                              final String actor,
                                                              final String action,
                                                              final int page,
                                                              final int size) {
        final List<MintAggregate> mints = mintRepository.findAll();
        final List<AuditEventResponse> allEvents = new ArrayList<>();
        for (final MintAggregate mint : mints) {
            if (mintId != null && !mint.mintId().asString().equals(mintId)) {
                continue;
            }
            final List<AuditMetadata> entries = mint.auditTrail().entries();
            for (int i = 0; i < entries.size(); i++) {
                final AuditMetadata entry = entries.get(i);
                if (actor != null && !actor.equals(entry.actor())) {
                    continue;
                }
                if (action != null && !action.equals(entry.action())) {
                    continue;
                }
                final Long revisionId = entry.lifecycleContext().hasConfigurationRevision()
                        ? entry.lifecycleContext().configurationRevisionId().value()
                        : null;
                allEvents.add(new AuditEventResponse(
                        mint.mintId().asString(),
                        i + 1,
                        entry.actor(),
                        entry.action(),
                        entry.timestamp(),
                        revisionId));
            }
        }
        allEvents.sort((a, b) -> b.timestamp().compareTo(a.timestamp()));
        return PagedResponse.of(allEvents, page, size);
    }
}
