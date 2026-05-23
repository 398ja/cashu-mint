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
 * Spec 003 — voucher funding backed by a settled customer payment.
 * Created by the spec-001 webhook bridge when {@code outcome=accepted}
 * lands for a voucher quote in {@code UNFUNDED}.
 */
@Entity
@Table(name = "customer_payment_funding")
@DiscriminatorValue("CUSTOMER_PAYMENT")
@Audited
@Getter
@Setter
@NoArgsConstructor
public class CustomerPaymentFundingEntity extends VoucherFundingEntity {

    @Column(name = "provider", length = 64, nullable = false)
    private String provider;

    @Column(name = "provider_event_id", length = 255, nullable = false)
    private String providerEventId;

    @Column(name = "webhook_event_quote_id", length = 64)
    private String webhookEventQuoteId;

    @Column(name = "customer_id", length = 255)
    private String customerId;

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public String providerEventId() {
        return providerEventId;
    }
}
