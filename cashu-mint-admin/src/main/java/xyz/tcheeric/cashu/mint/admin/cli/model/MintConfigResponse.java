package xyz.tcheeric.cashu.mint.admin.cli.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record MintConfigResponse(String mintId,
                                 String revision,
                                 Map<String, String> parameters) {

    public MintConfigResponse {
        this.mintId = ModelValidations.requireMintId(mintId);
        this.revision = ModelValidations.requireText(revision, "revision");
        this.parameters = parameters == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(parameters));
    }
}
