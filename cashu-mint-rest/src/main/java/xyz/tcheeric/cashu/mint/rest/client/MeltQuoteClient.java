package xyz.tcheeric.cashu.mint.rest.client;


import xyz.tcheeric.cashu.mint.rest.entity.MeltQuote;

public class MeltQuoteClient extends QuoteClient<MeltQuote> {

    public MeltQuoteClient() {
        super(Operation.MELT);
    }
}