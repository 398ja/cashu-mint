package cashu.mint.rest.client;


import cashu.mint.rest.entity.MintQuote;

public class MintQuoteClient extends QuoteClient<MintQuote> {

    public MintQuoteClient() {
        super(Operation.MINT);
    }
}