# API and dependency notes

Last documentation review: **2026-08-09**. These services and Android rules are time-dependent. Recheck official documentation, fixtures, rate limits, fees, authentication, program allowlists, and terms before enabling Live mode.

## Android

- Android 17 is API 37; StartEx compiles and targets 37. [Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk)
- AGP 9.3.1 requires JDK 17 and Gradle 9.5 or newer. The wrapper is 9.7.0. [AGP 9.3 release notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes)
- AGP 9.3.1 uses built-in Kotlin 2.2.10; the project does not apply `org.jetbrains.kotlin.android`. [Built-in Kotlin migration](https://developer.android.com/build/migrate-to-built-in-kotlin)
- Android lists finite fetch/sync operations under `dataSync`, which is limited to six background hours per rolling 24 hours on target 35+. StartEx's foreground unit of work is instead continuous, user-initiated, on-device market monitoring, risk evaluation, and open-position protection, so the private sideload declares `specialUse` with that exact subtype. `specialUse` is only valid for use cases not covered by another type and would require review if the app were ever submitted to Google Play. [Foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)
- The service is never started from `BOOT_COMPLETED`. A visible user action is required, and recovery remains fail-closed when authentication, providers, or persisted state are not ready.

## Helius

- HTTPS: `https://mainnet.helius-rpc.com/?api-key=...`
- WSS: `wss://mainnet.helius-rpc.com/?api-key=...`
- StartEx has no devnet selector; wallet balance, activity, simulation and manual SOL submission use mainnet in every build type.
- Free plan at review: 1M credits/month, 10 RPC requests/s, 1 `sendTransaction`/s, 5 concurrent WebSockets.
- Standard Solana subscriptions are used; enhanced `transactionSubscribe` is not assumed on Free.
- Keep one socket, ping before the 10-minute inactivity timeout, reconnect with bounded exponential backoff and jitter, and stop entries when quota/health/freshness fails.

Sources: [pricing](https://www.helius.dev/pricing), [credits](https://www.helius.dev/docs/billing/credits), [endpoints](https://www.helius.dev/docs/api-reference/endpoints), [WebSockets](https://www.helius.dev/docs/faqs/websockets).

An API key embedded in an APK can be recovered. StartEx accepts the user's own encrypted key. Helius documents a masked HTTPS endpoint, but no equivalent shared-key-safe WSS URL was confirmed; a shared production WSS key would require a backend, which is outside this product.

## PumpPortal

- WSS: `wss://pumpportal.fun/api/data?api-key=...`
- Since 2026-05-01 all trading data requires an API key. `subscribeNewToken` and `subscribeMigration` remain free with a key.
- StartEx does not subscribe to metered `subscribeTokenTrade` or `subscribeAccountTrade` feeds (reviewed price: 0.01 SOL per 10,000 received events).
- Use one WebSocket; stay below 200 subscription messages/s and 5,000 addresses/message.
- StartEx does not call PumpPortal's Local Transaction API. If that boundary is reconsidered later, the returned transaction must pass the same local parsing, allowlist, simulation, and cost gates as any other untrusted transaction.
- Lightning is prohibited because it submits remotely and conflicts with the self-custody/local-validation boundary.

Sources: [real-time data](https://pumpportal.fun/data-api/real-time/), [fees](https://pumpportal.fun/fees/), [Local Transaction API](https://pumpportal.fun/local-trading-api/trading-api/), [legal](https://pumpportal.fun/legal/).

PumpPortal is a third party and states it is not affiliated with pump.fun or Raydium. Its availability or latency claims are not safety guarantees.

## Jupiter Swap v2

- Base: `https://api.jup.ag/swap/v2`
- `GET /order?inputMint=...&outputMint=...&amount=...&taker=...` returns quote data, a base64 v0 transaction, and `requestId`; omitting `taker` is quote-only.
- Partially sign the exact validated v0 transaction. `POST /execute` sends `signedTransaction`, `requestId`, and optionally `lastValidBlockHeight`.
- Auth header: `x-api-key`. Reviewed limits: keyless 0.5 RPS; Free key 1 RPS. Execute has its own higher bucket.
- Reviewed platform fee varies by pair/token age and may be 50 bps for tokens younger than 24 hours. Always use response values and absolute local caps; never hardcode a single fee assumption.

Sources: [order and execute](https://developers.jup.ag/docs/swap/order-and-execute), [order reference](https://developers.jup.ag/docs/api-reference/swap/order), [execute reference](https://developers.jup.ag/docs/api-reference/swap/execute), [plans](https://developers.jup.ag/docs/portal/plans).

Jupiter transaction bytes are untrusted. Router/program allowlists must be sourced from current official program information and proven by current legacy/v0/ALT golden fixtures before Live is unlocked. A provider score is not a safety guarantee.

## Jupiter Tokens v2 and SOL/EUR

- Token enrichment uses `GET https://api.jup.ag/tokens/v2/search?query=...` with `x-api-key`. Missing audit, holder, liquidity, activity, token-program, or freshness fields fail closed. [Jupiter token information](https://developers.jup.ag/docs/tokens/token-information), [token search guide](https://developers.jup.ag/docs/guides/how-to-get-token-information)
- Fiat display uses Kraken's public `GET https://api.kraken.com/0/public/OHLC?pair=SOLEUR&interval=1`. The one-minute candle close is parsed as `BigDecimal`; an invalid, future, or older-than-two-minutes candle is unavailable rather than silently reused. No Kraken key or trading account is used. [Kraken OHLC](https://docs.kraken.com/api-reference/market-data/get-ohlc-data)

## Solana wallet libraries

- `cash.z.ecc.android:kotlin-bip39:1.0.9` handles BIP-39 checksum/seed behavior. [Release](https://github.com/zcash/kotlin-bip39/releases/tag/v1.0.9)
- Trust Wallet Core 4.7.3 performs the hardened Solana private-key derivation. Its official GitHub Packages Android AAR is vendored at `app/libs/wallet-core-4.7.3.aar` with SHA-256 `0082319df49f2cb0641fc06f7a5633e0fb3e2caadccdb1c4ba58626315149708`; the source and license are recorded in `app/libs/THIRD_PARTY_NOTICES.md`. [Trust Wallet Core 4.7.3](https://github.com/trustwallet/wallet-core/tree/4.7.3)
- Default account: `m/44'/501'/0'/0'`; Solana coin type is 501. [Solana mnemonic cookbook](https://solana.com/developers/cookbook/wallets/restore-from-mnemonic), [SLIP-0044](https://github.com/satoshilabs/slips/blob/master/slip-0044.md)
- `org.sol4k:sol4k:0.6.1` supplies Ed25519/base58 and the locally built legacy SOL-transfer transaction. It is pinned below 0.8.2 because 0.8.2 is compiled with Kotlin 2.4 metadata, incompatible with AGP 9.3.1's built-in Kotlin 2.2 compiler. [Sol4k releases](https://github.com/sol4k/sol4k/releases)
- `com.solanamobile:web3-solana:0.3.1` supplies the legacy/v0/ALT parser used by safety tests. Its own project labels Web3 Core experimental, and production resolvers are not implemented. [Solana Mobile Web3 Core](https://github.com/solana-mobile/web3-core)

Trust Wallet Core's Java binding does not expose deterministic close operations for native `HDWallet`/`PrivateKey` handles. StartEx clears its JVM copies in `finally` blocks, but cannot guarantee when native secret memory is reclaimed or wiped. No independent audit was confirmed for StartEx's exact Trust Wallet Core derivation path, Sol4k is below 1.0, and Solana Mobile Web3 Core is experimental. No production Jupiter program allowlist is approved or versioned yet; test program IDs are fixtures only. These are Live safety gates, not assumed guarantees.

## Migration checklist

When an Android or provider version changes:

1. Read the official changelog and authentication/rate/fee docs.
2. Update sanitized response and transaction fixtures first.
3. Run parser, transaction-validator, state-machine, accounting, lint, and release-minification tests.
4. Re-review program allowlists and instruction semantics.
5. Run optional live read-only health/quote checks with user keys.
6. Keep Live locked if any schema, route, fee, signer, program, or ALT behavior is not understood.
