package xyz.tcheeric.cashu.vault.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

@AllArgsConstructor
@Getter
@ToString
@Builder
public class ProofConfiguration implements EntityConfiguration {

    private final MintConfiguration mint;
    private final String unblindedSignature;
    private final String hashToCurveSecret;

    public ProofConfiguration(@NonNull MintConfiguration mint, @NonNull String hashToCurveSecret) {
        this(mint, null, hashToCurveSecret);
    }

    public ProofConfiguration(@NonNull MintConfiguration mint) {
        this.mint = mint;
        this.unblindedSignature = null;
        this.hashToCurveSecret = null;
    }
}
