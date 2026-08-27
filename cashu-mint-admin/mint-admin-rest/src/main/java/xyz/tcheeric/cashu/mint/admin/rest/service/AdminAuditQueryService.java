package xyz.tcheeric.cashu.mint.admin.rest.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessAuditRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessAuditRepository.OperatorAccessAuditEntry;
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
    private final OperatorAccessAuditRepository operatorAccessAuditRepository;

    public AdminAuditQueryService(final MintRepository mintRepository,
                                  final OperatorAccessAuditRepository operatorAccessAuditRepository) {
        this.mintRepository = Objects.requireNonNull(mintRepository);
        this.operatorAccessAuditRepository =
            Objects.requireNonNull(operatorAccessAuditRepository, "operator access audit repository");
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
        // Operator management is not about a mint, so it lives in its own table; it belongs on
        // the same timeline all the same, or suspending an Operator is the one privileged action
        // the Audit Trail does not show.
        if (mintId == null) {
            for (final OperatorAccessAuditEntry entry : operatorAccessAuditRepository.findAll()) {
                if ((actor == null || actor.equals(entry.actor()))
                        && (action == null || action.equals(entry.action()))) {
                    allEvents.add(new AuditEventResponse(null, 0, entry.actor(), entry.action(),
                        entry.occurredAt(), null));
                }
            }
        }

        allEvents.sort((a, b) -> b.timestamp().compareTo(a.timestamp()));
        return PagedResponse.of(allEvents, page, size);
    }
}
