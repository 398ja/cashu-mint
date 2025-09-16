package xyz.tcheeric.cashu.mint.admin.rest;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.ComponentScan;

import xyz.tcheeric.cashu.mint.admin.rest.controller.*;

/**
 * Minimal Spring Boot configuration for slice tests to ensure
 * Boot's test context can locate a {@code @SpringBootConfiguration}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackageClasses = {
        UsersAdminController.class,
        AlertsAdminController.class,
        LifecycleAdminController.class,
        ConfigurationAdminController.class
})
class TestAdminRestApplication {
}

