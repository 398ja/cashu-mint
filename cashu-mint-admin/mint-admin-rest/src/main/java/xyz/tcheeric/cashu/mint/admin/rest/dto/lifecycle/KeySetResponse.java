package xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One keyset the shared vault holds for a mint. Carries no key material.
 */
@Schema(description = "A keyset held in the shared vault for a mint")
public record KeySetResponse(
    @Schema(description = "Keyset id wallets name", example = "009a1f293253e41e")
    String keySetId,

    @Schema(description = "Unit the keyset serves", example = "sat")
    String unit,

    @Schema(description = "SIGNING for the keyset the mint issues with, ARCHIVED for one it no "
        + "longer signs with but still verifies and redeems, indefinitely", example = "SIGNING")
    String state,

    @Schema(description = "When the vault first stored the keyset")
    Instant createdAt,

    @Schema(description = "NUT-02 fee charged per thousand inputs spent from this keyset, "
        + "0 when it charges nothing. Fixed for the life of the keyset: changing a fee "
        + "rotates the keyset rather than editing it (ADR-0009), so an archived keyset "
        + "shows the fee its proofs are still redeemed at", example = "0")
    int inputFeePpk
) {
    /**
     * SIGNING rather than ACTIVE: a mint is separately ACTIVE or SUSPENDED, and one word
     * meaning two things on the same screen is worse than one that names what ADR-0004
     * actually turns off — signing.
     */
    public static final String SIGNING = "SIGNING";
    public static final String ARCHIVED = "ARCHIVED";
}
