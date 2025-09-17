package xyz.tcheeric.cashu.mint.proto.application.configuration.dto;

/**
 * Decision taken by an approver for a configuration stage.
 */
public enum Decision {
    PENDING,
    APPROVED,
    REJECTED,
    ACKNOWLEDGED
}
