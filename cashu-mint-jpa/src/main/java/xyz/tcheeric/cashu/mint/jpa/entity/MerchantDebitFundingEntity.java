package xyz.tcheeric.cashu.mint.jpa.entity;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.envers.Audited;

/**
 * Spec 003 — voucher funding backed by a debit against the merchant
 * ledger. The {@code merchantDebitId} references the external
 * merchant-ledger row that recorded the debit.
 */
@Entity
@Table(name = "merchant_debit_funding")
@DiscriminatorValue("MERCHANT_DEBIT")
@Audited
@Getter
@Setter
@NoArgsConstructor
public class MerchantDebitFundingEntity extends VoucherFundingEntity {

    @jakarta.persistence.Convert(converter = xyz.tcheeric.cashu.mint.jpa.crypto.IdentityHashConverter.class)
    @Column(name = "merchant_id", length = 255, nullable = false)
    private String merchantId;

    @Column(name = "merchant_debit_id", length = 255, nullable = false)
    private String merchantDebitId;

    // Spec 004 V20260601_005 dropped the merchant_ledger_balance_after
    // column (research R5a — speculative forensic snapshot with no
    // operator consumer; the merchant's own ledger is the source of
    // truth for merchant balance).

    @Override
    public String merchantId() {
        return merchantId;
    }

    @Override
    public String merchantDebitId() {
        return merchantDebitId;
    }
}
