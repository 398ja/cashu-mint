package cashu.vault.impl.fs;

import cashu.common.model.PrivateKey;
import cashu.common.model.Signature;
import cashu.common.protocol.CashuException;
import cashu.common.protocol.Error;
import cashu.util.Utils;
import cashu.vault.FSVault;
import cashu.vault.config.ProofConfiguration;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;

@AllArgsConstructor
@Log
public class FSProofVault extends FSVault<ProofConfiguration> {

    @NonNull
    private final ProofConfiguration proofConfiguration;

    @Override
    public void store() throws CashuException {
        try {
            var baseDir = getBaseDir();
            String secret = proofConfiguration.getSecret();
            String unblindedSignature = proofConfiguration.getUnblindedSignature();

            // <baseDir>/mint/<privateKey>/.proofs/[secret]
            Path path = Paths.get(baseDir, "mint", proofConfiguration.getMint().getPrivateKey(), ".proofs", secret);
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.createFile(path);
            }
            Files.write(path, Utils.hexStringToBytes(unblindedSignature));
            log.log(Level.INFO, "Stored proof {0}", path.toString());
        } catch (Exception e) {
            Error error = new Error(e);
            error.setDetail("Failed to store proof");
            throw new CashuException(error);
        }
    }

    @Override
    public String retrieve(@NonNull String key, boolean archive) throws CashuException {
        log.log(Level.INFO, "Retrieving proof {0}", key);
        try {
            var baseDir = getBaseDir(archive);

            assert proofConfiguration.getSecret().equals(key) : "The key does not match the proof's secret";

            Path path = Paths.get(baseDir, "mint", proofConfiguration.getMint().getPrivateKey(), ".proofs", key);

            if (Files.exists(path)) {
                log.log(Level.INFO, "The proof\'s path exists {0}", path.toString());
                byte[] keyBytes = Files.readAllBytes(path);
                return Signature.fromBytes(keyBytes).toString();
            } else {
                return null;
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "Failed to retrieve proof", e);
            throw new CashuException(e);
        }
    }

    @Override
    public void archive(String key) throws CashuException {
        throw new CashuException(new IllegalAccessException("Not implemented"));
    }
}
