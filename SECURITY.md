# Security

## Threat model

StartEx is a hot-wallet application on a general-purpose phone. It assumes Android, the device lock, and the app process are not fully compromised. It does not protect funds against a rooted device, malicious accessibility service, hostile firmware, unlocked screen, recovered mnemonic, compromised provider, malicious-but-valid on-chain program, or a market that loses all liquidity.

The loss-limiting design is a separate low-balance wallet, explicit exposure and daily-loss caps, local authentication, fail-closed provider/transaction checks, and no cloud or remote signer. This reduces impact; it does not make a hot wallet equivalent to a hardware wallet.

## Secret storage

- Mnemonics are created with a cryptographically secure random source through a maintained BIP-39 library.
- Trust Wallet Core 4.7.3 performs hardened Solana derivation at `m/44'/501'/0'/0'`; StartEx tests the Android native binding against a known mnemonic, path, and address vector.
- BIP-39, key derivation, Ed25519, base58, and transaction wire formats are library-owned. StartEx clears JVM secret arrays it controls, but Trust Wallet Core's Java binding relies on nondeterministic phantom-reference cleanup for native handles, so native-memory reclamation remains part of the security boundary.
- Android Keystore creates non-exportable AES-256-GCM wrapping keys. Secure wallet creation, saving, unlock, and reveal use strong authentication; crypto-bound unwrap operations require `BIOMETRIC_STRONG`.
- Only encrypted envelopes are persisted in Room. An unlocked wallet seed remains in process memory for the active session and provider keys remain in process memory while activated; mutable copies are cleared best-effort when released.
- Provider keys use encrypted envelopes as well. They are never hardcoded or placed in URLs outside the provider's required transport format.
- Room, logs, crash output, exports, notifications, intents, saved UI state, clipboard, and backups must not contain a mnemonic, private key, or provider key.

`allowBackup=false`, data-extraction rules exclude app data, cleartext network traffic is disabled, foreground components are non-exported, and PendingIntents are immutable. Sensitive activities use `FLAG_SECURE` before content is composed.

## Authentication modes

### Secure session (default)

The Keystore unwrap operation is bound to a strong biometric prompt. The key is held in memory only for the required signing scope. Background timeout, process death, reboot, cancellation, enrollment invalidation, or device-lock changes return the app to a locked state. Open positions can still be monitored, but an exit cannot be signed until the user authenticates. The UI must surface that risk immediately.

### Unattended restart (explicit opt-in)

Unattended mode weakens protection by allowing a dedicated low-balance wallet to become sign-capable after restart under the configured policy. Enrollment requires device authentication, a clear hot-wallet warning, and hard exposure/daily-loss limits. Withdrawal-address changes, security changes, recovery phrase access, and manual transfers still require authentication. Android may prevent automatic monitoring restart; the safe fallback is a critical notification, never a hidden background bypass.

## Recovery phrase

The phrase is revealed only on a protected screen, never copied, and followed by a word-position quiz. StartEx has no recovery service. If the Keystore key is invalidated or the app/device is lost, the offline mnemonic is the only recovery mechanism. Restoring a primary/high-value wallet is explicitly discouraged.

## Future unsigned transaction boundary

This boundary is mandatory but not yet connected to production. Automated Live execution stays hard-locked until every item below is implemented with current Jupiter fixtures and an approved program allowlist.

All Jupiter or optional PumpPortal transaction bytes are hostile input. Before signing, StartEx must:

1. Parse the complete legacy or v0 message with a maintained Solana library.
2. Resolve and inspect every address lookup table; unresolved entries reject the transaction.
3. Match the expected fee payer, signer set, mint, destination, input amount, and bounded SOL outflow.
4. Allow only current reviewed programs and intended instructions.
5. Reject unexpected transfers, signers, delegates, authority changes, account closures, token programs/extensions, and writable accounts.
6. Enforce compute-unit, priority-fee, network-fee, rent, slippage, and total-cost caps.
7. Require a fresh blockhash and simulate the exact bytes before signing.
8. Store the intent, parser/validator version, resolved accounts, simulation result, and transaction hash/signature.

Provider approval scores and a successful simulation are not security guarantees. Sol4k is a community dependency below 1.0 with no independently verified audit; current golden fixtures and a dedicated review remain a mandatory Live gate.

## Operational incidents

If a secret, device, provider account, or transaction is suspected compromised:

1. Stop new entries and stop monitoring from the persistent notification.
2. Authenticate and use the trusted withdrawal flow only if transaction validation and provider health pass.
3. If an exit/transfer is uncertain, reconcile it before creating another transaction.
4. Revoke/rotate provider keys at the provider, then replace the encrypted local value.
5. Move remaining funds to a newly generated wallet from a trusted environment.
6. Preserve redacted event/transaction exports for diagnosis; never export a mnemonic or key.
7. Reinstall and restore only after the device is considered trustworthy.

Report security issues privately to the repository owner. Do not include secrets, mnemonics, funded addresses, or unredacted provider responses in an issue.
