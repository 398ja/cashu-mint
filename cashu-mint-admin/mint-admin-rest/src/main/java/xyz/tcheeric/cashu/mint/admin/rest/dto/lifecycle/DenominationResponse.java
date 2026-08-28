package xyz.tcheeric.cashu.mint.admin.rest.dto.lifecycle;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigInteger;

/**
 * One denomination of a keyset, and where its private key is stored.
 *
 * <p>Carries a locator, never a secret. The private key lives in HashiCorp Vault and reading
 * it needs credentials this API does not hold — which is the point: an Operator can confirm
 * a denomination was provisioned, and back it up from the path, without the key ever crossing
 * the admin, an HTTP response, or a browser.
 *
 * <p>No public key either: the vault stores none, and deriving one needs the private key. The
 * mint advertises them under NUT-01, but the admin does not talk to the mint over HTTP
 * (ADR-0003), so this is the honest limit of what the vault can answer.
 */
@Schema(description = "A denomination of a keyset and the vault path holding its private key")
public record DenominationResponse(
        @Schema(description = "The amount this key signs", example = "32")
        BigInteger amount,
        @Schema(description = "Where the private key is stored, for backup and recovery. A locator, not the secret.",
                example = "cashu/keys/021b8c8e-a6a4-457b-9c29-2bd57c119af7/00bb04981c77bc64/32")
        String vaultPath) {
}
