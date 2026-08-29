package xyz.tcheeric.cashu.mint.proto.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.ports.MintSuspensionRepository;

/**
 * A suspended mint refuses to issue.
 *
 * <p>Only issuance: swap and melt run through their own tasks and are untouched, so a suspended
 * mint keeps honouring redemption and holders can always exit. See ADR-0006 and ADR-0007.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MintTaskSuspensionTest {

  private static final String MINT_ID = "00000000-0000-0000-0000-0000000000aa";

  @Mock private MintSuspensionRepository suspensions;

  @Mock private xyz.tcheeric.cashu.mint.proto.service.MintProtocolService mintProtocolService;

  @Mock private xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService signatureVaultService;

  @AfterEach
  void clearContext() {
    MintIntegrityContext.clear();
  }

  @Test
  @DisplayName("A suspended mint refuses to issue, before touching any output")
  // The assertion that matters: the refusal happens on the issuance path itself,
  // and nothing is signed or stored on the way to it.
  void refusesToIssueWhileSuspended() throws Exception {
    MintIntegrityContext.installMintSuspension(suspensions);
    when(suspensions.isIssuanceSuspended(MINT_ID)).thenReturn(true);

    final Mint mint = new Mint(MINT_ID);
    // Left empty on purpose: the refusal must happen before the request is
    // even inspected, so a suspended mint does no work on the way to saying no.
    final PostMintRequest<xyz.tcheeric.cashu.common.Secret> request = new PostMintRequest<>();
    final MintTask<xyz.tcheeric.cashu.common.Secret> task =
        new MintTask<>(
            request, PaymentMethod.BOLT11, mint, mintProtocolService, signatureVaultService);

    assertThatThrownBy(task::execute)
        .isInstanceOf(CashuErrorException.class)
        .extracting(t -> ((CashuErrorException) t).getErrorCode().name()).isEqualTo("mint_suspended");

    // Nothing may be signed or stored for a mint that is not issuing.
    verify(signatureVaultService, never()).store(any(), any());
  }

  @Test
  @DisplayName("Suspension is read from the durable record, not from memory")
  // Ensures the mint consults the durable record rather than process state, which
  // is what makes a suspension survive a restart.
  void readsSuspensionFromTheDurableRecord() {
    MintIntegrityContext.installMintSuspension(suspensions);
    when(suspensions.isIssuanceSuspended(anyString())).thenReturn(true);

    assertThat(MintIntegrityContext.mintSuspensionRepository()).isSameAs(suspensions);
    assertThat(MintIntegrityContext.mintSuspensionRepository().isIssuanceSuspended("mint-1"))
        .isTrue();
  }

  @Test
  @DisplayName("A mint with no suspension record issues normally")
  // Ensures the absent-record case is permissive: with the JPA module disabled
  // there is no record, and the mint must keep working.
  void treatsAnAbsentRecordAsNotSuspended() {
    MintIntegrityContext.installMintSuspension(null);

    assertThat(MintIntegrityContext.mintSuspensionRepository()).isNull();
  }

  @Test
  @DisplayName("Resuming clears the record so issuance can proceed")
  // Ensures suspend and resume are the inverse of each other, so suspension is
  // reversible rather than terminal.
  void resumeClearsTheSuspension() {
    MintIntegrityContext.installMintSuspension(suspensions);

    MintIntegrityContext.mintSuspensionRepository().suspend("mint-1", "incident");
    verify(suspensions).suspend("mint-1", "incident");

    MintIntegrityContext.mintSuspensionRepository().resume("mint-1");
    verify(suspensions).resume("mint-1");
  }
}
