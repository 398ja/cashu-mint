# Suspending a mint drains it rather than stopping it

A suspended mint refuses to issue new tokens but continues to honour swaps and
melts. We chose this over stopping all protocol operations because blocking
redemption strands holders' funds behind an operator action, and "we are
performing maintenance" is not an acceptable reason for someone to be unable to
redeem their ecash.

## Consequences

Suspend is therefore not a way to take a mint fully offline — it still serves
redemption traffic and still needs its Lightning gateway. An operator who needs
everything stopped stops the process. A distinct hard-stop state may be worth
adding later; it is deliberately not in the first version.

Pause and resume currently record only — `VaultProvisioningOutboxHandler`
branches on `CREATED` and `RETIRED` alone — so this decision describes what
suspend must do when it is built, not what it does today.
