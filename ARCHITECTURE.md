# Architecture

## Shape

StartEx deliberately uses one Android application module. The product is local-only and the package boundaries are sufficient for the current size; introducing separate Gradle modules would add build and dependency edges without improving the requested outcome.

The UI is unidirectional: screens render immutable state and emit actions. Provider I/O, persistence, cryptography, transaction work, and trading decisions execute outside composables. Provider contracts and the transaction parser are interfaces so deterministic fixtures can replace live services.

```text
Compose UI
  -> application actions / preflight
    -> serialized trading coordinator
      -> discovery -> hard filters -> deterministic decision -> hard risk gate
      -> paper executor; future validated local signer/live executor remains locked
      -> confirmation + reconciliation -> position/accounting records
    -> provider interfaces (PumpPortal, Helius, Jupiter)
    -> Room / DataStore / Keystore
```

## Data ownership

- Room stores public wallet metadata, trusted addresses, monitoring sessions, candidates and snapshots, decisions, positions, transaction intents and facts, fees, provider health, and redacted events.
- DataStore stores non-secret mode and user preferences.
- Android Keystore stores non-exportable AES-256-GCM wrapping keys. Room stores encrypted seed/API-key ciphertext, IV, format version, and public metadata.
- An unlocked wallet seed remains in process memory for the active local session; provider keys remain in process memory while configured clients are active. Mutable buffers are cleared best-effort when those sessions end.
- Amounts cross boundaries as lamports or token atomic-unit integers. Fiat display and accounting use `BigDecimal`. Times are `Instant` values serialized as epoch milliseconds or ISO-8601 at export boundaries.

## Provider abstractions

`network` exposes small contracts instead of provider response models to the rest of the app:

- PumpPortal emits new-token and migration events from one WebSocket. It never subscribes to metered token/account trade feeds.
- Helius provides RPC reads, simulation, submission, signatures, and standard WebSocket subscriptions.
- Jupiter provides Tokens data and read-only Swap v2 orders. An execute client exists for contract coverage but production does not call it. Any future transaction bytes are untrusted input.

Every provider result carries freshness and health. Authentication failure, rate limit, stale data, malformed schema, connection loss, and disagreement remain typed failures. Reconnect uses bounded exponential backoff with jitter and a single connection per provider.

## Candidate state machine

```text
Observed -> Enriching -> Watching -> Scored -> Accepted -> EntryPending
    |           |           |          |
    +---------> Rejected <--+----------+
                |
              Expired
```

The creation event is persisted before enrichment. Hard filters run before scoring. A decision records snapshot IDs, timestamps, completeness, confidence, reasons, and the immutable strategy version. Insufficient critical data can reject or keep observing; it never produces a Live acceptance.

## Future Live transaction safety pipeline

This is a required design boundary, not a currently reachable production state machine. Live stays locked because production instruction semantics, address-table resolution, blockhash validation, and current Jupiter golden fixtures are absent.

```text
Draft -> Quoted -> Built -> Validated -> Simulated -> Signed -> Submitted
                                                            |         |
                                                            v         v
                                                        Rejected   Uncertain
                                                                      |
                                                        Reconcile ----+
                                                           |
                                            Confirmed / Failed / Expired
```

Before a signature, the decoded legacy or v0 message, resolved address lookup tables, signers, programs, instructions, transfers, fees/rent, compute budget, mint, recipient, amount, and recent blockhash must match the stored intent and caps. Unknown programs or unresolved lookup tables fail closed.

Submission persists the signature/hash before waiting. A timeout enters `Uncertain`; reconciliation checks signature status, expiry, and balance effects. A replacement is allowed only after failure or expiry is proven.

## Conceptual position and exit pipeline

```text
Opening -> Open -> ExitRequested -> ExitSubmitting -> ExitUncertain
             |            ^               |                 |
             +-- triggers-+               +------retry------+
                                           |
                                   Closed / ExitBlocked
```

An open position is created from confirmed fill facts. Its current value and P&L use a fresh executable full-position sell quote, including entry, DEX/aggregator, network, priority, rent, and token transfer costs. Exit rules are frozen with a version at entry. Retry is bounded; a missing route becomes visible `EXIT BLOCKED`, not a fabricated close.

## Serialized trading work

Paper entry and exit final-state mutations share one short gate and use Room transactions for current-row deltas. Manual-wallet transfer durability is handled by its own write-ahead coordinator. Circuit breakers stop entries but do not disable position protection or reconciliation.

## Foreground session and recovery

The foreground service is started only from a visible user action after preflight and promotes itself immediately. The private sideload declares `specialUse` for continuous on-device market monitoring, risk evaluation, and open-position protection; it is not a finite `dataSync` job. There is no `BOOT_COMPLETED` receiver and no permanent wake lock. A future Play distribution would require a fresh foreground-service policy review.

Before a recovered session accepts an entry it reloads persisted mode/positions/pending transactions, reconciles chain state, restores monitoring, and rechecks provider/risk health. Secure mode still requires user authentication before any signing key is available. Unattended mode cannot override exposure, daily-loss, reserve, fee, or health limits.
