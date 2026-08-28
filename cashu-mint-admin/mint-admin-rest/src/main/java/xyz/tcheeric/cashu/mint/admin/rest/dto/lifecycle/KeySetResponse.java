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

    @Schema(description = "SIGNING for the keyset the mint issues with, ARCHIVED for one it "
        + "has retired from signing but still verifies and redeems", example = "SIGNING")
    String state,

    @Schema(description = "When the vault first stored the keyset")
    Instant createdAt
) {
    public static final String SIGNING = "SIGNING";
    public static final String ARCHIVED = "ARCHIVED";
}
