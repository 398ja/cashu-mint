package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The protocol-level input and output validations that NUT-03, NUT-04 and NUT-05 share.
 *
 * <p>Swap, mint and melt each present a set of inputs and a set of outputs, and the rules
 * that govern those two sets are the same for all three operations. Keeping them in one
 * task means a rule is written, and can be tested, exactly once.
 *
 * <p>Every rule here is decided from the request plus static keyset configuration, so the
 * task is cheap and MUST run before any blinded message is signed. A transaction rejected
 * after signing still leaves a blind signature in the vault, where NUT-09 restore can
 * hand it back.
 *
 * <p>The codes raised are the ones named in
 * <a href="https://github.com/cashubtc/nuts/blob/main/error_codes.md">error_codes.md</a>:
 * {@code 11007} duplicate inputs, {@code 11008} duplicate outputs, {@code 11009} multiple
 * units, {@code 11010} inputs and outputs of different units, {@code 12002} inactive
 * keyset, {@code 11003} outputs already signed.
 */
@Slf4j
public class ValidateTransactionTask<T extends Secret> extends InstrumentedTask<Void> {

    private final List<Proof<T>> inputs;
    private final List<BlindedMessage> outputs;
    private final KeySetDirectory keySets;
    private final SignatureVaultService signatureVaultService;

    /**
     * @param inputs                the transaction inputs, or {@code null} for an operation
     *                              that has none, such as mint
     * @param outputs               the blinded outputs, or {@code null} for an operation that
     *                              has none, such as a melt without NUT-08 change
     * @param keySets               resolves keysets for the unit and active-keyset rules; when
     *                              {@code null} those rules are not evaluated
     * @param signatureVaultService detects previously signed outputs; when {@code null} that
     *                              rule is not evaluated
     */
    public ValidateTransactionTask(List<Proof<T>> inputs,
                                   List<BlindedMessage> outputs,
                                   KeySetDirectory keySets,
                                   SignatureVaultService signatureVaultService) {
        this.inputs = inputs;
        this.outputs = outputs;
        this.keySets = keySets;
        this.signatureVaultService = signatureVaultService;
    }

    @Override
    protected Void doExecute() throws CashuErrorException {
        rejectDuplicateInputs();
        rejectDuplicateOutputs();
        rejectMixedUnits();
        rejectInactiveOutputKeysets();
        rejectAlreadySignedOutputs();
        return null;
    }

    /**
     * A proof presented twice must be refused rather than counted twice.
     *
     * <p>Nothing below this point stops it: {@code ProofLockManager} deduplicates the
     * secrets it locks, and the vault reports the same proof {@code UNSPENT} on both
     * lookups, so a doubled 1000-sat proof would otherwise buy 2000 sats of outputs.
     */
    private void rejectDuplicateInputs() throws CashuErrorException {
        if (inputs == null) {
            return;
        }
        Set<String> seenSecrets = new HashSet<>(inputs.size());
        for (Proof<T> input : inputs) {
            if (input == null || input.getSecret() == null) {
                continue;
            }
            if (!seenSecrets.add(input.getSecret().toString())) {
                log.warn("validate_transaction duplicate_inputs");
                throw error(CashuErrorCode.duplicate_inputs);
            }
        }
    }

    /** Two identical blinded messages would receive two signatures the wallet cannot both use. */
    private void rejectDuplicateOutputs() throws CashuErrorException {
        if (outputs == null) {
            return;
        }
        Set<String> seenBlindedMessages = new HashSet<>(outputs.size());
        for (BlindedMessage output : outputs) {
            if (output == null || output.getBlindedMessage() == null) {
                continue;
            }
            if (!seenBlindedMessages.add(output.getBlindedMessage().toString())) {
                log.warn("validate_transaction duplicate_outputs");
                throw error(CashuErrorCode.duplicate_outputs);
            }
        }
    }

    /**
     * All inputs share one unit, all outputs share one unit, and the two units agree.
     *
     * <p>Without this a transaction could cross units and create value out of nothing the
     * moment a second unit is configured.
     */
    private void rejectMixedUnits() throws CashuErrorException {
        if (keySets == null) {
            return;
        }
        Set<String> inputUnits = inputUnits();
        Set<String> outputUnits = outputUnits();
        if (inputUnits.size() > 1 || outputUnits.size() > 1) {
            log.warn("validate_transaction multiple_units input_units={} output_units={}",
                    inputUnits, outputUnits);
            throw error(CashuErrorCode.multiple_units);
        }
        if (!inputUnits.isEmpty() && !outputUnits.isEmpty() && !inputUnits.equals(outputUnits)) {
            log.warn("validate_transaction units_not_matching input_units={} output_units={}",
                    inputUnits, outputUnits);
            throw error(CashuErrorCode.inputs_outputs_unit_mismatch);
        }
    }

    private Set<String> inputUnits() throws CashuErrorException {
        Set<String> units = new TreeSet<>();
        if (inputs == null) {
            return units;
        }
        for (Proof<T> input : inputs) {
            if (input == null || input.getKeySetId() == null) {
                continue;
            }
            addUnit(units, keySets.find(input.getKeySetId()));
        }
        return units;
    }

    private Set<String> outputUnits() throws CashuErrorException {
        Set<String> units = new TreeSet<>();
        if (outputs == null) {
            return units;
        }
        for (BlindedMessage output : outputs) {
            if (output == null || output.getKeySetId() == null) {
                continue;
            }
            addUnit(units, keySets.find(output.getKeySetId().toString()));
        }
        return units;
    }

    /** An unresolvable keyset carries no unit; signing reports that separately. */
    private void addUnit(Set<String> units, KeySet keySet) {
        if (keySet != null && keySet.getUnit() != null) {
            units.add(keySet.getUnit());
        }
    }

    /**
     * NUT-02 requires new outputs to come from an active keyset.
     *
     * <p>Only a keyset the mint knows to be archived is refused. An unknown keyset id is
     * left to the signing step, which reports it as a missing keyset rather than a retired
     * one, and those are different recoveries for the wallet.
     */
    private void rejectInactiveOutputKeysets() throws CashuErrorException {
        if (keySets == null || outputs == null) {
            return;
        }
        for (BlindedMessage output : outputs) {
            if (output == null || output.getKeySetId() == null) {
                continue;
            }
            String keysetId = output.getKeySetId().toString();
            if (keySets.isArchived(keysetId)) {
                log.warn("validate_transaction keyset_inactive keyset_id={}", keysetId);
                throw error(CashuErrorCode.keyset_inactive);
            }
        }
    }

    /**
     * A replayed output set must not be signed again.
     *
     * <p>NUT-09 restore is the intended way to recover a signature that was already issued;
     * re-signing silently would hide the replay.
     */
    private void rejectAlreadySignedOutputs() throws CashuErrorException {
        if (signatureVaultService == null || outputs == null) {
            return;
        }
        for (BlindedMessage output : outputs) {
            if (output == null || output.getBlindedMessage() == null) {
                continue;
            }
            if (signatureVaultService.retrieve(output) != null) {
                log.warn("validate_transaction outputs_already_signed");
                throw error(CashuErrorCode.outputs_already_signed);
            }
        }
    }

    /** Raises the failure under its {@link CashuErrorCode}, which carries the spec's numeric code. */
    private CashuErrorException error(CashuErrorCode code) {
        return new CashuErrorException(code);
    }
}
