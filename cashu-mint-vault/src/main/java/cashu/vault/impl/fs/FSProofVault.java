package cashu.vault.impl.fs;

import cashu.common.model.Signature;
import cashu.common.util.CashuErrorException;
import cashu.util.ThreadUtil;
import cashu.util.Utils;
import cashu.vault.FSVault;
import cashu.vault.config.ProofConfiguration;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

@AllArgsConstructor
@Log
public class FSProofVault extends FSVault<ProofConfiguration> {

    @NonNull
    private final ProofConfiguration proofConfiguration;

    @Override
    public void store() throws CashuErrorException {
        try {
            var baseDir = getBaseDir();
            String hashToCurveSecret = proofConfiguration.getHashToCurveSecret();
            String unblindedSignature = proofConfiguration.getUnblindedSignature();

            // <baseDir>/mint/<privateKey>/.proofs/[hashToCurveSecret]
            Path path = Paths.get(baseDir, "mint", proofConfiguration.getMint().getId(), ".proofs", hashToCurveSecret);
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.createFile(path);
            }
            Files.write(path, Utils.hexStringToBytes(unblindedSignature));
            log.log(Level.INFO, "Stored proof {0}", path.toString());
        } catch (Exception e) {
            throw new CashuErrorException(e);
        }
    }

    public void storeWitness(@NonNull String witness) throws CashuErrorException {
        try {
            var baseDir = getBaseDir();
            String secret = proofConfiguration.getHashToCurveSecret();

            // <baseDir>/mint/<privateKey>/.proofs/[secret].witness
            Path path = Paths.get(baseDir, "mint", proofConfiguration.getMint().getId(), ".proofs", secret + ".witness");
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.createFile(path);
            }
            Files.write(path, witness.getBytes(StandardCharsets.UTF_8));
            log.log(Level.INFO, "Stored proof {0}", path.toString());
        } catch (Exception e) {
            throw new CashuErrorException(e);
        }
    }

    public void storePending() throws CashuErrorException {
        ThreadUtil.PROOF_STATE_LOCK.lock();
        try {
            var baseDir = getBaseDir();
            String hashToCurveSecret = proofConfiguration.getHashToCurveSecret();
            String unblindedSignature = proofConfiguration.getUnblindedSignature();

            // <baseDir>/mint/<privateKey>/.proofs/[hashToCurveSecret]
            Path path = Paths.get(baseDir, "mint", proofConfiguration.getMint().getId(), ".proofs", "pending", hashToCurveSecret);
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.createFile(path);
            }
            Files.write(path, Utils.hexStringToBytes(unblindedSignature));
            log.log(Level.INFO, "Stored proof {0}", path.toString());
        } catch (Exception e) {
            throw new CashuErrorException(e);
        }  finally {
            ThreadUtil.PROOF_STATE_LOCK.unlock();
        }
    }

    @Override
    public String retrieve(@NonNull String hashToCurveSecret, boolean archive) throws CashuErrorException {
        log.log(Level.INFO, "Retrieving proof {0}", hashToCurveSecret);
        try {
            var baseDir = getBaseDir(archive);

            Path path = Paths.get(baseDir, "mint", proofConfiguration.getMint().getId(), ".proofs", hashToCurveSecret);

            if (Files.exists(path)) {
                log.log(Level.INFO, "The proof's path exists {0}", path.toString());
                byte[] keyBytes = Files.readAllBytes(path);
                return Signature.fromBytes(keyBytes).toString();
            } else {
                return null;
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "Failed to retrieve proof", e);
            throw new CashuErrorException(e);
        }
    }

    public String retrievePending(@NonNull String hashToCurveSecret) throws CashuErrorException {
        ThreadUtil.PROOF_STATE_LOCK.lock();
        log.log(Level.INFO, "Retrieving pending proof {0}", hashToCurveSecret);
        try {
            var baseDir = getBaseDir(false);

            Path path = Paths.get(baseDir, "mint", proofConfiguration.getMint().getId(), ".proofs", "pending", hashToCurveSecret);

            if (Files.exists(path)) {
                log.log(Level.INFO, "The pending proof's path exists {0}", path.toString());
                byte[] keyBytes = Files.readAllBytes(path);
                return Signature.fromBytes(keyBytes).toString();
            } else {
                return null;
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "Failed to retrieve proof", e);
            throw new CashuErrorException(e);
        }  finally {
            ThreadUtil.PROOF_STATE_LOCK.unlock();
        }
    }

    public String retrieveWitness(@NonNull String hashToCurveSecret) {
        log.log(Level.INFO, "Retrieving witness {0}", hashToCurveSecret);
        try {
            var baseDir = getBaseDir(false);

            Path path = Paths.get(baseDir, "mint", proofConfiguration.getMint().getId(), ".proofs", hashToCurveSecret + ".witness");

            if (Files.exists(path)) {
                log.log(Level.INFO, "The witness's path exists {0}", path.toString());
                byte[] keyBytes = Files.readAllBytes(path);
                return new String(keyBytes, StandardCharsets.UTF_8);
            } else {
                return null;
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "Failed to retrieve witness", e);
            return null;
        }
    }

    public void deletePending() throws CashuErrorException {
        ThreadUtil.PROOF_STATE_LOCK.lock();
        try {
            var baseDir = getBaseDir(false);
            var path = Paths.get(baseDir, "mint", proofConfiguration.getMint().getId(), ".proofs", "pending", proofConfiguration.getHashToCurveSecret());
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.log(Level.SEVERE, "Failed to delete proof", e);
            throw new CashuErrorException(e);
        } finally {
            ThreadUtil.PROOF_STATE_LOCK.unlock();
        }
    }

    @Override
    public void archive(@NonNull String hashToCurveSecret) throws CashuErrorException {
        log.log(Level.INFO, "Archiving proof {0}", hashToCurveSecret);
        try {
            var baseDir = getBaseDir(false);
            var archBaseDir = getBaseDir(true);

            Path path = Paths.get(baseDir, "mint", proofConfiguration.getMint().getId(), ".proofs", hashToCurveSecret);

            if (Files.exists(path)) {
                log.log(Level.INFO, "The proofs path exists: {0}", path.toString());
                Path archivePath = Paths.get(archBaseDir, "mint", proofConfiguration.getMint().getId(), ".proofs", hashToCurveSecret);
                Files.createDirectories(archivePath.getParent()); // Ensure the target directory exists
                Files.move(path, archivePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "Failed to archive proof", e);
            throw new CashuErrorException(e);
        }
    }
}
