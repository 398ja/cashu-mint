package xyz.tcheeric.cashu.mint.admin.rest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import xyz.tcheeric.nap.spring.config.NapProperties;

/**
 * OpenAPI configuration for the administrative API surface.
 */
@Configuration
public class AdminOpenApiConfiguration {

    /** Name of the session-cookie security scheme every admin endpoint requires. */
    public static final String ADMIN_SESSION_SCHEME = "AdminSession";

    @Bean
    public OpenAPI adminOpenApi(final NapProperties napProperties) {
        final Components components = new Components()
                .addSecuritySchemes(ADMIN_SESSION_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .description("Session cookie issued by the NAP handshake at /api/v1/auth/complete")
                        .name(napProperties.cookie().name())
                        .in(SecurityScheme.In.COOKIE));
        final SecurityRequirement requirement = new SecurityRequirement()
                .addList(ADMIN_SESSION_SCHEME);
        return new OpenAPI()
                .components(components)
                .info(new Info()
                        .title("Cashu Mint Admin API")
                        .description("Administrative endpoints for operating the mint")
                        .version("v1"))
                .addSecurityItem(requirement);
    }
}
