package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.ActiveKeySet;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.ports.KeysetRedemptionCutoff;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintLoadService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
public class ActiveKeySetsTask extends InstrumentedTask<List<ActiveKeySet>> {

    private final MintLoadService mintLoadService;
    private final RedemptionCutoffGate cutoffs;

    public ActiveKeySetsTask() {
        this(new DefaultMintLoadService());
    }

    public ActiveKeySetsTask(@NonNull MintLoadService mintLoadService) {
        this(mintLoadService, RedemptionCutoffGate.installed());
    }

    /** Seam for the tests that pin how a redemption cutoff changes the listing (cashu-mint#551). */
    public ActiveKeySetsTask(@NonNull MintLoadService mintLoadService, @NonNull RedemptionCutoffGate cutoffs) {
        this.mintLoadService = mintLoadService;
        this.cutoffs = cutoffs;
    }

    @Override
    protected List<ActiveKeySet> doExecute() throws CashuErrorException {
        log.debug("execute()");
        // Deduplicate by keyset id. If a keyset appears in both sources, prefer active=true.
        java.util.Map<String, ActiveKeySet> byId = new java.util.HashMap<>();
        java.util.Map<String, KeysetRedemptionCutoff> cutoffByKeyset = cutoffs.all();
        java.time.Instant now = cutoffs.now();

        // First, add archived/inactive keysets as inactive. An archived keyset whose redemption
        // cutoff has passed is no longer advertised at all: its proofs are refused, so a wallet
        // that still sees it would keep offering value the mint will not take (cashu-mint#551).
        // One whose cutoff is still ahead stays active:false, with the cutoff as NUT-02
        // final_expiry, so a wallet can tell its holder to move the value before then.
        for (var keySet : mintLoadService.keySets(true)) {
            KeysetRedemptionCutoff cutoff = cutoffByKeyset.get(keySet.getId());
            if (cutoff != null && cutoff.hasPassed(now)) {
                continue;
            }
            Long finalExpiry = cutoff == null ? null : cutoff.redeemUntil().getEpochSecond();
            byId.put(keySet.getId(), ActiveKeySet.fromKeySet(keySet, false, finalExpiry));
        }

        // Then, add active keysets and override the flag to true if same id was seen. A signing
        // keyset is never retired, so a cutoff recorded against one is ignored here as it is on
        // the redemption path.
        for (var keySet : mintLoadService.keySets(false)) {
            byId.put(keySet.getId(), ActiveKeySet.fromKeySet(keySet, true));
        }

        List<ActiveKeySet> result = new ArrayList<>(byId.values());
        result.sort(Comparator.comparing(ActiveKeySet::getId));
        return result;
    }
}
