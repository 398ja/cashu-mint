package cashu.vault.impl.fs;

import cashu.common.model.KeySet;
import cashu.common.model.Keys;
import cashu.common.model.PrivateKey;
import cashu.common.protocol.CashuErrorException;
import cashu.vault.FSVault;
import cashu.vault.config.KeyConfiguration;
import cashu.vault.config.KeysetConfiguration;
import cashu.vault.config.MintConfiguration;
import lombok.AllArgsConstructor;
import lombok.NonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

@AllArgsConstructor
public class FSKeysetVault extends FSVault<KeysetConfiguration> {

    @NonNull
    private final KeysetConfiguration keysetConfiguration;

    @Override
    public void store() throws CashuErrorException {
        var mint = keysetConfiguration.getMint();
        var id = keysetConfiguration.getId();
        var unit = keysetConfiguration.getUnit();

        // <baseDir>/mint/<privateKey>/<unit>/[.keyset_id]
        String mintPath = mintPath(mint);
        Path filePath = Paths.get(mintPath, unit, "." + id);

        try {
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new CashuErrorException(e);
        }
    }

    @Override
    public String retrieve(@NonNull String keysetId, boolean archive) {
        var mintPath = mintPath(keysetConfiguration.getMint(), archive);
        var unit = keysetConfiguration.getUnit();

        if (unit != null) {
            return Paths.get(mintPath, unit, "." + keysetId).toString();
        } else {
            Path parentPath = Paths.get(mintPath);
            try (Stream<Path> paths = Files.list(parentPath)) {
                Optional<String> keysetIdPath = paths.filter(Files::isDirectory)
                        .flatMap(directory -> {
                            try {
                                return Files.list(directory);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        })
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equals("." + keysetId))
                        .map(Path::toString)
                        .findFirst();
                return keysetIdPath.orElse(null);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public PrivateKey getPrivateKey(@NonNull Integer amount) {
        FSMintVault mintVault = new FSMintVault(keysetConfiguration.getMint());
        String mintPath = mintVault.retrieve(keysetConfiguration.getMint().getId(), false);
        Path dirPath = Paths.get(mintPath, keysetConfiguration.getUnit(), amount.toString());

        try (Stream<Path> paths = Files.list(dirPath)) {
            Optional<Path> keyFilePath = paths
                    .min(Comparator.comparingLong(p -> p.toFile().lastModified()));
            if (keyFilePath.isPresent()) {
                String privateKeyStr = keyFilePath.get().getFileName().toString();
                return PrivateKey.fromString(privateKeyStr);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return null;
    }


    public static KeySet load(@NonNull KeysetConfiguration keysetConfiguration, boolean archive) {
        loadKeysetId(keysetConfiguration, archive);
        KeyConfiguration keyConfiguration = new KeyConfiguration(keysetConfiguration);
        Keys keys = FSKeyVault.load(keyConfiguration, archive);
        return KeySet.builder().id(keysetConfiguration.getId()).keys(keys).build();
    }

    static void loadKeysetId(@NonNull KeysetConfiguration keysetConfiguration) {
        loadKeysetId(keysetConfiguration, false);
    }

    static void loadKeysetId(@NonNull KeysetConfiguration keysetConfiguration, boolean archive) {
        String mintPath = mintPath(keysetConfiguration.getMint(), archive);
        String unit = keysetConfiguration.getUnit();
        Path unitPath = Paths.get(mintPath, unit);

        try (Stream<Path> paths = Files.list(unitPath)) {
            Optional<Path> keysetIdPath = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("."))
                    .findFirst();

            if (keysetIdPath.isPresent()) {
                var result = keysetIdPath.get().getFileName().toString().substring(1); // remove the leading dot
                keysetConfiguration.setId(result);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void archive(@NonNull String key) throws CashuErrorException {
        var keysetPath = retrieve(key, false);
        Path sourcePath = Paths.get(keysetPath);

        String mintArchivePath = mintArchivePath(keysetConfiguration.getMint());
        String unit = keysetConfiguration.getUnit();

        Path keysetArchivePath = Paths.get(mintArchivePath, unit, "." + key);

        try {
            Files.createDirectories(keysetArchivePath.getParent());
            Files.move(sourcePath, keysetArchivePath);
        } catch (IOException e) {
            throw new CashuErrorException(e);
        }
    }

    private static String mintPath(@NonNull MintConfiguration mint) {
        return mintPath(mint, false);
    }

    private static String mintPath(@NonNull MintConfiguration mint, boolean archive) {
        var baseDir = getBaseDir(archive);

        Path dirPath = Paths.get(baseDir, "mint", mint.getId());
        return dirPath.toString();
    }
}
