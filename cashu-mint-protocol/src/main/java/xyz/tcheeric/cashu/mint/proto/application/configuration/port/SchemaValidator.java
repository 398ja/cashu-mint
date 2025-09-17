package xyz.tcheeric.cashu.mint.proto.application.configuration.port;

import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ConfigurationRevisionDraft;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ValidationSummary;

/**
 * Validates configuration payloads against schema constraints.
 */
public interface SchemaValidator {

    ValidationSummary validate(ConfigurationRevisionDraft draft);
}
