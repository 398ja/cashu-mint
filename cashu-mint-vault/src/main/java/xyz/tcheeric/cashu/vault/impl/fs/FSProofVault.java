package xyz.tcheeric.cashu.vault.impl.fs;

import cashu.util.ThreadUtil;
import cashu.util.Utils;
import xyz.tcheeric.cashu.common.model.Signature;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.FSVault;
import xyz.tcheeric.cashu.vault.config.ProofConfiguration;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@AllArgsConstructor
@Slf4j
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
            log.info("Stored proof {}", path.toString());
        } catch (Exception e) {
            throw new CashuErrorException(e);
        }
    }

    public void storeWitness(@NonNull String witness) throws CashuErrorException {
        try {
            var baseDir = getBaseDir();
            String secret = proofConfiguration.getHashToCurveSecret();

            // <baseDir>/mint/<privateKey>/.proofs/[secret].witness
            Path path = Paths.get(baseDir, "mint", proofConfiguration.getMint().getId(), ".proofs",
                    secret + ".witness");
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.createFile(path);
            }
            Files.write(path, witness.getBytes(StandardCharsets.UTF_8));
            log.info("Stored proof {}", path.toString());
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
            Path path = Paths.get(baseDir, "mint", proofConfiguration.getMint().getId(), ".proofs", "pending",
                    hashToCurveSecret);
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.createFile(path);
            }
            Files.write(path, Utils.hexStringToBytes(unblindedSignature));
            log.info("Stored proof {}", path.toString());
        } catch (Exception e) {
            throw new CashuErrorException(e);
        } finally {
            ThreadUtil.PROOF_STATE_LOCK.unlock();
        }
    }

    @Override
    public String retrieve(@NonNull String hashToCurveSecret, boolean archive) throws CashuErrorException {
        log.info("Retrieving proof {}", hashToCurveSecret);
        try {
            var baseDir = getBaseDir(archive);

            Path path = Paths.get(baseDir, "mint", proofConfiguration.getMint().getId(), ".proofs", hashToCurveSecret);

            if (Files.exists(path)) {
                log.info("The proof's path exists {}", path.toString());
                byte[] keyBytes = Files.readAllBytes(path);
                return Signature.fromBytes(keyBytes).toString();
            } else {
                return null;
            }
        } catch (Exception e) {
            log.error("Failed to retrieve proof", e);
            throw new CashuErrorException(e);
        }
    }

    public String retrievePending(@NonNull String hashToCurveSecret) throws CashuErrorException {
        ThreadUtil.PROOF_STATE_LOCK.lock();
        log.info("Retrieving pending proof {}", hashToCurveSecret);
        try {
            var baseDir = getBaseDir(false);

            Path path = Paths.get(baseDir, "mint", proofConfiguration.getMint().getId(), ".proofs", "pending",
                    hashToCurveSecret);

            if (Files.exists(path)) {
                log.info("The pending proof's path exists {}", path.toString());
                byte[] keyBytes = Files.readAllBytes(path);
                return Signature.fromBytes(keyBytes).toString();
            } else {
                return null;
            }
        } catch (Exception e) {
            log.error("Failed to retrieve proof", e);
            throw new CashuErrorException(e);
        } finally {
            ThreadUtil.PROOF_STATE_LOCK.unlock();
        }
    }

    public String retrieveWitness(@NonNull String hashToCurveSecret) {
        log.info("Retrieving witness {}", hashToCurveSecret);
        try {
            var baseDir = getBaseDir(false);

            Path path = Paths.get(baseDir, "mint", proofConfiguration.getMint().getId(), ".proofs",
                    hashToCurveSecret + ".witness");

            if (Files.exists(path)) {
                log.info("The witness's path exists {}", path.toString());
                byte[] keyBytes = Files.readAllBytes(path);
                return new String(keyBytes, StandardCharsets.UTF_8);
            } else {
                return null;
            }
        } catch (Exception e) {
            log.error("Failed to retrieve witness", e);
            return null;
        }
    }

    public void deletePending() throws CashuErrorException {
        ThreadUtil.PROOF_STATE_LOCK.lock();
        try {
            var baseDir = getBaseDir(false);
            var path = Paths.get(baseDir, "mint", proofConfiguration.getMint().getId(), ".proofs", "pending",
                    proofConfiguration.getHashToCurveSecret());
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.error("Failed to delete proof", e);
            throw new CashuErrorException(e);
        } finally {
            ThreadUtil.PROOF_STATE_LOCK.unlock();
        }
    }

    @Override
    public void archive(@NonNull String hashToCurveSecret) throws CashuErrorException {
        log.info("Archiving proof {}", hashToCurveSecret);
        try {
            var baseDir = getBaseDir(false);
            var archBaseDir = getBaseDir(true);

            Path path = Paths.get(baseDir, "mint", proofConfiguration.getMint().getId(), ".proofs", hashToCurveSecret);

            if (Files.exists(path)) {
                log.info("The proofs path exists: {}", path.toString());
                Path archivePath = Paths.get(archBaseDir, "mint", proofConfiguration.getMint().getId(), ".proofs",
                        hashToCurveSecret);
                Files.createDirectories(archivePath.getParent()); // Ensure the target directory exists
                Files.move(path, archivePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.error("Failed to archive proof", e);
            throw new CashuErrorException(e);
        }
    }
}
