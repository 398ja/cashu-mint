package xyz.tcheeric.cashu.mint.admin.rest.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository.OperatorAccessAccount;
import xyz.tcheeric.cashu.mint.admin.application.service.OperatorCredentials;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Authenticates an operator from their own credential and publishes the result
 * for {@link AdminRbacFilter} to authorise against.
 *
 * <p>The presented credential is hashed and matched against the operator store;
 * roles come from the resolved operator. No request header influences identity
 * or authorisation — see ADR-0005.
 *
 * <p>{@code admin.security.api-token} remains as a bootstrap credential so a
 * fresh deployment can create its first operator. It resolves to a fixed
 * {@code bootstrap} identity holding every role, and should be unset once real
 * operators exist — it is shared, so anything it does is unattributable.
 */
public class AdminAuthenticationFilter extends OncePerRequestFilter {

    public static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    /**
     * Fixed identity of the bootstrap credential. A real UUID because the domain
     * validates the audit actor as one, and nil so it is unmistakable in the
     * Audit Trail as the shared, unattributable identity.
     */
    public static final String BOOTSTRAP_OPERATOR_ID = "00000000-0000-0000-0000-000000000000";
    private static final Set<String> BOOTSTRAP_ROLES = Set.of("MINT_ADMIN", "USER_ADMIN", "OPS_ADMIN");

    private final AdminSecurityProperties properties;
    private final OperatorAccessRepository operatorAccessRepository;

    public AdminAuthenticationFilter(final AdminSecurityProperties properties,
                                     final OperatorAccessRepository operatorAccessRepository) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.operatorAccessRepository = Objects.requireNonNull(operatorAccessRepository,
            "operator access repository");
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/admin")) {
            filterChain.doFilter(request, response);
            return;
        }

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        final String presented = request.getHeader(ADMIN_TOKEN_HEADER);
        if (!StringUtils.hasText(presented)) {
            unauthorized(response);
            return;
        }

        final Optional<AuthenticatedOperator> operator = resolve(presented);
        if (operator.isEmpty()) {
            unauthorized(response);
            return;
        }

        request.setAttribute(AuthenticatedOperator.ATTRIBUTE, operator.get());
        filterChain.doFilter(request, response);
    }

    private Optional<AuthenticatedOperator> resolve(final String presented) {
        if (MessageDigestComparator.equals(properties.apiToken(), presented)) {
            // Deliberately checked only on the bootstrap path, so normal requests
            // never pay for the scan.
            if (!operatorAccessRepository.findAll().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedOperator(
                BOOTSTRAP_OPERATOR_ID, "Bootstrap operator", BOOTSTRAP_ROLES));
        }

        return operatorAccessRepository.findByCredentialHash(OperatorCredentials.hash(presented))
            .filter(OperatorAccessAccount::active)
            .map(account -> new AuthenticatedOperator(
                account.accountId(), account.displayName(), account.roles()));
    }

    private void unauthorized(final HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Token realm=admin");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(
            ("{\"status\":401,\"error\":\"unauthorized\",\"code\":\"unauthorized\","
                + "\"message\":\"Valid X-Admin-Token header required\"}")
                .getBytes(StandardCharsets.UTF_8));
    }
}
