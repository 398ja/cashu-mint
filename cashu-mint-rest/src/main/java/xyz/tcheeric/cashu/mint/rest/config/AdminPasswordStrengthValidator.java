package xyz.tcheeric.cashu.mint.rest.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Boot-time gate refusing a plain-text admin password in any non-{@code local} profile
 * (AppSec finding M-3, issue #426).
 *
 * <p>{@code SecurityConfig} wraps a password with no encoder prefix in {@code {noop}}, which
 * means Spring compares it as plain text. That is convenient and it is also how the credential
 * guarding {@code /admin/**} — and, today, {@code /v1/vouchers/**} — ends up sitting in
 * configuration, environment dumps and process listings in the clear. A warning was logged, but
 * a warning is not a decision: nothing stopped a deployment reaching production that way.
 *
 * <p>This class follows the pattern the mint already uses for credentials that must not be
 * absent or weak in production — see {@code WebhookSecretStartupValidator} and
 * {@code ManagementPortGuard}. Fail-loud beats fail-quiet for anything that guards an operator
 * surface.
 *
 * <h2>What is and is not refused</h2>
 *
 * <ul>
 *   <li><b>Blank</b> is allowed. {@code SecurityConfig} registers zero users in that case, so
 *       every {@code /admin/**} request returns 401 and there is no guessable credential. A mint
 *       with its admin surface bolted shut is a deliberate and safe configuration.</li>
 *   <li><b>Plain text</b> is refused. It is the one setting that looks like it works while
 *       leaving the credential readable wherever configuration travels.</li>
 *   <li><b>An explicit encoder prefix</b> ({@code {bcrypt}}, {@code {argon2}}, {@code {pbkdf2}},
 *       …) is allowed, which is the intended production form.</li>
 *   <li><b>{@code {noop}} written explicitly</b> is allowed, on purpose. An operator who types
 *       the prefix has stated the intent rather than fallen into it, and there are legitimate
 *       uses — a throwaway staging box, or a credential already fronted by something else. The
 *       point of this gate is to convert an accident into a decision, not to remove the choice.</li>
 * </ul>
 *
 * <p>The {@code local} profile is exempt so developers can iterate without generating a hash.
 */
@Slf4j
@Component
@Profile("!local")
public class AdminPasswordStrengthValidator {

    /**
     * Spring Security's {@code DelegatingPasswordEncoder} reads an encoder id from a
     * {@code {prefix}} at the start of the stored value; anything else is treated as plain text
     * by {@code SecurityConfig}, which prepends {@code {noop}}.
     */
    private static final String ENCODER_PREFIX_START = "{";
    private static final char ENCODER_PREFIX_END = '}';

    private final String password;

    public AdminPasswordStrengthValidator(
            @Value("${cashu.mint.admin.password:}") String password) {
        this.password = password;
    }

    @PostConstruct
    void refusePlainTextPassword() {
        if (password == null || password.isBlank()) {
            // Fail-closed already: SecurityConfig registers no admin user, so /admin/** rejects
            // every request. Nothing to enforce here, but say so, because an operator reading
            // the logs should not have to infer that admin access is switched off.
            log.warn("Admin auth is disabled: cashu.mint.admin.password is unset, so /admin/** "
                    + "will reject every request with 401. Set MINT_ADMIN_PASSWORD to a hashed "
                    + "value to enable operator access.");
            return;
        }

        if (!password.startsWith(ENCODER_PREFIX_START)) {
            throw new IllegalStateException(
                    "cashu.mint.admin.password must not be plain text in a non-local profile "
                            + "(AppSec finding M-3). As written it is stored and compared in the "
                            + "clear, so the credential guarding /admin/** and /v1/vouchers/** is "
                            + "readable in configuration, environment dumps and process listings. "
                            + "Generate a hash with `spring encodepassword <password>` and set the "
                            + "result including its {bcrypt} prefix. To run without admin access, "
                            + "leave the property unset. To keep plain text deliberately — a "
                            + "throwaway environment, or a credential fronted by something else — "
                            + "write the {noop} prefix explicitly.");
        }

        int prefixEnd = password.indexOf(ENCODER_PREFIX_END);
        if (prefixEnd <= 1) {
            // "{" alone, "{}" or "{unclosed". Spring's DelegatingPasswordEncoder would accept
            // startup and then throw IllegalArgumentException on the first login attempt, which
            // surfaces as a confusing 500 long after the mistake was made. Failing here reports it
            // while the operator is still looking at the configuration.
            throw new IllegalStateException(
                    "cashu.mint.admin.password starts with '{' but has no usable encoder id. "
                            + "The format is {ENCODER}password, for example the output of "
                            + "`spring encodepassword <password>`. As written, every admin login "
                            + "would fail at authentication time rather than here.");
        }

        log.info("Admin password uses encoder prefix {}",
                password.substring(0, prefixEnd + 1));
    }
}
