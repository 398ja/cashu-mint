package xyz.tcheeric.cashu.mint.proto.tasks;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.RSSProof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;
import xyz.tcheeric.cashu.mint.proto.ports.CachedSwapResponse;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHold;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHoldRepository;
import xyz.tcheeric.cashu.mint.proto.ports.SwapResponseCache;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultServiceMocks;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.SwapRequestFingerprint;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

/**
 * AAE benchmark harness (read-only during a tuning session). Times full {@link SwapTask}
 * executions with REAL verification (BDHKE) and REAL blind signing against an in-memory keyset;
 * only persistence ports are faked. Prints {@code swap_us_geomean:} and {@code peak_heap_mb:}.
 *
 * <p>Run: {@code mvn -q -pl cashu-mint-protocol test -Dtest=SwapBench -Dbench=true}
 */
public final class SwapBench {

    private static final String KEYSET_ID = "0123456789abcdef";
    private static final int[] SIZES = {1, 4, 16};
    private static final int WARMUP = 150;
    private static final int ITERS = 400;

    public static void main(String[] args) throws Exception {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        PrivateKey mintKey = PrivateKey.fromBytes(
                xyz.tcheeric.cashu.crypto.Schnorr.generatePrivateKey());
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        Mockito.when(service.getPrivateKey(anyString(), anyInt(), any(Mint.class))).thenReturn(mintKey);
        Mockito.when(service.getPrivateKeyForSigning(anyString(), anyInt(), any(Mint.class)))
                .thenReturn(mintKey);

        Mint mint = new Mint(UUID.randomUUID().toString());
        mint.addKeySet(KeySet.builder().id(KEYSET_ID).unit("sat").build());
        MintLoadService loadService = Mockito.mock(MintLoadService.class);
        Mockito.when(loadService.load(any(UUID.class), Mockito.eq(false))).thenReturn(mint);
        Mockito.when(loadService.keySets(false)).thenReturn(new java.util.ArrayList<>(mint.getKeySets()));
        Mockito.when(loadService.keySets(true)).thenReturn(List.of());
        MintVaultService mintVault = Mockito.mock(MintVaultService.class);
        Mockito.when(mintVault.retrieveMint(anyString())).thenReturn(new MintEntity());

        double logSum = 0;
        try (var factory = Mockito.mockStatic(MintProtocolServiceFactory.class)) {
            factory.when(MintProtocolServiceFactory::getInstance).thenReturn(service);
            for (int size : SIZES) {
                List<PostSwapRequest<RandomStringSecret>> reqs = new ArrayList<>();
                for (int i = 0; i < WARMUP + ITERS; i++) {
                    reqs.add(build(size, mintKey));
                }
                for (int i = 0; i < WARMUP; i++) {
                    run(reqs.get(i), size, loadService, mintVault);
                }
                long t0 = System.nanoTime();
                for (int i = 0; i < ITERS; i++) {
                    PostSwapResponse r = run(reqs.get(WARMUP + i), size, loadService, mintVault);
                    if (r.getBlindSignatures().size() != size) {
                        throw new IllegalStateException("assertion: one signature per output");
                    }
                }
                double us = (System.nanoTime() - t0) / 1000.0 / ITERS;
                System.out.printf("size_%d_us: %.2f%n", size, us);
                logSum += Math.log(us);
            }
        }
        System.out.printf("swap_us_geomean: %.3f%n", Math.exp(logSum / SIZES.length));
        Runtime rt = Runtime.getRuntime();
        System.out.printf("peak_heap_mb: %.1f%n", (rt.totalMemory() - rt.freeMemory()) / 1048576.0);
    }

    private static PostSwapRequest<RandomStringSecret> build(int n, PrivateKey mintKey) throws Exception {
        List<Proof<RandomStringSecret>> inputs = new ArrayList<>();
        List<BlindedMessage> outputs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            RandomStringSecret secret = RandomStringSecret.create();
            org.bouncycastle.math.ec.ECPoint y = xyz.tcheeric.cashu.crypto.BDHKEUtils.hashToCurve(
                    secret.toString().getBytes(StandardCharsets.UTF_8));
            byte[] c = xyz.tcheeric.cashu.crypto.BDHKEUtils.signBlindedMessage(
                    y, xyz.tcheeric.cashu.crypto.util.Utils.bigIntFromBytes(mintKey.toBytes()))
                    .getEncoded(true);
            RSSProof p = new RSSProof();
            p.setAmount(1);
            p.setKeySetId(KEYSET_ID);
            p.setSecret(secret);
            p.setUnblindedSignature(Signature.fromBytes(c));
            inputs.add(p);

            BlindedMessage bm = new BlindedMessage();
            bm.setAmount(1);
            bm.setKeySetId(KeysetId.fromString(KEYSET_ID));
            PrivateKey r = PrivateKey.fromBytes(xyz.tcheeric.cashu.crypto.Schnorr.generatePrivateKey());
            bm.setBlindedMessage(PublicKey.fromBytes(PrivateKey.derivePublicKey(r).getBytes()));
            outputs.add(bm);
        }
        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();
        request.setInputs(inputs);
        request.setBlindedMessages(outputs);
        return request;
    }

    private static PostSwapResponse run(PostSwapRequest<RandomStringSecret> request, int n,
                                        MintLoadService load, MintVaultService mintVault) throws Exception {
        ProofVaultService proofVault = ProofVaultServiceMocks.keyingProofsByIssuanceKey();
        Mockito.when(proofVault.insertOrClaimForHold(any(), anyString(), any()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        Mockito.when(proofVault.commitSpentForHold(anyString())).thenReturn(n);
        try {
            return new SwapTask<>(UUID.randomUUID(), request, load, new DefaultSignatureVaultService(),
                    mintVault, proofVault, new Holds(), new Cache(), InFlightReplayWait.DEFAULT).execute();
        } catch (CashuErrorException e) {
            throw new IllegalStateException("swap refused: " + e, e);
        }
    }

    private record Hold(String holdId, String fingerprintHex, SwapHoldPhase phase, int inputCount,
                        java.time.Instant createdAt, java.time.Instant updatedAt) implements SwapHold {}

    private static final class Holds implements SwapHoldRepository {
        private final List<Hold> holds = new ArrayList<>();
        public void open(String id, int n, SwapRequestFingerprint f) {
            var now = java.time.Instant.now();
            holds.add(new Hold(id, f.hex(), SwapHoldPhase.HELD, n, now, now));
        }
        public void advance(String id, SwapHoldPhase phase) {}
        public List<SwapHold> findByRequestFingerprint(SwapRequestFingerprint f) { return List.of(); }
    }

    private static final class Cache implements SwapResponseCache {
        private final Map<String, String> entries = new HashMap<>();
        public Optional<CachedSwapResponse> find(SwapRequestFingerprint f) { return Optional.empty(); }
        public void store(SwapRequestFingerprint f, String json) { entries.put(f.hex(), json); }
    }
}
