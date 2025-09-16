package xyz.tcheeric.cashu.mint.rest.admin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * OpenAPI configuration for the administrative API surface.
 */
@Configuration
public class AdminOpenApiConfiguration {

    private static final String ADMIN_TOKEN_SCHEME = "AdminToken";
    private static final String ADMIN_ROLES_SCHEME = "AdminRoles";

    @Bean
    public OpenAPI adminOpenApi() {
        final Components components = new Components()
                .addSecuritySchemes(ADMIN_TOKEN_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .name(AdminAuthenticationFilter.ADMIN_TOKEN_HEADER)
                        .in(SecurityScheme.In.HEADER))
                .addSecuritySchemes(ADMIN_ROLES_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .name(AdminRbacFilter.ADMIN_ROLES_HEADER)
                        .in(SecurityScheme.In.HEADER));

        final SecurityRequirement securityRequirement = new SecurityRequirement()
                .addList(ADMIN_TOKEN_SCHEME)
                .addList(ADMIN_ROLES_SCHEME);

        return new OpenAPI()
                .components(components)
                .info(new Info()
                        .title("Cashu Mint Admin API")
                        .description("Administrative endpoints mirroring CLI workflows")
                        .version("v1"))
                .addSecurityItem(securityRequirement);
    }
}
