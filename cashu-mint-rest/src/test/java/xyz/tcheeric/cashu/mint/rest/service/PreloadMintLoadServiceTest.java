package xyz.tcheeric.cashu.mint.rest.service;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.nut.NUT03;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.tasks.InvalidateProofsTask;
import xyz.tcheeric.cashu.mint.proto.tasks.SignBlindedMessageTask;
import xyz.tcheeric.cashu.mint.proto.tasks.VerifyFeesTask;
import xyz.tcheeric.cashu.mint.proto.tasks.VerifyProofsTask;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.vault.api.VaultClientFactory;
import xyz.tcheeric.cashu.vault.db.client.KeySetVaultClient;
import xyz.tcheeric.cashu.vault.db.client.KeyVaultClient;
import xyz.tcheeric.cashu.vault.db.client.VaultClient;
import xyz.tcheeric.cashu.vault.db.model.KeyEntity;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;

public class PreloadMintLoadServiceTest {

    private static final String PRELOAD_JSON = """
            {
              "mintId": "1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae",
              "keySetId": "00e3372e61d05605",
              "unit": "sat",
              "keys": [
                {
                  "amount": 1,
                  "privateKeyHex": "4fbf609f4d521cf57b93bba3c530d7be1a1e5da7186c2d33990f0a4ead82ccf4"
                }
              ]
            }
            """;

    private Resource preloadResource() {
        return new ByteArrayResource(PRELOAD_JSON.getBytes(StandardCharsets.UTF_8));
    }

    // Ensures load fails fast when the vault rejects key storage during seeding.
    @Test
    void loadShouldPropagateVaultSeedFailures() {
        Resource resource = preloadResource();
        PreloadMintLoadService loader = new PreloadMintLoadService(resource);

        VaultClient<MintEntity> mintClient = Mockito.mock(VaultClient.class);
        KeySetVaultClient keySetClient = Mockito.mock(KeySetVaultClient.class);
        KeyVaultClient keyClient = Mockito.mock(KeyVaultClient.class);

        HttpClientErrorException seedFailure = HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "Not Found",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8
        );

        try (MockedStatic<VaultClientFactory> vaultFactory = Mockito.mockStatic(VaultClientFactory.class)) {
            vaultFactory.when(() -> VaultClientFactory.getClient(MintEntity.class)).thenReturn(mintClient);
            vaultFactory.when(VaultClientFactory::keySetClient).thenReturn(keySetClient);
            vaultFactory.when(VaultClientFactory::keyClient).thenReturn(keyClient);

            Mockito.when(mintClient.retrieve(any(String.class))).thenReturn(null);
            Mockito.when(mintClient.store(any(MintEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
            Mockito.when(keySetClient.getByKeySetId(any(String.class))).thenReturn(null);
            Mockito.when(keySetClient.store(any(KeySetEntity.class))).thenAnswer(invocation -> {
                KeySetEntity entity = invocation.getArgument(0);
                if (entity.getId() == null) {
                    entity.setId(UUID.randomUUID());
                }
                return entity;
            });
            Mockito.when(keyClient.store(any(KeyEntity.class))).thenThrow(seedFailure);

            CashuErrorException exception = assertThrows(CashuErrorException.class, () -> loader.load(UUID.randomUUID(), false));
            assertEquals("vault_seed_failed", exception.getMessage());
            Mockito.verify(keyClient).store(any(KeyEntity.class));
        }
    }

    // Validates swap aborts while seeding fails and succeeds after a healthy retry.
    @Test
    void swapShouldShortCircuitUntilVaultSeedSucceeds() throws CashuErrorException {
        Resource resource = preloadResource();
        PreloadMintLoadService loader = new PreloadMintLoadService(resource);

        VaultClient<MintEntity> mintClient = Mockito.mock(VaultClient.class);
        KeySetVaultClient keySetClient = Mockito.mock(KeySetVaultClient.class);
        KeyVaultClient keyClient = Mockito.mock(KeyVaultClient.class);

        AtomicBoolean failSeeding = new AtomicBoolean(true);
        AtomicInteger protocolFactoryCalls = new AtomicInteger();

        PostSwapRequest<RandomStringSecret> request = new PostSwapRequest<>();
        request.setInputs(Collections.<Proof<RandomStringSecret>>emptyList());
        request.setBlindedMessages(Collections.<BlindedMessage>emptyList());

        SignatureVaultService signatureVaultService = Mockito.mock(SignatureVaultService.class);
        MintProtocolService protocolService = Mockito.mock(MintProtocolService.class);

        try (MockedStatic<VaultClientFactory> vaultFactory = Mockito.mockStatic(VaultClientFactory.class);
             MockedStatic<MintProtocolServiceFactory> protocolFactory = Mockito.mockStatic(MintProtocolServiceFactory.class);
             MockedConstruction<VerifyProofsTask> verifyConstruction = Mockito.mockConstruction(VerifyProofsTask.class,
                     (mock, context) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<InvalidateProofsTask> invalidateConstruction = Mockito.mockConstruction(InvalidateProofsTask.class,
                     (mock, context) -> Mockito.when(mock.execute()).thenReturn(Collections.emptyList()));
             MockedConstruction<VerifyFeesTask> feesConstruction = Mockito.mockConstruction(VerifyFeesTask.class,
                     (mock, context) -> Mockito.doNothing().when(mock).execute());
             MockedConstruction<SignBlindedMessageTask> signConstruction = Mockito.mockConstruction(SignBlindedMessageTask.class,
                     (mock, context) -> Mockito.doReturn(null).when(mock).execute())) {

            vaultFactory.when(() -> VaultClientFactory.getClient(MintEntity.class)).thenReturn(mintClient);
            vaultFactory.when(VaultClientFactory::keySetClient).thenReturn(keySetClient);
            vaultFactory.when(VaultClientFactory::keyClient).thenReturn(keyClient);

            Mockito.when(mintClient.retrieve(any(String.class))).thenReturn(null);
            Mockito.when(mintClient.store(any(MintEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
            Mockito.when(keySetClient.getByKeySetId(any(String.class))).thenReturn(null);
            Mockito.when(keySetClient.store(any(KeySetEntity.class))).thenAnswer(invocation -> {
                KeySetEntity entity = invocation.getArgument(0);
                if (entity.getId() == null) {
                    entity.setId(UUID.randomUUID());
                }
                return entity;
            });
            Mockito.when(keyClient.store(any(KeyEntity.class))).thenAnswer(invocation -> {
                if (failSeeding.getAndSet(false)) {
                    throw HttpClientErrorException.create(
                            HttpStatus.NOT_FOUND,
                            "Not Found",
                            HttpHeaders.EMPTY,
                            new byte[0],
                            StandardCharsets.UTF_8
                    );
                }
                return invocation.getArgument(0);
            });

            protocolFactory.when(MintProtocolServiceFactory::getInstance).thenAnswer(invocation -> {
                protocolFactoryCalls.incrementAndGet();
                return protocolService;
            });

            assertThrows(CashuErrorException.class, () -> NUT03.swap(UUID.randomUUID(), request, loader, signatureVaultService));
            assertEquals(0, protocolFactoryCalls.get());

            PostSwapResponse response = NUT03.swap(UUID.randomUUID(), request, loader, signatureVaultService);
            assertNotNull(response);
            assertEquals(0, response.getBlindSignatures().size());

            assertEquals(1, protocolFactoryCalls.get());
            Mockito.verify(verifyConstruction.constructed().get(0)).execute();
            Mockito.verify(invalidateConstruction.constructed().get(0)).execute();
            Mockito.verify(feesConstruction.constructed().get(0)).execute();
        }
    }
}

