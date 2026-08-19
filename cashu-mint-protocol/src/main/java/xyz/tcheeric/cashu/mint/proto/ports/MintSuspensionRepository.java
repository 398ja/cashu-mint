package xyz.tcheeric.cashu.mint.proto.ports;

/**
 * Durable record of whether a mint is suspended from issuing.
 *
 * <p>A suspended mint issues no new tokens but still honours swaps and melts, so holders can always
 * exit — see ADR-0006. The state lives with the mint and survives a restart, because a mint that
 * comes back up issuing again is the failure suspending is meant to prevent (ADR-0007).
 */
public interface MintSuspensionRepository {

  /**
   * Whether the mint is currently suspended from issuing.
   *
   * @param mintId mint to check
   * @return true when new issuance must be refused
   */
  boolean isIssuanceSuspended(String mintId);

  /**
   * Suspend the mint from issuing.
   *
   * @param mintId mint to suspend
   * @param reason operator's reason, for the record
   */
  void suspend(String mintId, String reason);

  /**
   * Allow the mint to issue again.
   *
   * @param mintId mint to resume
   */
  void resume(String mintId);
}
