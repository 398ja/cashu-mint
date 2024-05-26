package cashu.mint.actor.abilities;

import cashu.common.annotation.Nut;
import cashu.common.model.KeySet;
import cashu.common.model.Keys;
import cashu.common.protocol.Ability;
import cashu.crypto.KeySetDerivation;
import cashu.util.ThreadUtil;
import cashu.vault.FSVault;
import cashu.vault.impl.fs.FSKeyVault;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

@Log
@Nut(1)
@AllArgsConstructor
public class KeysetGenerator implements Ability<KeySet> {

    private final String unit;

    @Override
    public KeySet apply() {
        var task = new KeysetGeneratorTask(unit);
        try {
            ThreadUtil.builder().blocking(true).task(task).build().run();
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        }
        return task.getKeySet();
    }

    static class KeysetGeneratorTask implements ThreadUtil.Task<KeySet> {

        private final String unit;

        @Getter
        private KeySet keySet;

        public KeysetGeneratorTask(@NonNull String unit) {
            this.unit = unit;
        }

        @Override
        public KeySet execute() {
            log.log(Level.INFO, "execute()");
            Keys keys = getKeys();
            log.log(Level.INFO, "Keys: {0}", keys);
            keySet = KeySet.builder().unit(unit).keys(keys).build();
            KeySetDerivation keySetDerivation = new KeySetDerivation(keySet);
            keySetDerivation.deriveKeySetId();
            return keySet;
        }

        private Keys getKeys() {
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
                    log.log( Level.FINE, "Most recent directory: {0}", mintPath.get());
                    var privateKey = mintPath.get().getFileName().toString();
                    return FSKeyVault.get(privateKey, unit);
                } else {
                    log.log(Level.SEVERE, "No directories found");
                    throw new RuntimeException("No directories found");
                }
            } catch (IOException e) {
                log.log(Level.SEVERE, "Failed to list directories", e);
                throw new RuntimeException(e);
            }
        }
    }
}
