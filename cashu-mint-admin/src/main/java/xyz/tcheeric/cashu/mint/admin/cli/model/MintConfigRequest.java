package xyz.tcheeric.cashu.mint.admin.cli.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record MintConfigRequest(String mintId, Map<String, String> parameters) {

    public static final String DEFAULT_MINT_ID = "default-mint";

    public MintConfigRequest {
        this.mintId = ModelValidations.requireMintId(mintId);
        this.parameters = parameters == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(parameters));
    }

    public static MintConfigRequest withDefaults(final String mintId) {
        return new MintConfigRequest(mintId, Map.of());
    }
}
