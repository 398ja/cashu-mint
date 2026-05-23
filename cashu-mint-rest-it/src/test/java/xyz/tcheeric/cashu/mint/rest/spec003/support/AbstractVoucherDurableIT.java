package xyz.tcheeric.cashu.mint.rest.spec003.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherFundingJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherIdempotencyKeyJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherIssuanceJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherQuoteJpaRepository;
import xyz.tcheeric.cashu.mint.rest.spec001.AbstractMintDurableIT;

/**
 * Spec 003 — base class for voucher integration tests. Extends the
 * spec-001 Testcontainers harness with cleanup of the spec-003 tables
 * before each test.
 */
public abstract class AbstractVoucherDurableIT extends AbstractMintDurableIT {

    @Autowired
    protected VoucherQuoteJpaRepository voucherQuoteJpaRepository;

    @Autowired
    protected VoucherFundingJpaRepository voucherFundingJpaRepository;

    @Autowired
    protected VoucherIssuanceJpaRepository voucherIssuanceJpaRepository;

    @Autowired
    protected VoucherIdempotencyKeyJpaRepository voucherIdempotencyKeyJpaRepository;

    @BeforeEach
    void cleanVoucherTables() {
        // Order matters: issuance + quote reference funding; idempotency is standalone.
        voucherIssuanceJpaRepository.deleteAll();
        voucherQuoteJpaRepository.deleteAll();
        voucherFundingJpaRepository.deleteAll();
        voucherIdempotencyKeyJpaRepository.deleteAll();
    }
}
