package xyz.tcheeric.cashu.mint.rest.client;


import xyz.tcheeric.cashu.mint.rest.entity.MintQuote;

@Deprecated
public class MintQuoteClient extends QuoteClient<MintQuote> {

    public MintQuoteClient() {
        super(Operation.MINT);
    }
}