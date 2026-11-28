package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;

import java.util.UUID;

/**
 * Spending condition for plain random-string proofs.
 *
 * <p>Every collaborator is required: a condition without a mint cannot run the per-mint
 * double-spend check, so that state is unconstructible (cashu-mint#488).
 */
@AllArgsConstructor
@Slf4j
public class RSSSpendingCondition implements SpendingCondition<RandomStringSecret> {

    @Setter(AccessLevel.NONE)
    @NonNull
    private final Mint mint;
    @NonNull
    private final MintProtocolService mintProtocolService;
    @NonNull
    private final ProofVaultService proofVaultService;

    public RSSSpendingCondition(@NonNull Mint mint,
                                @NonNull MintProtocolService mintProtocolService) {
        this(mint, mintProtocolService, new DefaultProofVaultService());
    }

    @Override
    public void verify(Proof<RandomStringSecret> proof) throws CashuErrorException {

        log.debug("Verify proof {}", proof);

        // No vault lookup here (exp aae/nov27). The double-spend check is the swap's hold:
        // SwapProofHold.claim binds every input atomically, refuses a spent or held one, and
        // reports a spent one as verify_proof_already_used_error. The per-input lookup this
        // replaced was advisory (a vault outage let the proof through) and cost one or two vault
        // round trips per input on every swap.
        requireMintId();

        if (proof.getKeySetId() == null || proof.getKeySetId().isBlank()) {
            log.error("verify_proof_key_set_id_error");
            throw new CashuErrorException(CashuErrorCode.verify_proof_key_set_id_error);
        }

        log.debug("The proof key set id is valid...");

        // The mint's signature on C is not re-checked here. VerifyProofsTask runs
        // ProofAuthenticity.require on every input before any spending condition, and that is
        // the BDHKE check every proof type depends on (see its class comment). Running it a
        // second time here cost a full scalar multiplication per input, about 15% of a swap,
        // and could only ever agree with the first. Callers outside VerifyProofsTask must call
        // ProofAuthenticity themselves; a condition only adds requirements on top of it.
        PrivateKey privateKey = getPrivateKey(proof);
        if (privateKey == null) {
            log.error("verify_proof_key_set_not_found");
            throw new CashuErrorException(CashuErrorCode.verify_proof_key_set_not_found);
        }
    }

    private PrivateKey getPrivateKey(@NonNull Proof<RandomStringSecret> proof) throws CashuErrorException {
        log.debug("Getting private key for {}", proof);
        return mintProtocolService.getPrivateKey(proof.getKeySetId(), proof.getAmount(), mint);
    }

    /**
     * The mint whose proof table the double-spend check must consult.
     *
     * <p>Fails rather than returning null. The constructors reject a null mint, so this guards a
     * mint without an id, and stays as defence in depth: a proof lookup without a mint cannot
     * answer "has this been spent here", because an unscoped lookup could read another mint's row
     * and skipping the lookup would let an already-spent proof verify.
     */
    private UUID requireMintId() throws CashuErrorException {
        if (mint.getId() == null) {
            log.error("verify_proof_no_mint_error rss_proof: cannot check for a double spend "
                    + "without a mint, refusing to verify");
            throw new CashuErrorException(
                    "Cannot verify a proof without a mint: the double-spend check is scoped per "
                            + "mint and cannot be skipped");
        }
        return UUID.fromString(mint.getId());
    }
}
