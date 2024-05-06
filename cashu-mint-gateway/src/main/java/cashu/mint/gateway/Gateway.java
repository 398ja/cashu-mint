package cashu.mint.gateway;

public interface Gateway {

    String createRequest(int amount);

    String getRequest(String quoteId);

    boolean checkPaymentStatus(String quoteId);

    String getPaymentPreimage(String quoteId);

    void pay(String quoteId);

    int getPaymentExpiry();

    int getFeeReserve(String requestId);
}
