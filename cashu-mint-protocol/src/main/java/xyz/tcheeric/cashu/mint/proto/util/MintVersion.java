package xyz.tcheeric.cashu.mint.proto.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The NUT-06 {@code version} string, read from the Maven-filtered build
 * descriptor rather than typed into a configuration file.
 *
 * <p>Wallets apply implementation-specific workarounds keyed on this string, so
 * claiming to be an implementation or release we are not invites the wrong ones.
 * Sourcing it from the build makes that class of lie impossible: the value moves
 * with the artifact.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/06.md">NUT-06</a>
 */
@Slf4j
public final class MintVersion {

    private static final String BUILD_DESCRIPTOR = "mint-build.properties";
    private static final String IMPLEMENTATION_KEY = "mint.build.implementation";
    private static final String VERSION_KEY = "mint.build.version";
    private static final String UNKNOWN_VERSION = "unknown";
    private static final String FALLBACK_IMPLEMENTATION = "cashu-mint";
    private static final String SEPARATOR = "/";

    private static final String VERSION_STRING = readVersionString();

    private MintVersion() {
    }

    /**
     * Returns the advertised version, formatted {@code <implementation>/<version>}
     * (for example {@code cashu-mint/0.32.0}).
     *
     * @return the NUT-06 version string; never null
     */
    public static String current() {
        return VERSION_STRING;
    }

    private static String readVersionString() {
        Properties buildProperties = loadBuildDescriptor();
        String implementation = buildProperties.getProperty(IMPLEMENTATION_KEY, FALLBACK_IMPLEMENTATION);
        String version = buildProperties.getProperty(VERSION_KEY, UNKNOWN_VERSION);
        return implementation + SEPARATOR + version;
    }

    private static Properties loadBuildDescriptor() {
        Properties buildProperties = new Properties();
        try (InputStream descriptor = MintVersion.class.getClassLoader()
                .getResourceAsStream(BUILD_DESCRIPTOR)) {
            if (descriptor == null) {
                log.warn("{} missing from the classpath; advertising version {}",
                        BUILD_DESCRIPTOR, UNKNOWN_VERSION);
                return buildProperties;
            }
            buildProperties.load(descriptor);
        } catch (IOException e) {
            log.warn("Failed to read {}; advertising version {}", BUILD_DESCRIPTOR, UNKNOWN_VERSION, e);
        }
        return buildProperties;
    }
}
