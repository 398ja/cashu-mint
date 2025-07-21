package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.model.KeySet;
import xyz.tcheeric.cashu.common.model.Keys;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.crypto.util.KeySetDerivation;
import xyz.tcheeric.cashu.vault.FSVault;
import xyz.tcheeric.cashu.vault.impl.fs.FSKeyVault;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;

@Slf4j
public class KeysetGeneratorTask implements Task<KeySet> {

    private final String unit;

    public KeysetGeneratorTask(@NonNull String unit) {
        this.unit = unit;
    }

    @Override
    public KeySet execute() throws CashuErrorException {
        log.info("execute()");

        Keys keys = getKeys();
        log.info("Keys: {}", keys);

        KeySet keySet = KeySet.builder().unit(unit).keys(keys).build();
        keySet.setId(KeySetDerivation.getId(keys.values()));
        return keySet;
    }

    private Keys getKeys() throws CashuErrorException {
        log.info("getKeys()");

        // <vault_basedir>/mint/<private_key>/<unit>/<key_index>/[private_key]
        var baseDir = FSVault.getBaseDir(false);
        log.info("Base directory: {}", baseDir);
        try {
            Optional<Path> mintPath = Files.list(Paths.get(baseDir, "mint"))
                    .filter(Files::isDirectory)
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            log.error("Failed to get last modified time: {}", p);
                            throw new UncheckedIOException(e);
                        }
                    }));

            if (mintPath.isPresent()) {
                log.debug("Most recent directory: {}", mintPath.get());
                var mintId = mintPath.get().getFileName().toString();
                return FSKeyVault.get(mintId, unit);
            } else {
                log.error("No directories found");
                throw new CashuErrorException("key_set_generator_mint_folder_missing_error");
            }
        } catch (IOException e) {
            log.error("Failed to list directories", e);
            throw new UncheckedIOException(e);
        }
    }
}
