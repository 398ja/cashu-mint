package xyz.tcheeric.cashu.mint.admin.cli.port;

import xyz.tcheeric.cashu.mint.admin.cli.model.MintUsersRequest;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintUserRecord;

import java.util.List;

public interface MintUsersPort {

    List<MintUserRecord> listUsers(MintUsersRequest request);
}
