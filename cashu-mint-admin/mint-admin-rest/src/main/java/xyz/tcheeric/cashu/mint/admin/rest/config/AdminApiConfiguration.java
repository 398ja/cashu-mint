package xyz.tcheeric.cashu.mint.admin.rest.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Spring configuration wiring the request filters and JSON customisations for admin endpoints.
 */
@Configuration
public class AdminApiConfiguration {

    @Bean
    public AdminCorrelationIdFilter adminCorrelationIdFilter() {
        return new AdminCorrelationIdFilter();
    }

    @Bean
    public FilterRegistrationBean<AdminCorrelationIdFilter> adminCorrelationIdFilterRegistration(
            final AdminCorrelationIdFilter filter) {
        final FilterRegistrationBean<AdminCorrelationIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/admin/*");
        return registration;
    }

    @Bean
    public Jackson2ObjectMapperBuilder jacksonCustomizer() {
        return Jackson2ObjectMapperBuilder.json()
                .findModulesViaServiceLoader(true)
                .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
