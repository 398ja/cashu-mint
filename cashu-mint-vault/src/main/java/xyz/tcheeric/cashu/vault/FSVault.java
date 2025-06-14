package xyz.tcheeric.cashu.vault;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.config.EntityConfiguration;
import xyz.tcheeric.cashu.vault.config.MintConfiguration;
import xyz.tcheeric.common.util.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

public abstract class FSVault<T extends EntityConfiguration> implements Vault<T> {

    @Override
    public void delete() throws CashuErrorException {
        throw new IllegalStateException("Not implemented");
    }

    protected static String getBaseDir() {
        return getBaseDir(false);
    }

    public static String getBaseDir(boolean archive) {
        Configuration configuration = new Configuration("vault");
        return archive ? configuration.get("archive_dir") : configuration.get("base_dir");
    }

    protected static String getArchiveDir() {
        Configuration configuration = new Configuration("vault");
        return configuration.get("archive_dir");
    }

    protected static String mintArchivePath(@NonNull MintConfiguration mint) {
        var archiveDir = getArchiveDir();

        Path dirPath = Paths.get(archiveDir, "mint", mint.getId());
        return dirPath.toString();
    }

}
