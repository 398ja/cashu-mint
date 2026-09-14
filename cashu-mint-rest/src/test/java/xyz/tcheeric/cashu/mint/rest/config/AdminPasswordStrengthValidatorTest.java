package xyz.tcheeric.cashu.mint.rest.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AppSec finding M-3 (issue #426): a plain-text admin password must not reach a non-local
 * profile.
 *
 * <p>`SecurityConfig` silently wraps an unprefixed value in `{noop}`, so it is compared as plain
 * text. That left the credential guarding `/admin/**` and `/v1/vouchers/**` readable wherever
 * configuration travels, with only a log warning against it — and a warning is not a decision.
 */
class AdminPasswordStrengthValidatorTest {

    /** A plain-text password must stop the context, not merely log a warning. */
    @Test
    void plainTextPasswordFailsStartup() {
        assertThatThrownBy(() -> new AdminPasswordStrengthValidator("hunter2").refusePlainTextPassword())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be plain text");
    }

    /** A bcrypt-prefixed password is the intended production form and must be accepted. */
    @Test
    void bcryptPrefixedPasswordIsAccepted() {
        assertThatCode(() -> new AdminPasswordStrengthValidator(
                "{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy")
                .refusePlainTextPassword())
                .doesNotThrowAnyException();
    }

    /**
     * Blank is allowed: SecurityConfig registers no admin user, so /admin/** returns 401 for
     * everything. A mint with its admin surface bolted shut is a safe, deliberate configuration,
     * and refusing to boot on it would force operators to invent a credential they do not want.
     */
    @Test
    void blankPasswordIsAllowedBecauseItDisablesAdminAccess() {
        assertThatCode(() -> new AdminPasswordStrengthValidator("").refusePlainTextPassword())
                .doesNotThrowAnyException();
        assertThatCode(() -> new AdminPasswordStrengthValidator(null).refusePlainTextPassword())
                .doesNotThrowAnyException();
    }

    /**
     * An explicit {noop} is allowed. The gate exists to convert an accident into a decision, not
     * to remove the choice: an operator who types the prefix has stated the intent.
     */
    @Test
    void explicitNoopPrefixIsAllowedAsADeliberateChoice() {
        assertThatCode(() -> new AdminPasswordStrengthValidator("{noop}hunter2").refusePlainTextPassword())
                .doesNotThrowAnyException();
    }

    /** The failure message must tell an operator what to do, not just what is wrong. */
    @Test
    void theFailureMessageExplainsHowToFixIt() {
        assertThatThrownBy(() -> new AdminPasswordStrengthValidator("hunter2").refusePlainTextPassword())
                .hasMessageContaining("spring encodepassword")
                .hasMessageContaining("{bcrypt}")
                .hasMessageContaining("{noop}");
    }

    /** Other encoder prefixes are accepted too; bcrypt is not special-cased. */
    @Test
    void anyEncoderPrefixIsAccepted() {
        for (String prefixed : new String[] {"{argon2}abc", "{pbkdf2}abc", "{sha256}abc"}) {
            assertThatCode(() -> new AdminPasswordStrengthValidator(prefixed).refusePlainTextPassword())
                    .as("%s should be accepted", prefixed)
                    .doesNotThrowAnyException();
        }
    }

    /** Guards against a prefix check that would accept a value merely containing a brace. */
    @Test
    void aBraceLaterInTheValueDoesNotCountAsAPrefix() {
        assertThatThrownBy(() -> new AdminPasswordStrengthValidator("hunter2{bcrypt}").refusePlainTextPassword())
                .isInstanceOf(IllegalStateException.class);
        assertThat("hunter2{bcrypt}".startsWith("{")).isFalse();
    }
}
