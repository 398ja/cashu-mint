package xyz.tcheeric.cashu.mint.admin.application.port.out;

import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.domain.ValidationReport;

/**
 * Validates configuration revisions against policy and compatibility constraints.
 */
public interface ConfigurationValidator {

    /**
     * Validate the supplied configuration revision.
     *
     * @param mintId the mint identifier
     * @param configurationSet the configuration to validate
     * @return validation report describing the outcome
     */
    ValidationReport validate(MintId mintId, ConfigurationSet configurationSet);
}
