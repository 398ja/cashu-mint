package xyz.tcheeric.cashu.mint.admin.application.port.in;

/**
 * Provides health and status reporting for mint instances.
 */
public interface MonitorMintHealthUseCase {

    MonitorMintHealthResponse handle(MonitorMintHealthRequest request);

    enum HealthQuery {
        SNAPSHOT,
        STREAM,
        ACKNOWLEDGE_ALERT
    }

    enum HealthStatus {
        HEALTHY,
        WARNING,
        CRITICAL,
        UNKNOWN
    }

    record MonitorMintHealthRequest(String mintId,
                                     HealthQuery query,
                                     String versionTag) { }

    record MonitorMintHealthResponse(String mintId,
                                      HealthStatus status,
                                      String versionTag) { }
}
