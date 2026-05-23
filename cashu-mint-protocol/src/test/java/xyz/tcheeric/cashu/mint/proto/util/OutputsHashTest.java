package xyz.tcheeric.cashu.mint.proto.util;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.PublicKey;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec 001 T102 / research R4: {@link OutputsHash#compute(List)} is stable
 * under permutation of the input list and rejects empty/null inputs.
 */
class OutputsHashTest {

    @Test
    void hashIsStableAcrossPermutations() {
        // The hash MUST identify the same multiset of outputs regardless of
        // request order — otherwise NUT-19 replay can't recognise a retry.
        BlindedMessage a = blindedMessage(1, "k1", "Akeya");
        BlindedMessage b = blindedMessage(2, "k1", "Bkeyb");
        BlindedMessage c = blindedMessage(4, "k2", "Ckeyc");

        String forward = OutputsHash.compute(List.of(a, b, c));
        String reversed = OutputsHash.compute(List.of(c, b, a));
        String mixed = OutputsHash.compute(List.of(b, c, a));

        assertThat(forward).isEqualTo(reversed).isEqualTo(mixed);
        assertThat(forward).hasSize(64); // sha256 hex
    }

    @Test
    void hashChangesWhenAmountChanges() {
        // Amount is part of the canonical tuple, so a wallet that mutates an
        // output's amount between attempts cannot retry under the same hash.
        BlindedMessage original = blindedMessage(2, "k1", "Bkeyb");
        BlindedMessage tampered = blindedMessage(3, "k1", "Bkeyb");

        assertThat(OutputsHash.compute(List.of(original)))
                .isNotEqualTo(OutputsHash.compute(List.of(tampered)));
    }

    @Test
    void hashChangesWhenKeysetChanges() {
        BlindedMessage original = blindedMessage(2, "k1", "Bkeyb");
        BlindedMessage rerouted = blindedMessage(2, "k2", "Bkeyb");

        assertThat(OutputsHash.compute(List.of(original)))
                .isNotEqualTo(OutputsHash.compute(List.of(rerouted)));
    }

    @Test
    void hashChangesWhenBlindedKeyChanges() {
        BlindedMessage original = blindedMessage(2, "k1", "Bkeyb");
        BlindedMessage swapped = blindedMessage(2, "k1", "Bkeyc");

        assertThat(OutputsHash.compute(List.of(original)))
                .isNotEqualTo(OutputsHash.compute(List.of(swapped)));
    }

    @Test
    void rejectsNullList() {
        assertThatThrownBy(() -> OutputsHash.compute(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyList() {
        assertThatThrownBy(() -> OutputsHash.compute(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BlindedMessage blindedMessage(int amount, String keysetId, String publicKey) {
        BlindedMessage m = Mockito.mock(BlindedMessage.class);
        KeysetId ks = Mockito.mock(KeysetId.class);
        PublicKey pk = Mockito.mock(PublicKey.class);
        Mockito.when(m.getAmount()).thenReturn(amount);
        Mockito.when(m.getKeySetId()).thenReturn(ks);
        Mockito.when(m.getBlindedMessage()).thenReturn(pk);
        Mockito.when(ks.toString()).thenReturn(keysetId);
        Mockito.when(pk.toString()).thenReturn(publicKey);
        return m;
    }
}
