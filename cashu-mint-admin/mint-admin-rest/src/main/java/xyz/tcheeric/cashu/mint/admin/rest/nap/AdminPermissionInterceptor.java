package xyz.tcheeric.cashu.mint.admin.rest.nap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminServiceException;
import xyz.tcheeric.nap.server.acl.PermissionRegistry;
import xyz.tcheeric.nap.spring.filter.NapPermissionInterceptor;

/**
 * Turns NAP's bare refusal statuses into the error body the rest of the admin API speaks.
 *
 * <p>NAP answers a denied permission with a body-less 401 or 403. Every other admin refusal
 * carries {@code {status, error, code, message}}, and a body-less status reads as a bug
 * rather than a decision, so this wraps NAP's interceptor and raises the same exception the
 * controllers do.
 */
public class AdminPermissionInterceptor implements HandlerInterceptor {

    private final NapPermissionInterceptor delegate;

    public AdminPermissionInterceptor(final PermissionRegistry registry) {
        this.delegate = new NapPermissionInterceptor(registry);
    }

    @Override
    public boolean preHandle(final HttpServletRequest request, final HttpServletResponse response,
                             final Object handler) throws Exception {
        if (delegate.preHandle(request, response, handler)) {
            return true;
        }
        if (response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
            throw new AdminServiceException(HttpStatus.UNAUTHORIZED, "unauthorized",
                "A valid admin session is required");
        }
        throw new AdminServiceException(HttpStatus.FORBIDDEN, "forbidden",
            "Your roles do not carry the permission this endpoint requires");
    }
}
