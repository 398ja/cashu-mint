# Operational invariants are exported as DB-derived gauges, not transition counters

A scheduled poller in `cashu-mint-jpa` runs the operator queries already recorded as Javadoc on `MeltSagaJpaRepository` and `VoucherIssuanceJpaRepository` every 60 seconds and exports each result as a Prometheus gauge. The invariants that matter — a Stuck Payment, a `PAYMENT_SENT_BURN_FAILED` saga, an Orphan Issuance — are alerted off these gauges.

We did this because all three are *durations in a database state*, not in-process moments. A counter incremented at the CAS transition cannot express "stuck for an hour" and loses its standing count on restart, which is exactly the wrong failure mode for conditions that by design never resolve themselves. A gauge derived from the reviewed operator SQL survives restarts, states the condition natively, and makes the alert a one-line threshold.

## Consequences

The mint now reads operational truth out of Postgres by two routes: this poller, and the spec-004 Grafana dashboards that query Postgres directly through the `cashu_mint_grafana_ro` role. The rule dividing them is **anything that must alert goes through the gauge poller; anything that is only browsed may query Postgres directly.** Grafana-managed alerts on a Postgres datasource cannot reach Alertmanager's routing, so alerting cannot use the direct route; pushing the spec-004 aggregates through Prometheus would widen the identity surface that spec-004's column-level `GRANT` deliberately narrowed, so browsing should not use the poller.

The poller assumes a single mint instance, which both compose files currently deploy. Running two replicas would produce two conflicting series per gauge and require leader election.
