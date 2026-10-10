package xyz.tcheeric.cashu.mint.rest.voucher;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import xyz.tcheeric.cashu.mint.jpa.SwapHoldReconciler;
import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHoldRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherRedemptionLedger;

/**
 * cashu-mint#540 review §2a: a redemption claim must survive neither a process death nor a lost
 * database reply as a permanent loss of the coupon.
 *
 * <p>The claim is auto-committed, and before this fix it was given back only from a {@code catch}.
 * A JVM killed between the claim and the signature, or a claim whose {@code UPDATE} committed but
 * whose reply was lost, left the claim standing with nothing signed. The redeemer's NUT-19 retry
 * then claimed again and, for a full-cap redemption in enforce, was refused: the honest holder
 * lost the coupon and nothing recovered it.
 *
 * <p>Failures are injected through interface proxies matched on method name only, so this test
 * drives the same code paths before and after the fix.
 */
@TestPropertySource(properties = {
    "cashu.mint.voucher.redemption-cap=enforce",
    "cashu.mint.voucher.issuer-binding=enforce",
    "cashu.mint.voucher.trusted-signers=" + VoucherRedemptionFixtures.ISSUER_PUB_X,
    "cashu.mint.voucher.redeemer-tokens=" + VoucherRedemptionRecoveryIT.REDEEMER,
    // The sweep is driven by hand below; the schedule must not race the tests.
    "cashu.mint.swap.hold-ttl=PT0S",
    "cashu.mint.swap.reconcile-interval=PT1H",
})
@Import(VoucherRedemptionRecoveryIT.FaultInjection.class)
class VoucherRedemptionRecoveryIT extends VoucherRedemptionFixtures {

  static final String REDEEMER = "it-recovery-redeemer-token";

  static final AtomicBoolean LOSE_NEXT_CLAIM_REPLY = new AtomicBoolean();
  static final AtomicBoolean FAIL_NEXT_GIVE_BACK = new AtomicBoolean();
  static final AtomicBoolean DIE_BEFORE_SIGNING = new AtomicBoolean();

  @Autowired SwapHoldReconciler reconciler;

  @Override
  String redeemerToken() {
    return REDEEMER;
  }

  @BeforeEach
  void noFaults() {
    LOSE_NEXT_CLAIM_REPLY.set(false);
    FAIL_NEXT_GIVE_BACK.set(false);
    DIE_BEFORE_SIGNING.set(false);
  }

  /**
   * The claim's UPDATE commits but its reply is lost, and the give-back fails too (the database
   * went away). The redeemer retries the same request: the retry reuses the standing claim rather
   * than adding a second one, so the full-cap redemption is accepted and counted once.
   */
  @Test
  void aRetryAfterALostClaimReplyReusesTheClaim() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String body = swapBody(voucherProofs(signedBlobHex(voucherId), 8, 2), outputs(8, 2));
    HttpHeaders headers = redemptionHeaders(voucherId + ":10", REDEEMER);

    LOSE_NEXT_CLAIM_REPLY.set(true);
    FAIL_NEXT_GIVE_BACK.set(true);
    ResponseEntity<String> lost = post("/v1/swap", body, headers);
    assertThat(lost.getStatusCode().is2xxSuccessful()).as("body=%s", lost.getBody()).isFalse();
    assertThat(redeemed(voucherId)).as("the claim committed").isEqualTo(CAP_SATS);

