package xyz.tcheeric.cashu.mint.proto.util;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The mint's own identity as declared by the deployment that runs it.
 *
 * <p>Every field is unset unless an operator supplies it, and the packaged
 * defaults resolve to empty. That is deliberate: a shipped default would be
 * identical for every deployment of this codebase, which is how {@code /v1/info}
 * came to advertise a mint named "Bob's Cashu mint" at {@code https://mint.host}.
 * An unset field is omitted from the response rather than filled with a lie.
 *
 * <p>Version and time are not configurable: the version comes from the build
 * ({@link MintVersion}) and the time from the mint's clock.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/06.md">NUT-06</a>
 */
@Component
@ConfigurationProperties(prefix = "mint.identity")
@Getter
@Setter
public class MintIdentityProperties {

    private static final String CONTACT_ENTRY_SEPARATOR = ":";
    private static final int CONTACT_METHOD_AND_INFO = 2;

    private String name;

    /**
     * The mint's NUT-20 signing public key. Left unset until a deployment has
     * one to publish; a fabricated key is worse than an absent field.
     */
    private String pubkey;

    private String description;

    private String descriptionLong;

    private String motd;

    private String iconUrl;

    private String tosUrl;

    private List<String> urls = new ArrayList<>();

    /**
     * Contacts as {@code method:info} pairs, so a deployment can supply them
     * through a single environment variable instead of indexed list properties.
     */
    private List<String> contacts = new ArrayList<>();

    /**
     * Parses the configured {@code method:info} pairs into NUT-06 contact
     * entries, skipping any entry that does not carry both halves.
     *
     * @return the contacts to advertise; empty when none are configured
     */
    public List<MintInfo.Contact> toContacts() {
        List<MintInfo.Contact> parsed = new ArrayList<>();
        for (String entry : contacts) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] parts = entry.split(CONTACT_ENTRY_SEPARATOR, CONTACT_METHOD_AND_INFO);
            if (parts.length < CONTACT_METHOD_AND_INFO || parts[0].isBlank() || parts[1].isBlank()) {
                continue;
            }
            parsed.add(new MintInfo.Contact(parts[0].trim(), parts[1].trim()));
        }
        return parsed;
    }

    /**
     * Returns the advertised mint URLs, dropping blanks left by unset
     * environment variables.
     *
     * @return the configured URLs; empty when none are configured
     */
    public List<String> toUrls() {
        return urls.stream()
                .filter(url -> url != null && !url.isBlank())
                .map(String::trim)
                .toList();
    }
}
