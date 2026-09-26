package xyz.tcheeric.cashu.mint.rest.config;

import nostr.id.Identity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import xyz.tcheeric.cashu.voucher.nostr.NostrClientAdapter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The {@code voucher.nostr} block in {@code application-voucher.yml} must reach the Nostr
 * components it describes (cashu-mint#407).
 *
 * <p>The publish and query timeouts used to be bound and then dropped: the repositories were
 * built with constructors that hardcode 5000ms for both. The relay list was a YAML list with no
 * placeholder, which an environment variable can only add to, never replace, so a deployment
 * could not point the mint at its own relay. These tests bind the shipped file the way Spring
 * Boot does, with the environment variables a deployment would set, and follow each value to the
 * component that uses it.
 */
@DisplayName("voucher.nostr configuration reaches the Nostr components (#407)")
class VoucherNostrConfigurationTest {

    private static final String PRIVKEY =
            "5c0c523f52a5b6fad39ed2403092df8cebc36318b39383bca6c00808626fab3a";

    /** Binds the shipped application-voucher.yml under the given environment variables. */
    private static VoucherProperties bindShippedYaml(Map<String, Object> environment) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.addFirst(new SystemEnvironmentPropertySource("test-environment", environment));
        for (PropertySource<?> yaml : new YamlPropertySourceLoader()
                .load("application-voucher.yml", new ClassPathResource("application-voucher.yml"))) {
            sources.addLast(yaml);
        }
        ConfigurationPropertySources.attach(env);
        return Binder.get(env).bind("voucher", VoucherProperties.class).get();
    }

    private static VoucherProperties withIssuerKeys(VoucherProperties properties) {
        properties.getMint().setIssuerPrivateKey(PRIVKEY);
        properties.getMint().setIssuerPublicKey(
                Identity.create(new nostr.base.PrivateKey(PRIVKEY)).getPublicKey().toString());
        return properties;
    }

    private static long longField(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(target);
    }

    /** The relays the client adapter will actually connect to. */
    @SuppressWarnings("unchecked")
    private static List<String> relaysOf(NostrClientAdapter adapter) throws ReflectiveOperationException {
        Field field = NostrClientAdapter.class.getDeclaredField("relayUrls");
        field.setAccessible(true);
        return (List<String>) field.get(adapter);
    }

    private static NostrClientAdapter adapterFor(VoucherProperties properties) {
        VoucherConfiguration configuration = new VoucherConfiguration(properties);
        return configuration.nostrClientAdapter(configuration.nostrRelayConfig());
    }

    // With nothing set, the shipped file yields the documented defaults, including both public
    // relays, so an existing deployment behaves as before.
    @Test
    void theShippedDefaultsAreUnchanged() throws IOException {
        VoucherProperties.Nostr nostr = bindShippedYaml(Map.of()).getNostr();

        assertThat(nostr.getRelays()).containsExactly("wss://relay.damus.io", "wss://relay.cashu.xyz");
        assertThat(nostr.getPublishTimeoutMs()).isEqualTo(5000L);
        assertThat(nostr.getQueryTimeoutMs()).isEqualTo(10000L);
    }

    // The headline of #407: one environment variable replaces the whole relay list, so a private
    // or test deployment connects to its own relay and to no public one. Checked on the client
    // adapter itself, because NostrRelayConfig's builder silently drops any relay list it is given.
    @Test
    void oneEnvironmentVariableReplacesTheWholeRelayList() throws Exception {
        NostrClientAdapter adapter = adapterFor(bindShippedYaml(
                Map.of("MINT_VOUCHER_NOSTR_RELAYS", "ws://relay.internal:7000")));

        assertThat(relaysOf(adapter)).containsExactly("ws://relay.internal:7000");
    }

    // Several relays can be given in the same variable, comma-separated, and all of them reach the
    // adapter, with stray spaces around the commas removed.
    @Test
    void theRelayVariableTakesACommaSeparatedList() throws Exception {
        NostrClientAdapter adapter = adapterFor(bindShippedYaml(
                Map.of("MINT_VOUCHER_NOSTR_RELAYS", "wss://a.example, wss://b.example")));

        assertThat(relaysOf(adapter)).containsExactly("wss://a.example", "wss://b.example");
    }

    // With nothing set the adapter still gets the two shipped public relays.
    @Test
    void theAdapterGetsTheShippedRelaysByDefault() throws Exception {
        assertThat(relaysOf(adapterFor(bindShippedYaml(Map.of()))))
                .containsExactly("wss://relay.damus.io", "wss://relay.cashu.xyz");
    }

    // A relay that is not a ws:// or wss:// URL is refused at startup rather than failing on the
    // first voucher, which is when an unreachable relay would otherwise surface.
    @Test
    void aRelayThatIsNotAWebSocketUrlIsRefusedAtStartup() throws IOException {
        VoucherProperties properties = bindShippedYaml(
                Map.of("MINT_VOUCHER_NOSTR_RELAYS", "https://relay.example"));

        assertThatThrownBy(() -> adapterFor(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ws:// or wss://");
    }

    // Fewer relays than the required minimum is refused at startup. The library enforced this only
    // against its own default list, so the mint checks the list it actually uses.
    @Test
    void fewerRelaysThanTheMinimumAreRefusedAtStartup() throws IOException {
        VoucherProperties properties = bindShippedYaml(Map.of("MINT_VOUCHER_NOSTR_RELAYS", "wss://only.example"));
        properties.getNostr().setMinimumRelays(2);

        assertThatThrownBy(() -> adapterFor(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 2");
    }

    // The configured publish and query timeouts reach the ledger repository instead of being
    // replaced by the 5000ms its shorter constructors hardcode.
    @Test
    void theLedgerUsesTheConfiguredTimeouts() throws Exception {
        VoucherConfiguration configuration = new VoucherConfiguration(withIssuerKeys(bindShippedYaml(Map.of(
                "MINT_VOUCHER_NOSTR_PUBLISH_TIMEOUT_MS", "7777",
                "MINT_VOUCHER_NOSTR_QUERY_TIMEOUT_MS", "8888"))));

        Object ledger = configuration.voucherLedgerPort(
                mock(NostrClientAdapter.class), configuration.nostrRelayConfig());

        assertThat(longField(ledger, "publishTimeoutMs")).isEqualTo(7777L);
        assertThat(longField(ledger, "queryTimeoutMs")).isEqualTo(8888L);
    }

    // The backup repository had the same defect and gets the same timeouts.
    @Test
    void theBackupRepositoryUsesTheConfiguredTimeouts() throws Exception {
        VoucherConfiguration configuration = new VoucherConfiguration(bindShippedYaml(Map.of(
                "MINT_VOUCHER_NOSTR_PUBLISH_TIMEOUT_MS", "7777",
                "MINT_VOUCHER_NOSTR_QUERY_TIMEOUT_MS", "8888")));

        Object backup = configuration.voucherBackupPort(
                mock(NostrClientAdapter.class), configuration.nostrRelayConfig());

        assertThat(longField(backup, "publishTimeoutMs")).isEqualTo(7777L);
        assertThat(longField(backup, "queryTimeoutMs")).isEqualTo(8888L);
    }

    // The shipped query timeout is 10000ms; before #407 the log showed 5000ms, a number no
    // configured source produced. The default path must carry the file's value too.
    @Test
    void theShippedQueryTimeoutIsTheOneUsed() throws Exception {
        VoucherConfiguration configuration = new VoucherConfiguration(withIssuerKeys(bindShippedYaml(Map.of())));

        Object ledger = configuration.voucherLedgerPort(
                mock(NostrClientAdapter.class), configuration.nostrRelayConfig());

        assertThat(longField(ledger, "queryTimeoutMs")).isEqualTo(10000L);
    }
}
