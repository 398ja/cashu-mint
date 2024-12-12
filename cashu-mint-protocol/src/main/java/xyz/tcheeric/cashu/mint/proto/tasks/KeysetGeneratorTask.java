package xyz.tcheeric.cashu.mint.proto.tasks;

import xyz.tcheeric.cashu.common.model.KeySet;
import xyz.tcheeric.cashu.common.model.Keys;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.crypto.KeySetDerivation;
import xyz.tcheeric.cashu.vault.FSVault;
import xyz.tcheeric.cashu.vault.impl.fs.FSKeyVault;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;
import java.util.logging.Level;

@Log
public class KeysetGeneratorTask implements Task<KeySet> {

    private final String unit;

    public KeysetGeneratorTask(@NonNull String unit) {
        this.unit = unit;
    }

    @Override
    public KeySet execute() throws CashuErrorException {
        log.log(Level.INFO, "execute()");

        Keys keys = getKeys();
        log.log(Level.INFO, "Keys: {0}", keys);

        KeySet keySet = KeySet.builder().unit(unit).keys(keys).build();
        KeySetDerivation keySetDerivation = new KeySetDerivation(keySet);
        keySetDerivation.deriveKeySetId();
        return keySet;
    }

    private Keys getKeys() throws CashuErrorException {
        log.log(Level.INFO, "getKeys()");

        // <vault_basedir>/mint/<private_key>/<unit>/<key_index>/[private_key]
        var baseDir = FSVault.getBaseDir(false);
        log.log(Level.INFO, "Base directory: {0}", baseDir);
        try {
            Optional<Path> mintPath = Files.list(Paths.get(baseDir, "mint"))
                    .filter(Files::isDirectory)
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            log.log(Level.SEVERE, "Failed to get last modified time: {0}", p);
                            throw new UncheckedIOException(e);
                        }
                    }));

            if (mintPath.isPresent()) {
                log.log(Level.FINE, "Most recent directory: {0}", mintPath.get());
                var mintId = mintPath.get().getFileName().toString();
                return FSKeyVault.get(mintId, unit);
            } else {
                log.log(Level.SEVERE, "No directories found");
                throw new CashuErrorException("key_set_generator_mint_folder_missing_error");
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "Failed to list directories", e);
            throw new UncheckedIOException(e);
        }
    }
}
