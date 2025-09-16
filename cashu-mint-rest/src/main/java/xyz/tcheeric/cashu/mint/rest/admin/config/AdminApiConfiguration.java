package xyz.tcheeric.cashu.mint.rest.admin.config;

import com.fasterxml.jackson.databind.DeserializationFeature;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Spring configuration wiring authentication and JSON customisations for admin endpoints.
 */
@Configuration
@EnableConfigurationProperties(AdminSecurityProperties.class)
public class AdminApiConfiguration {

    @Bean
    public AdminAuthenticationFilter adminAuthenticationFilter(final AdminSecurityProperties properties) {
        return new AdminAuthenticationFilter(properties);
    }

    @Bean
    public FilterRegistrationBean<AdminAuthenticationFilter> adminAuthenticationFilterRegistration(
            final AdminAuthenticationFilter filter) {
        final FilterRegistrationBean<AdminAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/admin/*");
        return registration;
    }

    @Bean
    public AdminRbacFilter adminRbacFilter() {
        return new AdminRbacFilter();
    }

    @Bean
    public FilterRegistrationBean<AdminRbacFilter> adminRbacFilterRegistration(final AdminRbacFilter filter) {
        final FilterRegistrationBean<AdminRbacFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.addUrlPatterns("/admin/*");
        return registration;
    }

    @Bean
    public Jackson2ObjectMapperBuilder jacksonCustomizer() {
        return Jackson2ObjectMapperBuilder.json()
                .findModulesViaServiceLoader(true)
                .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
