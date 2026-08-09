# Testing

Tests are deterministic by default. Routine test tasks do not call live providers, require an API key, use a funded wallet, or submit a transaction.

## Local commands

Use JDK 17 and Android SDK 37, then run from the repository root:

```powershell
.\gradlew.bat clean
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
.\gradlew.bat :app:lint
.\gradlew.bat ktlintCheck
.\gradlew.bat detekt
```

If an API 37 emulator or phone is available:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Dependency and update inspection:

```powershell
.\gradlew.bat :app:dependencies
```

Only run tasks that exist in the checked-in Gradle configuration; the final verification report records the exact commands and results.

## Verified on 2026-08-09

The final local verification used JDK 17, Android SDK 37, and an Android 16
(API 36) Google APIs emulator:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest `
  :app:assembleDebug :app:assembleRelease :app:lint ktlintCheck detekt `
  --no-daemon --max-workers=1 --no-build-cache
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon --max-workers=1 `
  --no-build-cache
```

- JVM: 234 tests, 0 failures, 0 errors, 0 skipped
- Android instrumentation: 17 tests, 0 failures, 0 skipped
- Debug and R8-minified unsigned release APKs assembled successfully
- Android lint, ktlint, and detekt completed successfully
- The empty-data home state was recorded with Roborazzi and compared with the
  supplied visual reference; reference mock values were not copied

## Required JVM coverage

- SOL/lamport and token decimal conversions; EUR freshness; fee and net-P&L accounting
- deterministic score, hard rejection reasons, completeness/confidence, and strategy version
- candidate, position, exit/retry, and transaction state transitions
- hard limits, rolling-day accounting/reset, cooldowns, reserve, and circuit breakers
- Paper latency, requote, failed routes, slippage, and complete costs
- base58/address validation and trusted-address policy
- BIP-39 vectors and a known Trust Wallet Core Solana mnemonic/path/address vector;
  secret-envelope pure transformations
- transaction intent validator with accepted and malicious program/signer/transfer/authority/closure/fee/compute/ALT fixtures
- uncertain submission reconciliation and duplicate prevention
- PumpPortal, Helius, and Jupiter parsing for success, omission, malformed data, auth, and rate-limit responses
- retry/backoff limits and provider freshness/health

## Android/instrumentation coverage

When an emulator or phone is available, test:

- Room DAO transactions, foreign keys, retention, and migrations
- Android Keystore encryption/decryption, biometric cancel/failure, and invalidation handling
- onboarding, wallet backup quiz, navigation, preflight, start/pause/stop, and process recreation
- foreground notification and timeout lifecycle
- offline/online recovery and pending-transaction reconciliation
- `FLAG_SECURE`, notification permission, large font, TalkBack semantics, 48 dp targets, and reduced motion

## Provider fixtures

Sanitized response fixtures live under test resources and contain no real key, signature tied to funds, or personal address. Contract tests must include PumpPortal new-token/migration events, Helius RPC results/errors, and Jupiter order/execute results, plus missing fields, schema changes, stale values, HTTP 401/403/429/5xx, and socket closure.

Optional live read-only checks are separate and skipped without user-provided environment values. They may check health, a token feed, balances, and quotes; they may never sign or submit a mainnet transaction.

## Verification boundaries

A successful API 37 compile is not physical Pixel 9 runtime evidence. JVM/UI fixture tests are not biometric, Keystore hardware, camera, thermal, battery, reboot, or long-running foreground-service proof. A read-only quote is not an executed buy/sell test. Mainnet trades are prohibited during development verification.
