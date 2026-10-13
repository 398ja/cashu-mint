package xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import xyz.tcheeric.cashu.mint.tools.MintPreloadData;
import xyz.tcheeric.cashu.mint.tools.MintPreloadDataGenerator;

/**
 * The keyset the e2e stack preloads, generated for this run (cashu-mint#545).
 *
 * <p>This used to be a committed JSON file holding a fixed mint id and its private keys. Committing
 * a private key publishes it, and under the old derivation the mint id alone was enough to recompute
 * the keys. The fixture is now written to {@code target/} with a random mint id and CSPRNG keys, so
 * nothing about it is known outside this run. It lives only under {@code target/} (git-ignored) and
 * holds throwaway keys for a disposable stack.
 */
public final class PreloadFixture {

    /** The denominations the served mint's keyset covers, matching its registered configuration. */
    public static final List<Integer> DENOMINATIONS = List.of(1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024);

    private static final PreloadFixture INSTANCE = new PreloadFixture();

    private final MintPreloadData data;
    private final Path path;

    private PreloadFixture() {
        final UUID mintId = UUID.randomUUID();
        final MintPreloadDataGenerator generator = new MintPreloadDataGenerator(mintId, "sat", DENOMINATIONS);
        this.data = generator.data();
        try {
            final Path dir = Files.createDirectories(Path.of("target", "e2e-preload"));
            this.path = dir.resolve("preload-" + mintId + ".json");
            generator.writeJson(path);
        } catch (final IOException e) {
            throw new UncheckedIOException("could not write the e2e preload fixture", e);
        }
    }

    public static PreloadFixture shared() {
        return INSTANCE;
    }

    /** The served mint's id. Not a secret any more, but random per run. */
    public String mintId() {
        return data.mintId().toString();
    }

    public String keySetId() {
        return data.keySetId();
    }

    public Path path() {
        return path;
    }
}
