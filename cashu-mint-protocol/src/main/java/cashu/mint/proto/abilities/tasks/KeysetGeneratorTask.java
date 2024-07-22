package cashu.mint.proto.abilities.tasks;

import cashu.common.model.KeySet;
import cashu.common.model.Keys;
import cashu.common.protocol.BaseAbility;
import cashu.common.protocol.CashuErrorException;
import cashu.crypto.KeySetDerivation;
import cashu.vault.FSVault;
import cashu.vault.impl.fs.FSKeyVault;
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
public class KeysetGeneratorTask implements BaseAbility.Task<KeySet> {

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
