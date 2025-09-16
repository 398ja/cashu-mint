package xyz.tcheeric.cashu.mint.rest.admin.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import xyz.tcheeric.cashu.mint.admin.presentation.lifecycle.LifecycleSummaryPresenter;
import xyz.tcheeric.cashu.mint.rest.admin.presenter.LifecycleSummaryApiPresenter;

/**
 * Supplies the dependencies required by {@link AdminLifecycleService}.
 */
@Configuration
public class AdminLifecycleServiceConfiguration {

    @Bean
    public ConcurrentMap<String, AdminLifecycleService.MintRecord> adminLifecycleState() {
        return new ConcurrentHashMap<>();
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
