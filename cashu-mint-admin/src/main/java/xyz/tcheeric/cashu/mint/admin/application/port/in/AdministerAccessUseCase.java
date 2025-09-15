package xyz.tcheeric.cashu.mint.admin.application.port.in;

/**
 * Manages administrator and operator accounts.
 */
public interface AdministerAccessUseCase {

    AdministerAccessResponse handle(AdministerAccessRequest request);

    enum AccessCommand {
        PROVISION,
        UPDATE_ROLES,
        REVOKE,
        RESET_CREDENTIALS
    }

    record AdministerAccessRequest(String operatorId,
                                   String targetAccountId,
                                   AccessCommand command,
                                   String versionTag) { }

    record AdministerAccessResponse(String targetAccountId,
                                    String versionTag) { }
}
