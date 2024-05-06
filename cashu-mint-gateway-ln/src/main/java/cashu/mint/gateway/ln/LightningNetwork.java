package cashu.mint.gateway.ln;

import cashu.mint.gateway.Gateway;

public class LightningNetwork implements Gateway {

    @Override
    public String createRequest(int amount) {
        return null;
    }

    @Override
    public String getRequest(String quoteId) {
        return null;
    }

    @Override
    public boolean checkPaymentStatus(String quoteId) {
        return false;
    }

    @Override
    public String getPaymentPreimage(String quoteId) {
        return null;
    }

    @Override
    public void pay(String quoteId) {

    }

    @Override
    public int getPaymentExpiry() {
        return 0;
    }

    @Override
    public int getFeeReserve(String requestId) {
        return 0;
    }
}
