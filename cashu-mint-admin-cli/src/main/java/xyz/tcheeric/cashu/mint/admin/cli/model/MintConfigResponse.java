package xyz.tcheeric.cashu.mint.admin.cli.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record MintConfigResponse(String mintId,
                                 String revision,
                                 Map<String, String> parameters) {

    public MintConfigResponse {
        mintId = ModelValidations.requireMintId(mintId);
        revision = ModelValidations.requireText(revision, "revision");
        parameters = parameters == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(parameters));
    }
}
