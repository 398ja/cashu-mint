package xyz.tcheeric.cashu.mint.proto.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec 002 T101 — pure-logic boundary tests for {@link BurnAmountValidator}.
 */
class BurnAmountValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void exactly_funded_passes() {
        assertThatCode(() -> BurnAmountValidator.requireFunded(105L, 100L, 5L))
                .doesNotThrowAnyException();
    }

    @Test
    void overfunded_passes() {
        assertThatCode(() -> BurnAmountValidator.requireFunded(200L, 100L, 5L))
                .doesNotThrowAnyException();
    }

    @Test
    void off_by_one_rejected() {
        assertThatThrownBy(() -> BurnAmountValidator.requireFunded(104L, 100L, 5L))
                .isInstanceOf(CashuErrorException.class)
                .matches(ex -> errorCode((CashuErrorException) ex).equals("insufficient_input"));
    }

    @Test
    void zero_proof_sum_rejected() {
        assertThatThrownBy(() -> BurnAmountValidator.requireFunded(0L, 100L, 5L))
                .isInstanceOf(CashuErrorException.class);
    }

    @Test
    void negative_proof_sum_rejected() {
        assertThatThrownBy(() -> BurnAmountValidator.requireFunded(-1L, 100L, 5L))
                .isInstanceOf(CashuErrorException.class);
    }

    @Test
    void zero_invoice_rejected() {
        assertThatThrownBy(() -> BurnAmountValidator.requireFunded(100L, 0L, 5L))
                .isInstanceOf(CashuErrorException.class);
    }

    @Test
    void zero_fee_reserve_allowed_when_proofs_cover_invoice() {
        assertThatCode(() -> BurnAmountValidator.requireFunded(100L, 100L, 0L))
                .doesNotThrowAnyException();
    }

    @Test
    void overflow_is_caught_not_silent() {
        // Long.MAX_VALUE - 1 + 2 would wrap if naive arithmetic were used.
        assertThatThrownBy(() -> BurnAmountValidator.requireFunded(
                Long.MAX_VALUE, Long.MAX_VALUE - 1, 2L))
                .isInstanceOf(CashuErrorException.class)
                .matches(ex -> errorCode((CashuErrorException) ex).equals("insufficient_input"));
    }

    @Test
    void large_but_safe_values_pass() {
        assertThatCode(() -> BurnAmountValidator.requireFunded(
                Long.MAX_VALUE, Long.MAX_VALUE - 1, 1L))
                .doesNotThrowAnyException();
    }

    private static String errorCode(CashuErrorException ex) {
        try {
            return MAPPER.readValue(ex.getMessage(), ErrorResponse.class).code();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
