package cashu.mint.rest.client;


import cashu.mint.rest.entity.MeltQuote;

public class MeltQuoteClient extends QuoteClient<MeltQuote> {

    public MeltQuoteClient() {
        super(Operation.MELT);
    }
}