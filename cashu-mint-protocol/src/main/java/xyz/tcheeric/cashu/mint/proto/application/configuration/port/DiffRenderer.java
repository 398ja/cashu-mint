package xyz.tcheeric.cashu.mint.proto.application.configuration.port;

import java.util.Optional;
import xyz.tcheeric.cashu.mint.proto.application.configuration.domain.ConfigurationRevision;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ConfigurationRevisionDraft;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.DiffArtifact;

/**
 * Renders diffs between proposed and baseline configurations.
 */
public interface DiffRenderer {

    DiffArtifact renderDiff(ConfigurationRevisionDraft draft, Optional<ConfigurationRevision> baseline);
}
