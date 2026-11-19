package xyz.tcheeric.cashu.mint.rest.spec003;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.rest.spec003.support.AbstractVoucherDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The voucher binding on a voucher quote, against the real schema: recorded, unique across quotes,
 * any number of unbound quotes allowed, and not changed by a later update.
 */
class VoucherQuoteVoucherBindingIT extends AbstractVoucherDurableIT {

    private static final String VOUCHER = "6f1c2b9e-1d2a-4c3b-9f8e-7a6b5c4d3e2f";

    @Test
    void bindingIsStoredAndFoundByVoucherId() {
        VoucherQuoteEntity quote = VoucherTestSupport.unfundedQuote("bind-found", 1000L);
        quote.setVoucherId(VOUCHER);
        voucherQuoteJpaRepository.saveAndFlush(quote);

        assertThat(voucherQuoteJpaRepository.findByVoucherId(VOUCHER))
                .get().extracting(VoucherQuoteEntity::getQuoteId).isEqualTo("bind-found");
    }

    @Test
    void oneVoucherIdCannotBindTwoQuotes() {
        VoucherQuoteEntity first = VoucherTestSupport.unfundedQuote("bind-first", 1000L);
        first.setVoucherId(VOUCHER);
        voucherQuoteJpaRepository.saveAndFlush(first);

        VoucherQuoteEntity second = VoucherTestSupport.unfundedQuote("bind-second", 1000L);
        second.setVoucherId(VOUCHER);
        assertThatThrownBy(() -> voucherQuoteJpaRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unboundQuotesCoexist() {
        voucherQuoteJpaRepository.saveAndFlush(VoucherTestSupport.unfundedQuote("unbound-a", 1000L));
        voucherQuoteJpaRepository.saveAndFlush(VoucherTestSupport.unfundedQuote("unbound-b", 1000L));

        assertThat(voucherQuoteJpaRepository.findById("unbound-a")).get()
                .extracting(VoucherQuoteEntity::voucherId).isNull();
    }

    @Test
    void bindingIsNotChangedByAnUpdate() {
        VoucherQuoteEntity quote = VoucherTestSupport.unfundedQuote("bind-immutable", 1000L);
        quote.setVoucherId(VOUCHER);
        voucherQuoteJpaRepository.saveAndFlush(quote);

        VoucherQuoteEntity loaded = voucherQuoteJpaRepository.findById("bind-immutable").orElseThrow();
        loaded.setVoucherId("00000000-0000-4000-8000-000000000000");
        loaded.setFee(loaded.fee() + 1);
        voucherQuoteJpaRepository.saveAndFlush(loaded);

        assertThat(voucherQuoteJpaRepository.findByVoucherId(VOUCHER)).isPresent();
        assertThat(voucherQuoteJpaRepository.findByVoucherId("00000000-0000-4000-8000-000000000000")).isEmpty();
    }
}
