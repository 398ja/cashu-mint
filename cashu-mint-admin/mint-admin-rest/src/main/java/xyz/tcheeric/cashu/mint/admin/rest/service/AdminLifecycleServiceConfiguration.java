package xyz.tcheeric.cashu.mint.admin.rest.service;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc.JdbcAlertRepository;
import xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc.JdbcConfigurationSetRepository;
import xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc.JdbcMintHealthRepository;
import xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc.JdbcMintLifecycleHistoryRepository;
import xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc.JdbcMintRepository;
import xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc.JdbcOperationalControlRepository;
import xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc.JdbcOperatorAccessRepository;
import xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc.JdbcOutboxRepository;
import xyz.tcheeric.cashu.mint.admin.adapter.out.outbox.TransactionalOutboxMintLifecycleEventPublisher;
import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ExecuteOperationalControlsUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageNotificationsUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.MonitorMintHealthUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.out.AlertRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.ConfigurationSetRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintHealthRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleEventPublisher;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintLifecycleHistoryRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperationalControlRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OutboxRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.TransactionManager;
import xyz.tcheeric.cashu.mint.admin.application.service.AdministerAccessInteractor;
import xyz.tcheeric.cashu.mint.admin.application.service.ExecuteOperationalControlsInteractor;
import xyz.tcheeric.cashu.mint.admin.application.service.ManageConfigurationInteractor;
import xyz.tcheeric.cashu.mint.admin.application.service.ManageMintLifecycleInteractor;
import xyz.tcheeric.cashu.mint.admin.application.service.ManageNotificationsInteractor;
import xyz.tcheeric.cashu.mint.admin.application.service.MonitorMintHealthInteractor;
import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummaryPresenter;
import xyz.tcheeric.cashu.mint.admin.rest.config.SpringTransactionManager;
import xyz.tcheeric.cashu.mint.admin.rest.presenter.LifecycleSummaryApiPresenter;

/**
 * Supplies the dependencies required by {@link AdminLifecycleService},
 * wiring the real {@link ManageMintLifecycleInteractor} backed by JDBC repositories.
 */
@Configuration
public class AdminLifecycleServiceConfiguration {

    @Bean
    public Clock adminClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ConfigurationSetRepository configurationSetRepository(final DataSource dataSource,
                                                                  final ObjectMapper objectMapper) {
        return new JdbcConfigurationSetRepository(dataSource, objectMapper);
    }

    @Bean
    public MintRepository mintRepository(final DataSource dataSource,
                                         final ConfigurationSetRepository configurationSetRepository,
                                         final ObjectMapper objectMapper) {
        return new JdbcMintRepository(dataSource, (JdbcConfigurationSetRepository) configurationSetRepository, objectMapper);
    }

    @Bean
    public OutboxRepository outboxRepository(final DataSource dataSource, final ObjectMapper objectMapper) {
        return new JdbcOutboxRepository(dataSource, objectMapper);
    }

    @Bean
    public MintLifecycleHistoryRepository lifecycleHistoryRepository(final DataSource dataSource,
                                                                      final ObjectMapper objectMapper) {
        return new JdbcMintLifecycleHistoryRepository(dataSource, objectMapper);
    }

    @Bean
    public OperatorAccessRepository operatorAccessRepository(final DataSource dataSource,
                                                              final ObjectMapper objectMapper) {
        return new JdbcOperatorAccessRepository(dataSource, objectMapper);
    }

    @Bean
    public AlertRepository alertRepository(final DataSource dataSource, final ObjectMapper objectMapper) {
        return new JdbcAlertRepository(dataSource, objectMapper);
    }

    @Bean
    public MintHealthRepository mintHealthRepository(final DataSource dataSource) {
        return new JdbcMintHealthRepository(dataSource);
    }

    @Bean
    public OperationalControlRepository operationalControlRepository(final DataSource dataSource) {
        return new JdbcOperationalControlRepository(dataSource);
    }

    @Bean
    public TransactionManager coreTransactionManager(final PlatformTransactionManager platformTransactionManager) {
        return new SpringTransactionManager(new TransactionTemplate(platformTransactionManager));
    }

    @Bean
    public MintLifecycleEventPublisher mintLifecycleEventPublisher(final OutboxRepository outboxRepository,
                                                                    final ObjectMapper objectMapper) {
        return new TransactionalOutboxMintLifecycleEventPublisher(outboxRepository, objectMapper);
    }

    @Bean
    public ManageMintLifecycleUseCase manageMintLifecycleUseCase(final MintRepository mintRepository,
                                                                  final ConfigurationSetRepository configurationSetRepository,
                                                                  final TransactionManager transactionManager,
                                                                  final MintLifecycleEventPublisher eventPublisher,
                                                                  final Clock adminClock) {
        return new ManageMintLifecycleInteractor(mintRepository, configurationSetRepository,
            transactionManager, eventPublisher, adminClock);
    }

    @Bean
    public ManageConfigurationUseCase manageConfigurationUseCase(final MintRepository mintRepository,
                                                                  final ConfigurationSetRepository configurationSetRepository,
                                                                  final TransactionManager transactionManager,
                                                                  final MintLifecycleEventPublisher eventPublisher,
                                                                  final Clock adminClock) {
        return new ManageConfigurationInteractor(mintRepository, configurationSetRepository,
            transactionManager, eventPublisher, adminClock);
    }

    @Bean
    public ManageNotificationsUseCase manageNotificationsUseCase(final AlertRepository alertRepository) {
        return new ManageNotificationsInteractor(alertRepository);
    }

    @Bean
    public AdministerAccessUseCase administerAccessUseCase(final OperatorAccessRepository operatorAccessRepository) {
        return new AdministerAccessInteractor(operatorAccessRepository);
    }

    @Bean
    public MonitorMintHealthUseCase monitorMintHealthUseCase(final MintHealthRepository mintHealthRepository,
                                                              final Clock adminClock) {
        return new MonitorMintHealthInteractor(mintHealthRepository, adminClock);
    }

    @Bean
    public ExecuteOperationalControlsUseCase executeOperationalControlsUseCase(
        final OperationalControlRepository operationalControlRepository,
        final Clock adminClock) {
        return new ExecuteOperationalControlsInteractor(operationalControlRepository, adminClock);
    }

    @Bean
    public LifecycleSummaryPresenter lifecycleSummaryPresenter() {
        return new LifecycleSummaryPresenter();
    }

    @Bean
    public LifecycleSummaryApiPresenter lifecycleSummaryApiPresenter() {
        return new LifecycleSummaryApiPresenter();
    }
}
