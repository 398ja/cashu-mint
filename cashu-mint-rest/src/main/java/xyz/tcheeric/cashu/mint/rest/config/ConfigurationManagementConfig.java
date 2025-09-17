package xyz.tcheeric.cashu.mint.rest.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.cashu.mint.proto.application.configuration.DefaultManageConfigurationInteractor;
import xyz.tcheeric.cashu.mint.proto.application.configuration.ManageConfigurationInteractor;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.ApprovalPolicyEngine;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.AuditTrailWriter;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.ConfigurationRevisionRepository;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.ConfigurationSecretsGateway;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.DiffRenderer;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.DomainEventPublisher;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.NotificationGateway;
import xyz.tcheeric.cashu.mint.proto.application.configuration.port.SchemaValidator;

/**
 * Exposes configuration management application services to REST adapters.
 */
@Configuration
public class ConfigurationManagementConfig {

    @Bean
    @ConditionalOnBean({
            ConfigurationRevisionRepository.class,
            SchemaValidator.class,
            ApprovalPolicyEngine.class,
            DiffRenderer.class,
            ConfigurationSecretsGateway.class,
            NotificationGateway.class,
            AuditTrailWriter.class,
            DomainEventPublisher.class
    })
    public ManageConfigurationInteractor manageConfigurationInteractor(
            ConfigurationRevisionRepository revisionRepository,
            SchemaValidator schemaValidator,
            ApprovalPolicyEngine approvalPolicyEngine,
            DiffRenderer diffRenderer,
            ConfigurationSecretsGateway secretsGateway,
            NotificationGateway notificationGateway,
            AuditTrailWriter auditTrailWriter,
            DomainEventPublisher domainEventPublisher) {
        return new DefaultManageConfigurationInteractor(
                revisionRepository,
                schemaValidator,
                approvalPolicyEngine,
                diffRenderer,
                secretsGateway,
                notificationGateway,
                auditTrailWriter,
                domainEventPublisher);
    }
}
