package xyz.tcheeric.cashu.mint.admin.cli.port;

import xyz.tcheeric.cashu.mint.admin.cli.model.MintAlertRecord;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintAlertsRequest;

import java.util.List;

public interface MintAlertsPort {

    List<MintAlertRecord> listAlerts(MintAlertsRequest request);
}
