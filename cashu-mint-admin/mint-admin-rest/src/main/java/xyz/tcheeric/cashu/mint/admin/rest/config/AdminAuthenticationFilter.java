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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Minimal token-based authentication protecting the administrative API surface.
 */
public class AdminAuthenticationFilter extends OncePerRequestFilter {

    public static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final AdminSecurityProperties properties;

    public AdminAuthenticationFilter(final AdminSecurityProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
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

        final String providedToken = request.getHeader(ADMIN_TOKEN_HEADER);
        if (!StringUtils.hasText(providedToken) || !MessageDigestComparator.equals(properties.apiToken(), providedToken)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Token realm=admin");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getOutputStream().write(
                ("{\"status\":401,\"error\":\"unauthorized\",\"code\":\"unauthorized\","
                    + "\"message\":\"Valid X-Admin-Token header required\"}")
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }

        filterChain.doFilter(request, response);
    }
}
