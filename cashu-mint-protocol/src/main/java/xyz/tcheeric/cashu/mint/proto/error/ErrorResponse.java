package xyz.tcheeric.cashu.mint.proto.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The mint's own error payload, carrying a machine-readable code and a human-readable message.
 *
 * <p>This type used to be declared in {@code xyz.tcheeric.cashu.entities.rest}, the package
 * cashu-lib also ships an {@code ErrorResponse} in. The local source shadowed the library class, so
 * the clash was invisible to the compiler right up until the local copy was removed. Moving the
 * class into a mint package makes the two types distinguishable and ends the split package.
 *
 * <p>The mint still speaks its own string codes ({@code melt_in_progress}, {@code proofs_not_bound}
 * and the like), most of which have no NUT-00 numeric equivalent. Migrating them onto
 * {@code CashuErrorCode} changes the wire format visible to every client, so it is tracked
 * separately in cashu-mint#396 rather than folded into the secret-encoding migration.
 */
public record ErrorResponse(String code, String message) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Properties MESSAGES = new Properties();

    static {
        try (InputStream is = ErrorResponse.class.getClassLoader().getResourceAsStream("messages.properties")) {
            if (is != null) {
                MESSAGES.load(is);
            }
        } catch (IOException ignored) {
        }
    }

    public ErrorResponse(String code) {
        this(code, MESSAGES.getProperty(code, code));
    }

    /**
     * Serialises the payload through Jackson rather than string concatenation. A message
     * containing a quote or a backslash produced invalid JSON when this was built with
     * {@code String.format}, which turned a reportable error into an unparseable body.
     */
    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("error response could not be serialised: " + code, e);
        }
    }
}

