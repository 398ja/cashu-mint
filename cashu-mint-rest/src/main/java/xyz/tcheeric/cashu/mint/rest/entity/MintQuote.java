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
import xyz.tcheeric.cashu.common.model.rest.PostMintQuoteResponse;

@NoArgsConstructor
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "t_mint_quote")
public class MintQuote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Integer id;

    @NotNull
    @Column(name = "quote", nullable = false, length = Integer.MAX_VALUE)
    private String quote;

    @NotNull
    @Column(name = "request", nullable = false, length = Integer.MAX_VALUE)
    private String request;

    @NotNull
    @Column(name = "expiry", nullable = false)
    private Integer expiry;

    public static MintQuote fromEntity(PostMintQuoteResponse quote) {
        MintQuote result = new MintQuote();
        result.setQuote(quote.getQuoteId());
        result.setRequest(quote.getRequest());
        result.setExpiry(quote.getExpiry());
        return result;
    }
}