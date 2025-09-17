package xyz.tcheeric.cashu.mint.proto.application.configuration.port;

import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.AuditEntry;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.AuditReference;

/**
 * Persists audit trail entries for governance operations.
 */
public interface AuditTrailWriter {

    AuditReference record(AuditEntry entry);
}
