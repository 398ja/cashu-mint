package xyz.tcheeric.cashu.mint.admin.application.port.out;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.NextAction;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationSet;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Evaluates configuration revisions against governance policies to determine the recommended next action.
 */
public interface ConfigurationPolicyEngine {

    /**
     * Determine the next recommended action for the supplied revision.
     *
     * @param mintId the mint identifier
     * @param configurationSet the revision under evaluation
     * @return recommended action or {@code null} if none applies
     */
    NextAction nextActionFor(MintId mintId, ConfigurationSet configurationSet);
}
