package xyz.tcheeric.cashu.mint.rest.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import xyz.tcheeric.cashu.common.model.rest.PostMeltQuoteResponse;

import java.math.BigDecimal;

@NoArgsConstructor
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "t_melt_quote")
@Deprecated
public class MeltQuote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @NotNull
    @Column(name = "quote", nullable = false, length = Integer.MAX_VALUE)
    private String quote;

    @NotNull
    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @NotNull
    @Column(name = "fee_reserve", nullable = false)
    private BigDecimal feeReserve;

    @NotNull
    @Column(name = "expiry", nullable = false)
    private Integer expiry;

    public static MeltQuote fromEntity(PostMeltQuoteResponse quote) {
        MeltQuote result = new MeltQuote();
        result.setQuote(quote.getQuoteId());
        result.setExpiry(quote.getExpiry());
        result.setAmount(BigDecimal.valueOf(quote.getAmount()));
        result.setFeeReserve(BigDecimal.valueOf(quote.getFeeReserve()));
        return result;
    }
}