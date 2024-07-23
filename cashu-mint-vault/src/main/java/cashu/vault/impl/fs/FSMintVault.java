package cashu.vault.impl.fs;

import cashu.common.model.KeySet;
import cashu.common.model.Mint;
import cashu.common.util.CashuErrorException;
import cashu.vault.FSVault;
import cashu.vault.config.KeyConfiguration;
import cashu.vault.config.KeysetConfiguration;
import cashu.vault.config.MintConfiguration;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Optional;
import java.util.logging.Level;
import java.util.stream.Stream;

@AllArgsConstructor
@Log
public class FSMintVault extends FSVault<MintConfiguration> {

    @NonNull
    private final MintConfiguration mintConfiguration;

    public FSMintVault(@NonNull String id) {
        this.mintConfiguration = new MintConfiguration(id);
    }

    @Override
    public void store() throws CashuErrorException {

        // <baseDir>/mint/<privateKey>
        var baseDir = getBaseDir();
        log.log(Level.INFO, "Storing mint: {0} - Source: {1}", new Object[]{mintConfiguration.getId(), baseDir});
        Path dirPath = Paths.get(baseDir, "mint", mintConfiguration.getId());

        try {
            Files.createDirectories(dirPath);
        } catch (IOException e) {
            throw new CashuErrorException(e);
        }
    }

    @Override
    public String retrieve(@NonNull String key, boolean archive) {
        var baseDir = getBaseDir(archive);

        Path dirPath = Paths.get(baseDir, "mint", key);

        return dirPath.toString();
    }

/*
    @Override
    public void archive(@NonNull String key) throws CashuErrorException {
        Path archivePath = Paths.get(FSVault.mintArchivePath(mintConfiguration));
        Path dirPath = Paths.get(getBaseDir(), "mint", key);
        try {
            log.log(Level.INFO, "Archiving mint: {0} - Source: {1} - Destination: {2}", new Object[]{key, dirPath, archivePath});
            Files.move(dirPath, archivePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new CashuErrorException(e);
        }
    }
*/

    @Override
    public void archive(@NonNull String key) throws CashuErrorException {
        Path sourceDirPath = Paths.get(getBaseDir(), "mint", key);
        Path archivePath = Paths.get(FSVault.mintArchivePath(mintConfiguration));

        try {
            Files.walkFileTree(sourceDirPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path destFile = archivePath.resolve(sourceDirPath.relativize(file));
                    Files.move(file, destFile, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path dirToCreate = archivePath.resolve(sourceDirPath.relativize(dir));
                    if (Files.notExists(dirToCreate)) {
                        Files.createDirectories(dirToCreate);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc == null) {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    } else {
                        throw exc;
                    }
                }
            });
            log.log(Level.INFO, "Successfully archived mint: {0}", key);
        } catch (IOException e) {
            throw new CashuErrorException(e);
        }
    }

    @Override
    public void delete() throws CashuErrorException {
        Path dirPath = Paths.get(getBaseDir(), "mint", mintConfiguration.getId());
        try {
            log.log(Level.INFO, "Deleting mint: {0} - Source: {1}", new Object[]{mintConfiguration.getId(), dirPath});
            Files.walkFileTree(dirPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc == null) {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    } else {
                        throw exc;
                    }
                }
            });
        } catch (IOException e) {
            throw new CashuErrorException(e);
        }
    }

    public String getPrivateKey(@NonNull String unit, @NonNull Integer amount) {
        String mintPath = this.retrieve(mintConfiguration.getId(), false);
        Path dirPath = Paths.get(mintPath, unit, amount.toString());

        try (Stream<Path> paths = Files.list(dirPath)) {
            Optional<Path> keyFilePath = paths
                    .min(Comparator.comparingLong(p -> p.toFile().lastModified()));
            if (keyFilePath.isPresent()) {
                return keyFilePath.get().getFileName().toString();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return null;
    }

    public String getUnit(@NonNull String keySetId) {
        String mintPath = this.retrieve(mintConfiguration.getId(), false);
        Path dirPath = Paths.get(mintPath);

        try (Stream<Path> paths = Files.list(dirPath)) {
            Optional<String> unit = paths
                    .filter(Files::isDirectory)
                    .flatMap(directory -> {
                        try {
                            return Files.list(directory);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("." + keySetId))
                    .map(Path::getParent)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .findFirst();
            return unit.orElse(null);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Mint load(boolean archive) {
        return load(archive, false);
    }

    public static Mint load(boolean archive, boolean lazy) {
        String baseDir = getBaseDir(archive);
        try (Stream<Path> paths = Files.list(Paths.get(baseDir, "mint"))) {
            Optional<Path> mintDir = paths
                    .filter(Files::isDirectory)
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
            if (mintDir.isPresent()) {
                String privateKey = mintDir.get().getFileName().toString();
                MintConfiguration configuration = new MintConfiguration(privateKey);
                return load(configuration, archive, lazy);
            } else {
                return null;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Mint load(@NonNull MintConfiguration mintConfiguration, boolean archive, boolean lazy) {
        FSMintVault mintVault = new FSMintVault(mintConfiguration);
        String mintPath = mintVault.retrieve(mintConfiguration.getId(), archive);

        Mint mint = new Mint(mintConfiguration.getId());

        if (lazy) {
            return mint;
        }

        try (Stream<Path> paths = Files.list(Paths.get(mintPath))) {
            paths.filter(Files::isDirectory)
                    .forEach(unitPath -> { // Unit
                        String unit = unitPath.getFileName().toString();
                        KeysetConfiguration keysetConfiguration = new KeysetConfiguration(mintConfiguration, null, unit);
                        FSKeysetVault.load(keysetConfiguration, archive);
                        KeySet keySet = KeySet.builder().id(keysetConfiguration.getId()).unit(unit).build();

                        try (Stream<Path> unitPaths = Files.list(unitPath)) {
                            unitPaths
                                    .filter(Files::isDirectory)
                                    .forEach(keyPath -> { // Amount
                                        String amount = keyPath.getFileName().toString();
                                        try (Stream<Path> keyPaths = Files.list(Paths.get(keyPath.toString()))) {
                                            keyPaths
                                                    .filter(Files::isRegularFile)
                                                    .forEach(orivateKeyFile -> { // Private Key
                                                        String privateKey = orivateKeyFile.getFileName().toString();
                                                        KeyConfiguration keyConfiguration = new KeyConfiguration(keysetConfiguration, new BigInteger(amount), privateKey);
                                                        keySet.setKeys(FSKeyVault.load(keyConfiguration, archive));
                                                    });
                                        } catch (IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    });
                            mint.addKeySet(keySet);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return mint;
    }
}