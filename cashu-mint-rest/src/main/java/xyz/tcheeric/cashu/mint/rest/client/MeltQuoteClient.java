package xyz.tcheeric.cashu.mint.rest.client;


import xyz.tcheeric.cashu.mint.rest.entity.MeltQuote;

@Deprecated
public class MeltQuoteClient extends QuoteClient<MeltQuote> {

    public MeltQuoteClient() {
        super(Operation.MELT);
    }
}