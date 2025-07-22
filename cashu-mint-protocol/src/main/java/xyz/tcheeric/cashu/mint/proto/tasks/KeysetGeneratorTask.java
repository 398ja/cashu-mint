package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.crypto.util.KeySetDerivation;
import xyz.tcheeric.cashu.vault.api.config.KeyConfiguration;
import xyz.tcheeric.cashu.vault.api.config.KeysetConfiguration;
import xyz.tcheeric.cashu.vault.api.config.MintConfiguration;
import xyz.tcheeric.cashu.vault.api.db.impl.DBKeyVault;


@Slf4j
@RequiredArgsConstructor
public class KeysetGeneratorTask implements Task<KeySet> {

    private final String mintId;
    private final String unit;

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

        MintConfiguration mintConfiguration = new MintConfiguration(mintId);
        KeysetConfiguration keysetConfiguration = new KeysetConfiguration(mintConfiguration, unit);
        KeyConfiguration keyConfiguration = new KeyConfiguration(keysetConfiguration);
        return DBKeyVault.load(keyConfiguration, false);

/*
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
*/
    }
}
