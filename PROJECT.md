# StartEx project

## Scope

StartEx is a single-user, sideloaded, phone-only Android application. It combines a dedicated local Solana wallet, free provider feeds, deterministic candidate evaluation, independent hard risk limits, and realistic Paper execution. A future Live path is represented by safety contracts and remains unimplemented and hard-locked. All durable data is local.

Out of scope: backend services, Firebase, cloud sync, accounts, analytics, billing, paid AI, LLM decisions, leverage, shared API keys, remote signing, PumpPortal metered trade feeds, Lightning transactions, and automated mainnet trades during development or tests.

## Current product boundary

- Android API 29 minimum; compile/target API 37.
- One `:app` module with package boundaries instead of speculative Gradle modules.
- Fixed dark Material 3 UI with Home, Watch, Wallet, History, and Settings navigation.
- Nine-step safety onboarding, preflight, honest empty/degraded states, and clear Paper/Live labeling.
- Room for operational records; DataStore for non-secret preferences; Android Keystore AES-GCM envelopes for seed and provider secrets.
- OkHttp for HTTP and WebSocket traffic.
- PumpPortal new-token and migration events only; Helius mainnet RPC/WebSocket; Jupiter Tokens and read-only Swap v2 orders. The execute client is not called by production code.
- A bounded user-started foreground session. No reboot auto-start.

## Safety invariants

1. Paper is the default and debug builds cannot enable Live execution.
2. Domain amounts use integer atomic units; display fiat uses `BigDecimal`.
3. Strategy acceptance never bypasses the independent hard risk layer.
4. Missing, stale, conflicting, unhealthy, or rate-limited critical data blocks a new Live entry.
5. Any future provider-created transaction must be decoded and checked against the intended swap before signing; production currently refuses that path.
6. A timeout is uncertain, not failed; reconciliation prevents duplicate submissions.
7. A position closes only from confirmed execution facts, never from a requested exit.
8. Entry can pause while position protection and reconciliation continue.
9. Plaintext seed material and provider keys never enter Room, logs, clipboard, exports, or backups; encrypted ciphertext and IV metadata are stored in Room.
10. Secure session mode cannot sign automatically after process death or reboot.

## Package map

- `ui`: Material 3 screens and unidirectional UI state
- `wallet` / `security`: mnemonic orchestration, derivation, signing adapter, authentication policy, and Keystore envelopes
- `network`: provider contracts, parsers, health, throttling, and backoff
- `data`: Room entities/DAOs and DataStore preferences
- `domain` / `trading`: exact amounts, candidate decisions, risk, paper execution, positions, exits, validation, and reconciliation
- `service`: foreground-session lifecycle

See [ARCHITECTURE.md](ARCHITECTURE.md) for data flow and state machines.

## Known platform and verification limits

- The continuous monitor uses a documented `specialUse` foreground-service subtype for private sideloading; a future Play distribution would require policy review.
- Secure session mode deliberately trades unattended exit capability for stronger key protection after process death.
- A current sell quote does not guarantee a future route or fill.
- Live execution is not composed: there are no production Jupiter instruction/ALT/blockhash resolvers or approved program allowlist, and `execute()` is unused.
- Sol4k is a community library below 1.0 and Solana Mobile Web3 Core is experimental; neither has an independently verified security audit for this use.
- Manual SOL transfers use mainnet even in debug builds. No devnet selector is implemented.
- Manual SPL transfers are disabled until `TransferChecked`, ATA creation, and Token-2022 extension checks are complete.
- API keys, a system image, and a connected phone are not repository inputs. Tests must not imply those integrations ran when they did not.
- Mainnet buy/sell validation is intentionally prohibited during development.

These limits are product behavior, not hidden fallbacks.
