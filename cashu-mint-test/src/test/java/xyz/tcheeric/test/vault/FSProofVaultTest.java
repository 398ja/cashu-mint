package xyz.tcheeric.test.vault;

import cashu.util.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.model.Mint;
import xyz.tcheeric.cashu.common.model.RandomStringSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.vault.FSVault;
import xyz.tcheeric.cashu.vault.config.MintConfiguration;
import xyz.tcheeric.cashu.vault.config.ProofConfiguration;
import xyz.tcheeric.cashu.vault.impl.fs.FSMintVault;
import xyz.tcheeric.cashu.vault.impl.fs.FSProofVault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FSProofVaultTest {

    private Mint mint;

    @BeforeEach
    public void setUp() throws CashuErrorException {
        this.mint = new Mint("" + System.currentTimeMillis());
        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        FSMintVault mintVault = new FSMintVault(mintConfiguration);
        mintVault.store();
    }

    @AfterEach
    public void tearDown() throws CashuErrorException {
        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        FSMintVault mintVault = new FSMintVault(mintConfiguration);
        //mintVault.archive(mintConfiguration.getId());
        mintVault.delete();
    }

    @Test
    public void storePending() throws CashuErrorException {
        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        byte[] hashToCurveSecret = BDHKEUtils.hashToCurve(RandomStringSecret.create().toString());
        ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, "02bc9097997d81afb2cc7346b5e4345a9346bd2a506eb7958598a72f0cf85163ea", Utils.bytesToHexString(hashToCurveSecret));
        FSProofVault proofVault = new FSProofVault(proofConfiguration);

        proofVault.storePending();
        Path pendingPath = Paths.get(FSVault.getBaseDir(false), "mint", mint.getId(), ".proofs", "pending", proofConfiguration.getHashToCurveSecret());
        assertTrue(pendingPath.toFile().exists());
    }

    @Test
    public void storeWitness() throws CashuErrorException, IOException {
        MintConfiguration mintConfiguration = new MintConfiguration(mint.getId());
        byte[] hashToCurveSecret = BDHKEUtils.hashToCurve(RandomStringSecret.create().toString());
        ProofConfiguration proofConfiguration = new ProofConfiguration(mintConfiguration, "02bc9097997d81afb2cc7346b5e4345a9346bd2a506eb7958598a72f0cf85163ea", Utils.bytesToHexString(hashToCurveSecret));
        FSProofVault proofVault = new FSProofVault(proofConfiguration);

        proofVault.storeWitness("witness");
        Path witnessPath = Paths.get(FSVault.getBaseDir(false), "mint", mint.getId(), ".proofs", proofConfiguration.getHashToCurveSecret() + ".witness");
        assertTrue(witnessPath.toFile().exists());
        byte[] witnessBytes = Files.readAllBytes(witnessPath);
        assertEquals("witness", new String(witnessBytes));

    }
}
