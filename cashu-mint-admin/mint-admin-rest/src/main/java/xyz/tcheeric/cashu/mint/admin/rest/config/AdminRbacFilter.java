package xyz.tcheeric.cashu.mint.admin.rest.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces basic role based access control for administrative endpoints.
 */
public class AdminRbacFilter extends OncePerRequestFilter {

    public static final String ADMIN_ROLES_HEADER = "X-Admin-Roles";

    private static final Map<String, String> REQUIRED_ROLE_BY_PATTERN = Map.of(
            "/admin/lifecycle/**", "MINT_ADMIN",
            "/admin/users/**", "USER_ADMIN",
            "/admin/operations/**", "OPS_ADMIN"
    );

    private static final List<String> NO_ROLE_REQUIRED_PATTERNS = List.of(
            "/admin/auth/**",
            "/admin/audit/**",
            "/admin/dashboard/**"
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        final String uri = request.getRequestURI();
        if (!uri.startsWith("/admin") || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isNoRoleRequired(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        final String requiredRole = resolveRequiredRole(uri);
        if (requiredRole == null) {
            filterChain.doFilter(request, response);
            return;
        }

        final Set<String> callerRoles = extractRoles(request.getHeader(ADMIN_ROLES_HEADER));
        if (callerRoles.contains(requiredRole)) {
            filterChain.doFilter(request, response);
            return;
        }

        final String payload = "{" +
                "\"status\":" + HttpStatus.FORBIDDEN.value() + ',' +
                "\"error\":\"Forbidden\"," +
                "\"code\":\"forbidden\"," +
                "\"message\":\"Missing required role: " + requiredRole + "\"}";
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isNoRoleRequired(final String uri) {
        for (final String pattern : NO_ROLE_REQUIRED_PATTERNS) {
            if (pathMatcher.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    private String resolveRequiredRole(final String uri) {
        for (Map.Entry<String, String> entry : REQUIRED_ROLE_BY_PATTERN.entrySet()) {
            if (pathMatcher.match(entry.getKey(), uri)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Set<String> extractRoles(final String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return Set.of();
        }
        return Arrays.stream(headerValue.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
