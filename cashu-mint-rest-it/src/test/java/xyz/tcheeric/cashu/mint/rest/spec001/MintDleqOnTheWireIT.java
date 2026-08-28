package xyz.tcheeric.cashu.mint.rest.spec001;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.DLEQUtils;
import xyz.tcheeric.cashu.mint.jpa.entity.MintQuoteEntity;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #389 — the mint advertises NUT-12, so every {@code BlindSignature} it puts
 * on the wire must carry a DLEQ proof a wallet can verify.
 *
 * <p>Unit tests pin the fail-closed behaviour inside {@code SignBlindedMessageTask};
 * this drives the real {@code POST /v1/mint/bolt11} endpoint instead, so the
 * assertion is about what a wallet actually receives rather than about an internal
 * call. The proof is verified against the mint's published key, which is the whole
 * point of NUT-12: a proof nobody can check is no better than none.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/12.md">NUT-12</a>
 */
class MintDleqOnTheWireIT extends AbstractMintDurableIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ECNamedCurveParameterSpec CURVE = ECNamedCurveTable.getParameterSpec("secp256k1");

    private static final String TEST_KEYSET_ID = "004cf8cba2f93266";
    private static final UUID TEST_MINT_UUID = UUID.fromString("00000001-0000-0000-0000-000000000001");
    private static final String QUOTE_ID = "q-it-dleq-wire";
    private static final String B_AMT_8 = "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2";
    private static final String B_AMT_2 = "025f9d298d8d9e774c81ee64927a27e6e6b6e18f65447eb6a16808f92b84e44112";

    @Value("${local.server.port}")
    int port;

    @MockBean
    MintLoadService mintLoadService;

    private final RestTemplate restTemplate = new RestTemplate();

    private static MintProtocolService originalProtocolService;

    @BeforeAll
    static void overrideProtocolService() throws Exception {
        originalProtocolService = MintProtocolServiceFactory.getInstance();
        Gateway gateway = Mockito.mock(Gateway.class);
        Mockito.when(gateway.checkPaymentStatus(Mockito.anyString())).thenReturn(true);
        Mockito.when(gateway.getAmount(Mockito.anyString())).thenReturn(10);

        MintProtocolService stub = Mockito.spy(originalProtocolService);
        Mockito.doReturn(gateway).when(stub).createGateway(Mockito.any(PaymentMethod.class));
        Mockito.doReturn(gateway).when(stub)
                .createGateway(Mockito.any(PaymentMethod.class), Mockito.anyString());
        Mockito.doAnswer(invocation -> privateKeyForAmount(invocation.getArgument(1)))
                .when(stub).getPrivateKey(Mockito.anyString(), Mockito.anyInt(), Mockito.any(Mint.class));
        Mockito.doAnswer(invocation -> privateKeyForAmount(invocation.getArgument(1)))
                .when(stub).getPrivateKeyForSigning(Mockito.anyString(), Mockito.anyInt(), Mockito.any(Mint.class));
        MintProtocolServiceFactory.setInstance(stub);
    }

    @AfterAll
    static void restoreProtocolService() {
        MintProtocolServiceFactory.setInstance(originalProtocolService);
    }

    @BeforeEach
    void seedPaidQuote() throws CashuErrorException {
        Mint mint = createMintWithKeys();
        Mockito.when(mintLoadService.load(Mockito.any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
        Mockito.when(mintLoadService.load(Mockito.anyBoolean())).thenReturn(List.of(mint));

        MintQuoteEntity quote = new MintQuoteEntity();
        quote.setQuoteId(QUOTE_ID);
        quote.setAmount(10L);
        quote.setUnit("sat");
        quote.setMintUrl("https://mint.it.example");
        quote.setPaymentMethod("bolt11");
        quote.setLifecycleState(LifecycleState.PAID);
        quote.setRequestHash("0".repeat(64));
        mintQuoteJpaRepository.save(quote);
    }

    // Every signature a mint response carries must include a dleq whose (e, s)
    // verifies against the key that signed the blinded message.
    @Test
    void everyMintedSignatureCarriesAVerifiableDleq() throws Exception {
        Map<String, String> blindedMessageByAmount = Map.of("8", B_AMT_8, "2", B_AMT_2);

        ResponseEntity<String> response = postMint(List.of(
                Map.of("amount", 8, "id", TEST_KEYSET_ID, "B_", B_AMT_8),
                Map.of("amount", 2, "id", TEST_KEYSET_ID, "B_", B_AMT_2)));

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("status=%s body=%s", response.getStatusCode(), response.getBody())
                .isTrue();

        JsonNode signatures = MAPPER.readTree(response.getBody()).get("signatures");
        assertThat(signatures).as("the response must carry signatures").isNotNull();
        assertThat(signatures).hasSize(2);

        for (JsonNode signature : signatures) {
            String amount = signature.get("amount").asText();
            JsonNode dleq = signature.get("dleq");

            assertThat(dleq)
                    .as("NUT-12 is advertised, so signature amount=%s must carry a dleq", amount)
                    .isNotNull();
            assertThat(dleqVerifies(
                    dleq.get("e").asText(),
                    dleq.get("s").asText(),
                    blindedMessageByAmount.get(amount),
                    signature.get("C_").asText(),
                    Integer.parseInt(amount)))
                    .as("the dleq on signature amount=%s must verify against the signing key", amount)
                    .isTrue();
        }
    }

    private static boolean dleqVerifies(String challenge,
                                        String signatureScalar,
                                        String blindedMessageHex,
                                        String blindSignatureHex,
                                        int amount) {
        ECPoint blindedMessage = decode(blindedMessageHex);
        ECPoint blindSignature = decode(blindSignatureHex);
        ECPoint publicKey = CURVE.getG().multiply(BigInteger.valueOf(amount)).normalize();
        return DLEQUtils.verifyProof(challenge, signatureScalar, blindedMessage, blindSignature, publicKey);
    }

    private static ECPoint decode(String compressedHex) {
        return CURVE.getCurve().decodePoint(Hex.decode(compressedHex)).normalize();
    }

    private ResponseEntity<String> postMint(List<Map<String, Object>> outputs) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            String body = MAPPER.writeValueAsString(Map.of("quote", QUOTE_ID, "outputs", outputs));
            return restTemplate.postForEntity(
                    "http://localhost:" + port + "/v1/mint/bolt11",
                    new HttpEntity<>(body, headers),
                    String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .headers(e.getResponseHeaders())
                    .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to POST /v1/mint/bolt11", e);
        }
    }

    private static PrivateKey privateKeyForAmount(Integer amount) {
        return PrivateKey.fromString(String.format("%064x", BigInteger.valueOf(amount)));
    }

    private static Mint createMintWithKeys() {
        Mint mint = new Mint(TEST_MINT_UUID.toString());
        Keys keys = new Keys();
        for (int amount : new int[]{1, 2, 4, 8}) {
            keys.put(BigInteger.valueOf(amount),
                    PrivateKey.derivePublicKey(privateKeyForAmount(amount)));
        }
        mint.addKeySet(KeySet.builder().id(TEST_KEYSET_ID).unit("sat").keys(keys).build());
        return mint;
    }
}