    ResponseEntity<String> retry = post("/v1/swap", body, redemptionHeaders(voucherId + ":10", REDEEMER));
    assertThat(retry.getStatusCode().is2xxSuccessful())
        .as("the NUT-19 retry reuses its own claim, body=%s", retry.getBody()).isTrue();
    assertThat(redeemed(voucherId)).as("counted once").isEqualTo(CAP_SATS);
  }

  /**
   * The process dies after the claim and before signing began. A retry of the same request reuses
   * the claim. Without a retry, the hold sweeper releases the stranded hold and its claim with it.
   */
  @Test
  void aClaimStrandedBeforeSigningIsReusedByARetry() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String body = swapBody(voucherProofs(signedBlobHex(voucherId), 8, 2), outputs(8, 2));

    DIE_BEFORE_SIGNING.set(true);
    ResponseEntity<String> died = post("/v1/swap", body, redemptionHeaders(voucherId + ":10", REDEEMER));
    assertThat(died.getStatusCode().is2xxSuccessful()).as("body=%s", died.getBody()).isFalse();

    ResponseEntity<String> retry = post("/v1/swap", body, redemptionHeaders(voucherId + ":10", REDEEMER));
    assertThat(retry.getStatusCode().is2xxSuccessful())
        .as("the retry reuses the dead attempt's claim, body=%s", retry.getBody()).isTrue();
    assertThat(redeemed(voucherId)).isEqualTo(CAP_SATS);
  }

  @Test
  void theHoldSweeperReleasesAClaimWhoseHoldNeverBeganSigning() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String body = swapBody(voucherProofs(signedBlobHex(voucherId), 8, 2), outputs(8, 2));

    DIE_BEFORE_SIGNING.set(true);
    assertThat(post("/v1/swap", body, redemptionHeaders(voucherId + ":10", REDEEMER))
        .getStatusCode().is2xxSuccessful()).isFalse();
    Thread.sleep(20);
    reconciler.reconcileTick();

    assertThat(redeemed(voucherId)).as("nothing was signed, so the sweep gives the claim back").isZero();
    assertThat(redeem(voucherId, 10, voucherProofs(signedBlobHex(voucherId), 8, 2)).getStatusCode()
        .is2xxSuccessful()).isTrue();
  }

  /** A hold that had begun signing is committed by the sweep, and its claim stands. */
  @Test
  void theHoldSweeperKeepsAClaimWhoseHoldBeganSigning() throws Exception {
    String voucherId = UUID.randomUUID().toString();
    String body = swapBody(voucherProofs(signedBlobHex(voucherId), 8, 2), outputs(8, 2));

    DIE_ON_NEXT_SIGNING.set(true);
    assertThat(post("/v1/swap", body, redemptionHeaders(voucherId + ":10", REDEEMER))
        .getStatusCode().is2xxSuccessful()).isFalse();
    Thread.sleep(20);
    reconciler.reconcileTick();

    assertThat(redeemed(voucherId)).as("signing may have begun: the inputs are spent, the claim stands")
        .isEqualTo(CAP_SATS);
  }

  /** Wraps the ledger and the hold repository so the tests can fail one call at a time. */
  @TestConfiguration
  static class FaultInjection {

    @Bean
    static BeanPostProcessor redemptionFaults() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
          if (bean instanceof VoucherRedemptionLedger ledger) {
            return proxy(VoucherRedemptionLedger.class, ledger, (method, args, call) -> {
              if (method.equals("release") && FAIL_NEXT_GIVE_BACK.getAndSet(false)) {
                throw new DataAccessResourceFailureException("give-back lost: database gone");
              }
              Object result = call.call();
              if (method.equals("claim") && LOSE_NEXT_CLAIM_REPLY.getAndSet(false)) {
                throw new DataAccessResourceFailureException("claim committed, reply lost");
              }
              return result;
            });
          }
          if (bean instanceof SwapHoldRepository holds) {
            return proxy(SwapHoldRepository.class, holds, (method, args, call) -> {
              if (method.equals("advance") && args[1] == SwapHoldPhase.SIGNING
                  && DIE_BEFORE_SIGNING.getAndSet(false)) {
                throw new SimulatedCrash();
              }
              return call.call();
            });
          }
          return bean;
        }
      };
    }
  }

  interface Interceptor {
    Object intercept(String method, Object[] args, java.util.concurrent.Callable<Object> call) throws Throwable;
  }

  @SuppressWarnings("unchecked")
  static <T> T proxy(Class<T> type, T target, Interceptor interceptor) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, method, args) -> {
      try {
        return interceptor.intercept(method.getName(), args, () -> {
          try {
            return method.invoke(target, args);
          } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) {
              throw ex;
            }
            throw (Error) e.getCause();
          }
        });
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    });
  }
}
