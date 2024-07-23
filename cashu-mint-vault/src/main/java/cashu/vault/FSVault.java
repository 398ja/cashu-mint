package cashu.vault;

import cashu.common.protocol.CashuErrorException;
import cashu.util.Configuration;
import cashu.vault.config.EntityConfiguration;
import cashu.vault.config.MintConfiguration;
import lombok.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

public abstract class FSVault<T extends EntityConfiguration> implements Vault<T> {

    @Override
    public void delete() throws CashuErrorException {
        throw new IllegalStateException("Not implemented");
    }

    protected static String getBaseDir() {
        return getBaseDir(false);
    }

    public static String getBaseDir(boolean archive) {
        try (InputStream inputStream = FSVault.class.getResourceAsStream("/vault.properties")) {
            Configuration configuration = Configuration.load(Objects.requireNonNull(inputStream));
            return archive ? configuration.getValue("archive_dir") : configuration.getValue("base_dir");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load vault properties", e);
        }
    }

    protected static String getArchiveDir() {
        try (InputStream inputStream = FSVault.class.getResourceAsStream("/vault.properties")) {
            Configuration configuration = Configuration.load(Objects.requireNonNull(inputStream));
            return configuration.getValue("archive_dir");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load vault properties", e);
        }
    }

    protected static String mintArchivePath(@NonNull MintConfiguration mint) {
        var archiveDir = getArchiveDir();

        Path dirPath = Paths.get(archiveDir, "mint", mint.getId());
        return dirPath.toString();
    }

}
