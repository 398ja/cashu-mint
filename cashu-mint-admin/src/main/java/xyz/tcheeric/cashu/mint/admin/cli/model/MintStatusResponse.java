package xyz.tcheeric.cashu.mint.admin.cli.model;

public record MintStatusResponse(String mintId,
                                 String state,
                                 int activeUsers,
                                 int pendingAlerts) {
}
