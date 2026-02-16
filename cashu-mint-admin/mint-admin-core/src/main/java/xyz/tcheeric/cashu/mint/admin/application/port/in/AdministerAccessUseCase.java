package xyz.tcheeric.cashu.mint.admin.application.port.in;

import java.util.Set;

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
                                   String versionTag,
                                   String displayName,
                                   String email,
                                   Set<String> roles,
                                   String reason) {

        public AdministerAccessRequest(String operatorId,
                                       String targetAccountId,
                                       AccessCommand command,
                                       String versionTag) {
            this(operatorId, targetAccountId, command, versionTag, null, null, Set.of(), null);
        }
    }

    record AdministerAccessResponse(String targetAccountId,
                                    String versionTag,
                                    String displayName,
                                    String email,
                                    Set<String> roles,
                                    boolean active,
                                    String message,
                                    String resetToken) { }
}
