package xyz.tcheeric.cashu.mint.rest.spec003;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.nut20.MintQuoteSignatureMessage;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.mint.jpa.entity.CustomerPaymentFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.rest.spec003.support.AbstractVoucherDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestSupport;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cashu-mint#529 against a real PostgreSQL: a voucher quote can be NUT-20 locked, the key survives
 * the round trip through {@code voucher_quote.pubkey}, and only a mint request signed by it can
 * mint the funded quote.
 */
class VoucherQuoteNut20LockIT extends AbstractVoucherDurableIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEYSET_ID = "004cf8cba2f93266";
    private static final UUID MINT_ID = UUID.fromString("00000001-0000-0000-0000-000000000529");
    private static final int FACE_VALUE = 8;
    private static final long FEE = 1L;
    private static final String BLINDED_POINT =
            "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2";
    private static final byte[] LOCKING_PRIVATE_KEY =
            Hex.decode("0000000000000000000000000000000000000000000000000000000000000003");
    private static final String LOCKING_KEY =
            "02f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9";

    @Value("${local.server.port}")
    int port;

    @MockBean
    MintLoadService mintLoadService;

    private final RestTemplate restTemplate = new RestTemplate();

    private static MintProtocolService originalProtocolService;

    @BeforeAll
    static void stubGatewayAndSigningKeys() throws CashuErrorException {
        originalProtocolService = MintProtocolServiceFactory.getInstance();
        Gateway gateway = Mockito.mock(Gateway.class);
        Mockito.when(gateway.createMintQuote(Mockito.anyString(), Mockito.anyInt(), Mockito.isNull()))
                .thenAnswer(call -> call.getArgument(0));
        Mockito.when(gateway.getRequest(Mockito.anyString())).thenReturn("lnbc10n1test");
        Mockito.when(gateway.getPaymentExpiry(Mockito.anyString())).thenReturn(600);
        Mockito.when(gateway.checkPaymentStatus(Mockito.anyString())).thenReturn(true);

        MintProtocolService stub = Mockito.spy(originalProtocolService);
        Mockito.doReturn(gateway).when(stub).createGateway(Mockito.any(PaymentMethod.class));
        Mockito.doReturn(gateway).when(stub).createGateway(Mockito.any(PaymentMethod.class), Mockito.anyString());
        Mockito.doAnswer(call -> privateKeyForAmount(call.getArgument(1)))
                .when(stub).getPrivateKeyForSigning(Mockito.anyString(), Mockito.anyInt(), Mockito.any(Mint.class));
        MintProtocolServiceFactory.setInstance(stub);
    }

    @AfterAll
    static void restoreProtocolService() {
        MintProtocolServiceFactory.setInstance(originalProtocolService);
    }

    @BeforeEach
    void serveTheTestKeyset() throws CashuErrorException {
        Mint mint = mintWithKeys();
        Mockito.when(mintLoadService.load(Mockito.any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        Mockito.when(mintLoadService.load(Mockito.anyBoolean())).thenReturn(List.of(mint));
    }

    /**
     * Ensures a voucher quote requested with a pubkey stores it on the row, and that both the
     * create response and the status route echo it, as NUT-20 requires.
     */
    @Test
    void shouldStoreAndEchoTheLockingKeyOfAVoucherQuote() throws Exception {
        // Act
        ResponseEntity<String> created = post("/v1/mint/quote/voucher/bolt11",
                Map.of("amount", 1000, "unit", "sat", "pubkey", LOCKING_KEY));
        String quoteId = MAPPER.readTree(created.getBody()).path("quote").asText();
        ResponseEntity<String> status = get("/v1/mint/quote/voucher/bolt11/" + quoteId);

        // Assert
        assertThat(created.getStatusCode()).as("body=%s", created.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(MAPPER.readTree(created.getBody()).path("pubkey").asText()).isEqualTo(LOCKING_KEY);
        assertThat(voucherQuoteJpaRepository.findById(quoteId).orElseThrow().getPubkey()).isEqualTo(LOCKING_KEY);
        assertThat(MAPPER.readTree(status.getBody()).path("pubkey").asText()).isEqualTo(LOCKING_KEY);
    }

    /** Ensures a voucher quote request with an invalid pubkey is refused with 20009. */
    @Test
    void shouldRefuseAVoucherQuoteWithAnInvalidKey() throws Exception {
        // Act
        ResponseEntity<String> refused = post("/v1/mint/quote/voucher/bolt11",
                Map.of("amount", 1000, "unit", "sat", "pubkey", "02deadbeef"));

        // Assert
        assertThat(refused.getStatusCode().is4xxClientError()).as("body=%s", refused.getBody()).isTrue();
        assertThat(MAPPER.readTree(refused.getBody()).path("code").asInt())
                .isEqualTo(CashuErrorCode.pubkey_required_for_mint_quote.getCode());
    }

    /**
     * Ensures that someone holding only the id of a funded, locked voucher quote cannot mint it:
     * the request is refused with 20008 and the quote stays FUNDED for its rightful holder.
     */
    @Test
    void shouldRefuseToMintAFundedLockedVoucherQuoteWithoutASignature() throws Exception {
        // Arrange
        String quoteId = seedFundedQuote(LOCKING_KEY);

        // Act
        ResponseEntity<String> refused = mint(quoteId, null);

        // Assert
        assertThat(refused.getStatusCode().is4xxClientError()).as("body=%s", refused.getBody()).isTrue();
        assertThat(MAPPER.readTree(refused.getBody()).path("code").asInt())
                .isEqualTo(CashuErrorCode.mint_signature_invalid.getCode());
        assertThat(voucherQuoteJpaRepository.findById(quoteId).orElseThrow().getLifecycleState())
                .isEqualTo(VoucherLifecycleState.FUNDED);
    }

    /** Ensures the holder of the locking key can mint the funded voucher quote. */
    @Test
    void shouldMintAFundedLockedVoucherQuoteSignedByItsKey() throws Exception {
        // Arrange
        String quoteId = seedFundedQuote(LOCKING_KEY);

        // Act
        ResponseEntity<String> minted = mint(quoteId, sign(quoteId));

        // Assert
        assertThat(minted.getStatusCode().is2xxSuccessful()).as("body=%s", minted.getBody()).isTrue();
        assertThat(MAPPER.readTree(minted.getBody()).path("signatures")).hasSize(1);
    }

    private String seedFundedQuote(String pubkey) {
        String fundingId = VoucherTestSupport.newFundingId();
        CustomerPaymentFundingEntity funding = VoucherTestSupport.customerPaymentFunding(
                fundingId, FEE, "phoenixd-it", "evt-" + fundingId);
        voucherFundingJpaRepository.saveAndFlush(funding);

        String quoteId = UUID.randomUUID().toString();
        VoucherQuoteEntity quote = VoucherTestSupport.fundedQuote(quoteId, FACE_VALUE, fundingId);
        quote.setChargedAmount(FEE);
        quote.setFee(FEE);
        quote.setPubkey(pubkey);
        voucherQuoteJpaRepository.saveAndFlush(quote);
        return quoteId;
    }

    private ResponseEntity<String> mint(String quoteId, String signature) {
        Map<String, Object> body = new HashMap<>();
        body.put("quote", quoteId);
        body.put("outputs", List.of(Map.of("amount", FACE_VALUE, "id", KEYSET_ID, "B_", BLINDED_POINT)));
        if (signature != null) {
            body.put("signature", signature);
        }
        return post("/v1/mint/bolt11", body);
    }

    private static String sign(String quoteId) throws Exception {
        BlindedMessage output = new BlindedMessage(FACE_VALUE, KeysetId.fromString(KEYSET_ID),
                PublicKey.fromString(BLINDED_POINT), null);
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(MintQuoteSignatureMessage.forQuote(quoteId, List.of(output)));
        return Hex.toHexString(Schnorr.sign(hash, LOCKING_PRIVATE_KEY));
    }

    private ResponseEntity<String> get(String path) {
        try {
            return restTemplate.getForEntity("http://localhost:" + port + path, String.class);
        } catch (HttpStatusCodeException error) {
            return ResponseEntity.status(error.getStatusCode()).body(error.getResponseBodyAsString());
        }
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            return restTemplate.postForEntity("http://localhost:" + port + path,
                    new HttpEntity<>(MAPPER.writeValueAsString(body), headers), String.class);
        } catch (HttpStatusCodeException error) {
            return ResponseEntity.status(error.getStatusCode()).body(error.getResponseBodyAsString());
        } catch (Exception error) {
            throw new IllegalStateException("Failed to POST " + path, error);
        }
    }

    private static PrivateKey privateKeyForAmount(Integer amount) {
        return PrivateKey.fromString(String.format("%064x", BigInteger.valueOf(amount)));
    }

    private static Mint mintWithKeys() {
        Mint mint = new Mint(MINT_ID.toString());
        Keys keys = new Keys();
        keys.put(BigInteger.valueOf(FACE_VALUE), PrivateKey.derivePublicKey(privateKeyForAmount(FACE_VALUE)));
        mint.addKeySet(KeySet.builder().id(KEYSET_ID).unit("sat").keys(keys).build());
        return mint;
    }
}
