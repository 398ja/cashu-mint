package xyz.tcheeric.cashu.mint.admin.cli.port.stub;

import xyz.tcheeric.cashu.mint.admin.cli.model.MintUserRecord;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintUsersRequest;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintUsersPort;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Stub implementation returning synthetic operator accounts.
 */
public final class StubMintUsersPort implements MintUsersPort {

    private static final List<MintUserRecord> USERS = List.of(
        new MintUserRecord("operator-1", "Alice", "ADMIN", true),
        new MintUserRecord("operator-2", "Bob", "AUDITOR", true),
        new MintUserRecord("operator-3", "Charlie", "VIEWER", false)
    );

    @Override
    public List<MintUserRecord> listUsers(final MintUsersRequest request) {
        if (request.includeInactive()) {
            return List.copyOf(USERS);
        }
        return USERS.stream().filter(MintUserRecord::active).collect(Collectors.toList());
    }
}
