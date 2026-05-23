package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltRequest;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Spec 002 T102 — verifies {@link ExactFeeReserveResolver} sums the
 * gateway-reported lightning reserve with the NUT-15 input fees and
 * surfaces each component for the saga record.
 */
class ExactFeeReserveResolverTest {

    @Test
    void resolves_lightning_reserve_plus_input_fees() {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.getFeeReserve("q-1")).thenReturn(5);
        KeySet keyset = Mockito.mock(KeySet.class);
        @SuppressWarnings("unchecked")
        PostMeltRequest<?> request = Mockito.mock(PostMeltRequest.class);
        when(request.getFees(any(KeySet.class))).thenReturn(2);

        ExactFeeReserveResolver.Resolved resolved =
                ExactFeeReserveResolver.resolve(gateway, "q-1", request, keyset);

        assertThat(resolved.getLightningReserve()).isEqualTo(5L);
        assertThat(resolved.getInputFees()).isEqualTo(2L);
        assertThat(resolved.getTotal()).isEqualTo(7L);
    }

    @Test
    void zero_input_fees_returns_only_lightning_reserve() {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.getFeeReserve("q-2")).thenReturn(10);
        KeySet keyset = Mockito.mock(KeySet.class);
        @SuppressWarnings("unchecked")
        PostMeltRequest<?> request = Mockito.mock(PostMeltRequest.class);
        when(request.getFees(any(KeySet.class))).thenReturn(0);

        ExactFeeReserveResolver.Resolved resolved =
                ExactFeeReserveResolver.resolve(gateway, "q-2", request, keyset);

        assertThat(resolved.getTotal()).isEqualTo(10L);
    }
}
