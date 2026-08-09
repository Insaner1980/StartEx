# StartEx

StartEx is a private, local-only Android app for monitoring and simulating very small Solana trades from a dedicated self-custody wallet. Paper mode is the default. Automated Live execution is not wired into the production runtime and is hard-locked; no build flag or UI action can enable it.

> StartEx cannot guarantee an exit, prevent every malicious token, or make trading safe. A route can disappear after entry. Use only a separate low-balance wallet and only funds you can lose completely.

The app has no backend, user account, cloud database, analytics, paid AI, remote signer, or wallet-adapter approval flow. Secrets stay encrypted on the phone.

## Visual direction

The supplied design board was used only as a visual reference. Its mock balances, tokens, events, and statuses are not application data. Runtime empty states remain honest until a wallet and providers return data.

## Requirements

- Android Studio with JDK 17
- Android SDK 37
- A phone or emulator running Android 10 (API 29) or newer
- A registered strong biometric for Keystore-bound wallet creation and saving
- For read-only provider checks: the user's own free Helius, PumpPortal, and Jupiter API keys

## Build

On Windows PowerShell, set the local SDK/JDK for the current shell and run:

```powershell
$env:ANDROID_HOME = 'C:\Users\<you>\AppData\Local\Android\Sdk'
$env:JAVA_HOME = '<path-to-jdk-17>'
.\gradlew.bat clean assembleDebug
```

Debug and release builds both have `LIVE_TRADING_BUILD_ENABLED=false`. Release compilation verifies the locked Live scaffold:

```powershell
.\gradlew.bat assembleRelease
```

Install the debug APK with Android Studio or:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

No signing key is stored in this repository. Configure release signing outside the project before distributing a release APK.

## First run

1. Read and acknowledge all nine onboarding steps.
2. Create a new dedicated wallet; importing a primary wallet is strongly discouraged.
3. Record the recovery phrase offline and pass the backup quiz.
4. Keep Secure session mode unless the unattended hot-wallet risk is explicitly acceptable.
5. Enter the user's provider keys, test Helius and Jupiter, then start Paper monitoring to establish PumpPortal WebSocket health.
6. Set strict risk limits and a fee/exit reserve.
7. Grant notifications and review battery settings.
8. Run Paper mode and inspect candidate rejections and accounting before considering Live mode.

API keys are user-specific. A shared key embedded in an APK is not secret. The app intentionally fails closed when critical provider data is absent, stale, inconsistent, or rate-limited.

## Network and transfer warning

StartEx currently uses Solana mainnet only. Debug builds do not turn a manual SOL transfer into a test transaction: after review and authentication, Send SOL can sign and broadcast a real mainnet transfer through the configured Helius endpoint. Use the mocked automated tests for development and fund the dedicated wallet only with an amount you deliberately accept risking.

Manual SPL-token sending is disabled. `TransferChecked`, destination associated-token-account creation, and complete Token-2022 extension validation are not yet connected to the production UI.

## Android monitoring limit

StartEx runs monitoring only after an explicit user action and declares the private sideload's continuous on-device monitoring/risk/position-protection work as `specialUse`. It does not auto-start from `BOOT_COMPLETED`; provider, authentication, and recovery checks fail closed. A future Google Play distribution would require a fresh foreground-service policy review. See [API_NOTES.md](API_NOTES.md).

## Future Live implementation gate

- Recovery phrase verified and stored offline
- Dedicated wallet holds only a deliberately small amount
- Strong device lock and biometric authentication available
- Helius, PumpPortal, and Jupiter checks healthy and fresh
- Buy and immediate full-sell routes both available
- Program allowlist and transaction-validator fixtures current
- Exposure, daily-loss, fee, slippage, holding-time, and reserve limits reviewed
- Paper records and failure cases inspected
- No unresolved transaction or blocked exit

There is currently no supported procedure for unlocking Live automation. Production Jupiter instruction resolvers, an official versioned program allowlist, and sanitized current legacy/v0/ALT golden fixtures must be implemented and reviewed first.

## Documentation

- [Project status and scope](PROJECT.md)
- [Architecture and recovery](ARCHITECTURE.md)
- [Security model](SECURITY.md)
- [Strategy and risk rules](STRATEGY.md)
- [Testing](TESTING.md)
- [Provider and Android API notes](API_NOTES.md)

StartEx is not financial advice and provides no profit, liquidity, execution, or recovery guarantee.
