package xyz.tcheeric.cashu.mint.admin.rest.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import xyz.tcheeric.cashu.mint.admin.framework.CorrelationIdContext;

import java.io.IOException;

/**
 * Servlet filter that ensures every REST call is associated with a correlation identifier.
 */
public class AdminCorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String CORRELATION_ID_ATTRIBUTE = AdminCorrelationIdFilter.class.getName() + ".CORRELATION_ID";

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        final String suppliedId = request.getHeader(CORRELATION_ID_HEADER);
        try (CorrelationIdContext.Scope scope =
                 CorrelationIdContext.open(StringUtils.hasText(suppliedId) ? suppliedId : null)) {
            final String correlationId = scope.correlationId();
            request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
            response.setHeader(CORRELATION_ID_HEADER, correlationId);
            filterChain.doFilter(request, response);
        }
    }
}
