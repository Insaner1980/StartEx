<!-- generated-by: gsd-doc-writer -->

# StartEx project reference

> Source-of-truth snapshot: 2026-08-09. This document describes the checked-in implementation, not an aspirational roadmap. When this document and executable code disagree, the code, generated Room schema, manifest, Gradle configuration, and tests take precedence.

## 1. Purpose of this document

This is the broad project reference for implementation work, code review, security review, UI/UX work, test planning, and release analysis. It intentionally combines product behavior, architecture, persistence, safety rules, visual conventions, provider contracts, build configuration, and known boundaries in one place.

Use the more focused documents for deeper rationale:

- [README.md](README.md): setup, first-run guidance, and operator warnings.
- [ARCHITECTURE.md](ARCHITECTURE.md): data flow and state-machine overview.
- [SECURITY.md](SECURITY.md): threat model, secret handling, and transaction-validation requirements.
- [STRATEGY.md](STRATEGY.md): candidate, risk, Paper, and exit concepts.
- [TESTING.md](TESTING.md): commands, coverage expectations, and verification boundaries.
- [API_NOTES.md](API_NOTES.md): provider and Android API constraints.
- [design-qa.md](design-qa.md): reference-image comparison and visual QA notes.

## 2. Product summary

StartEx is a private, single-user, phone-only Android application for monitoring newly created Solana tokens and simulating very small trades from a dedicated self-custody wallet. It is designed for sideloaded personal use, keeps durable application data on the device, and has no StartEx backend.

The implemented product has three materially different capability areas:

| Area | Current status | Important boundary |
| --- | --- | --- |
| Demo UI | Implemented | Fully local synthetic state; no provider, wallet, service, history, or transaction activity. |
| Paper monitoring and trading | Implemented | Uses live read-only market/provider data but simulates fills; it never signs a swap transaction. |
| Manual SOL transfer | Implemented | A real Solana mainnet transfer can be signed and broadcast after review and authentication. Debug builds are not a sandbox. |
| Manual SPL transfer | Disabled | Both the build flag and production UI keep it unavailable. |
| Automated Live trading | Hard-locked | No production runtime composes Live signing/execution. Both build types set `LIVE_TRADING_BUILD_ENABLED=false`, and service policy accepts only Paper sessions. |
| Future Live safety scaffold | Partially implemented | Parser, validator, reconciliation, and provider execute contracts exist for tests/design work but are not a reachable automated trading path. |

### Product assumptions

- One local operator, one application installation, and one local wallet profile.
- A separate low-balance hot wallet is expected; importing a primary wallet is discouraged.
- Solana mainnet is the only configured cluster.
- The app may reduce risk but cannot guarantee an exit, a route, a fill, token safety, recovery, or profit.
- Paper results are simulations derived from current quotes and configured costs, not evidence that a real transaction would have landed.
- Secrets are device-local. Public addresses, mints, signatures, provider request IDs, and trading evidence are not secrets and may appear in local records or exports.

### Explicitly absent

There is no StartEx server, account system, Firebase, cloud database, cloud sync, analytics SDK, billing, subscription, paid AI, LLM decision engine, remote signer, wallet-adapter approval flow, leverage, shared embedded API key, automatic reboot receiver, or production automated mainnet buy/sell path.

## 3. Current platform and application identity

| Property | Value |
| --- | --- |
| Project name | `StartEx` |
| Gradle modules | One Android application module: `:app` |
| Namespace / release application ID | `com.finnvek.startex` |
| Debug application ID | `com.finnvek.startex.debug` |
| Brand resource | `StartEx`, read from `startex.brandName` |
| Version | `1.0.0` (`versionCode` 1) |
| Minimum Android | API 29 / Android 10 |
| Compile and target SDK | API 37 |
| Java toolchain | JDK 17 |
| UI toolkit | Jetpack Compose with Material 3 |
| Orientation | Portrait only |
| Database | Room database `startex.db`, schema version 1 |
| Preferences | Preferences DataStore named `app_settings` |
| Network stack | One shared OkHttp client plus provider-specific adapters |
| Build outputs | Debug APK and R8/resource-shrunk unsigned release APK unless external signing is configured |

`MainActivity` is the only exported component and only because it is the launcher activity. `TradingMonitorService` is non-exported. The application supports RTL at the manifest level.

## 4. Build system and dependencies

### Gradle configuration

- Gradle wrapper: 9.7.0, with a pinned distribution SHA-256, 10-second wrapper network timeout, zero wrapper retries, and URL validation.
- Android Gradle Plugin: 9.3.1.
- Kotlin: 2.2.10 with the Compose and serialization plugins.
- KSP: 2.3.11.
- Repositories are centralized in `settings.gradle.kts`; project repositories are rejected.
- Repository sources are Google Maven, Maven Central, and the Gradle Plugin Portal where appropriate.
- Gradle JVM heap is 4 GiB. Parallel execution and build cache are enabled.
- AndroidX, non-transitive `R`, and non-final resource IDs are enabled.
- Core library desugaring uses `desugar_jdk_libs` 2.1.5.
- Java and Kotlin JVM targets are 17.
- Compose, `BuildConfig`, and generated resource values are enabled.
- Unit tests include Android resources.
- Room schemas are exported to `app/schemas`.
- Packaged resources exclude `META-INF/AL2.0` and `META-INF/LGPL2.1`.

### Build types and feature locks

- Debug adds `.debug` to the application ID and `-debug` to the version name.
- Release enables code shrinking and resource shrinking using the optimized default ProGuard configuration plus `app/proguard-rules.pro`.
- `LIVE_TRADING_BUILD_ENABLED` is `false` in debug and release.
- `SPL_TRANSFERS_BUILD_ENABLED` is `false` in the default configuration.
- The production Live lock is not dependent only on the build constant: `MonitoringSessionPolicy` also refuses a non-Paper start, and there is no production caller that switches DataStore to `OperatingMode.LIVE`.
- No signing key or signing configuration is stored in the repository.

### Runtime libraries

| Concern | Library and version |
| --- | --- |
| Android core | AndroidX Core KTX 1.19.0 |
| Activity / Compose host | Activity Compose 1.13.0 |
| Lifecycle | Lifecycle runtime/viewmodel Compose 2.11.0 |
| Compose | BOM 2026.06.01, UI, Foundation, Material 3, extended icons, tooling preview |
| Persistence | Room 2.8.4, DataStore 1.2.1 |
| Authentication | AndroidX Biometric 1.1.0 |
| HTTP and WebSocket | OkHttp 5.4.0 |
| JSON | Kotlinx Serialization 1.11.0 |
| Coroutines | Kotlinx Coroutines 1.11.0 |
| QR generation | ZXing Core 3.5.4 |
| QR scanning | Google Play Services Code Scanner 16.1.0 |
| Mnemonics | `cash.z.ecc.android:kotlin-bip39` 1.0.9 |
| Key/signature and basic transaction work | Sol4k 0.6.1 |
| Solana message models | Solana Mobile Web3 Solana 0.3.1 |
| Hardened Solana derivation | Vendored Trust Wallet Core `wallet-core-4.7.3.aar` |

### Test and quality tooling

| Tool | Version / policy |
| --- | --- |
| JUnit | 4.13.2 |
| AndroidX JUnit | 1.3.0 |
| Espresso | 3.7.0 |
| Robolectric | 4.16.1 |
| Roborazzi Compose | 1.71.0 |
| ktlint Gradle plugin | 14.2.0, running ktlint 1.8.0 |
| detekt | 1.23.8, `maxIssues: 0` |

Ktlint excludes generated and build output. Detekt targets JVM 17, emits SARIF and XML, and uses the checked-in configuration. Notable configured thresholds include 140-character lines, 300-line methods, 2,200-line classes, and project-specific complexity/function limits. These thresholds accommodate the current large UI and ViewModel files; they do not make those files automatically low-risk.

## 5. Repository and source map

### Root files

| Path | Responsibility |
| --- | --- |
| `settings.gradle.kts` | Repository policy, Foojay toolchain resolver, root name, and the single module. |
| `build.gradle.kts` | Root plugin aliases. |
| `gradle.properties` | Build performance and application identity properties. |
| `gradle/libs.versions.toml` | Version catalog for all external dependencies/plugins. |
| `config/detekt/detekt.yml` | Static-analysis policy. |
| `.editorconfig` | Kotlin/Compose formatting behavior. |
| `app/build.gradle.kts` | Android variants, feature locks, quality tools, Room schema output, and dependencies. |
| `app/schemas/.../1.json` | Generated Room schema contract for version 1. |
| `app/libs/wallet-core-4.7.3.aar` | Vendored Trust Wallet Core Android artifact. |
| `app/src/main/AndroidManifest.xml` | Permissions, components, backup/network rules, orientation, and foreground-service declaration. |
| `app/src/main/res/values/strings.xml` | User-facing text and accessibility labels. |
| `app/src/main/res/values/styles.xml` | Android window-level dark styling. |
| `app/src/main/res/drawable-nodpi/startex_launcher.png` | Launcher icon. |

The root reference image `ChatGPT Image Aug 9, 2026, 02_21_09 AM.png` is ignored by Git and was used only as visual direction. Runtime values were not copied from it.

### Kotlin package map

| Package / file group | Current responsibility |
| --- | --- |
| `MainActivity.kt` | Secure window, edge-to-edge Compose host, lifecycle forwarding. |
| `StartExApplication.kt` | Process-level dependency composition, shared OkHttp client, notification channels. |
| `SessionApiKeySource.kt` | Mutex-protected in-memory provider-key map using mutable character arrays. |
| `bootstrap/DefaultConfiguration.kt` | Initial immutable strategy and risk rows. |
| `data/local` | Room entities, DAOs, database creation, schema contract. |
| `data/StartExRepository.kt` | Transactional persistence boundary and bounded cleanup/query operations. |
| `data/settings` | DataStore model, defaults, validation, and setters. |
| `data/AnalysisExport.kt` | Public trading-analysis export model. |
| `data/HistoryExporter.kt` | Deterministic JSON and 21-column CSV serialization. |
| `device/DeviceHealth.kt` | Network, battery, charging, thermal, and foreground-service facts plus entry policy. |
| `domain/Amounts.kt` | Exact SOL/lamport, token atomic-unit, and EUR value types/conversions. |
| `domain/CandidateDecision.kt` | Candidate states, hard filters, score factors, and deterministic scoring. |
| `domain/ConfigurationEditor.kt` | User-editable configuration parsing, ranges, relationships, and immutable version creation. |
| `network` | Provider result/error types, bounded transport, Helius, PumpPortal, Jupiter, Kraken, retry/freshness policy. |
| `security` | Android Keystore cipher, authenticated secret envelope, trusted-address policy, wallet-access policy. |
| `service` | Foreground monitoring lifecycle, notifications, session/recovery policy, bounded candidate dispatch. |
| `trading/HardRiskController.kt` | Independent entry limits and circuit-breaker decisions. |
| `trading/PaperCandidateCoordinator.kt` | Serialized observation, filtering, scoring, re-quote, final risk gate, and Paper entry. |
| `trading/PaperExecutionEngine.kt` | Paper latency, slippage, route, and cost simulation. |
| `trading/PaperPositionMonitor.kt` | Full-position sell quotes, exit decisions, bounded retry, and Paper close accounting. |
| `trading/PaperRuntimeSources.kt` | Provider-backed safety proofs, filter thresholds, and runtime risk facts. |
| `trading/PositionExit.kt` | Position state, exit triggers, P&L/costs, and retry policy. |
| `trading/TransactionSafetyValidator.kt` | Future Live transaction-intent validation contract. |
| `trading/SolanaMobileUnsignedTransactionParser.kt` | Legacy/v0 structure parsing with resolver interfaces. |
| `trading/TransactionReconciler.kt` | Generic uncertain-transaction state machine and duplicate-attempt guard. |
| `wallet` | BIP-39 wallet creation/restore, derivation, secret codec, trusted transfer preparation/submission/tracking. |
| `ui/StartExUiState.kt` | Immutable UI state, overlays, transfer states, authentication purposes, and UI events. |
| `ui/StartExViewModel.kt` | Main application orchestration and process-memory secret/wallet ownership. |
| `ui/StartExApp.kt` | Top-level state routing, biometric prompts, service intents, sharing, and bottom navigation. |
| `ui/screens` | Main, onboarding, wallet setup, receive/send, provider, preflight, configuration, and lock screens. |
| `ui/components` / `ui/theme` | Reusable visual components, colors, shapes, and typography. |

## 6. Runtime architecture

StartEx deliberately remains a single Gradle module. Separation is by package and small interfaces rather than module boundaries.

```text
MainActivity / Compose
  -> StartExApp
    -> StartExViewModel
      -> Room repository + DataStore
      -> Android Keystore + in-memory LocalWallet
      -> provider adapters
      -> manual SOL transfer coordinator/tracker
    -> TradingMonitorService intents
      -> PumpPortal discovery
      -> Jupiter/Helius/Kraken enrichment and health
      -> serialized candidate coordinator
      -> hard risk controller
      -> Paper entry + Paper position monitor
      -> Room transactions + notifications
```

### Process-level dependency composition

`StartExApplication` owns lazy instances of the database, repository, settings store, device-health provider, provider adapters, and one `SessionApiKeySource`. The shared OkHttp client has:

- 10-second connect timeout;
- 30-second whole-call timeout;
- 30-second WebSocket ping interval;
- HTTP and HTTPS redirects disabled;
- automatic connection-failure retry disabled.

Provider-specific retry and uncertainty behavior is implemented above OkHttp rather than delegated to implicit client retries.

### UI state flow

Compose renders immutable `PersistedAppState` plus wallet setup, overlay, transfer, message, and configuration-save state. UI callbacks invoke ViewModel methods. The ViewModel owns provider calls, persistence, authentication continuations, runtime wallet material, and service events. Composables do not sign, persist, or call providers directly.

The top-level branch order is significant:

1. loading;
2. wallet setup flow, if active;
3. wallet overlay, if active;
4. provider setup or configuration full-screen panel, if selected;
5. onboarding until complete;
6. secure-session lock screen when a configured wallet is locked;
7. preflight when requested;
8. the five-destination main navigation.

### Lifecycle behavior

- `MainActivity` applies `FLAG_SECURE` before composing UI, preventing ordinary screenshots and recent-task previews.
- The activity uses edge-to-edge layout.
- When the lifecycle reaches `ON_STOP`, a secure-session wallet locks unless a biometric prompt is currently active.
- If authentication finishes after the activity has already moved to the background, the secure-session wallet locks immediately.
- Process restart starts with no authenticated secure-session state.
- Unattended wallet mode may decrypt at startup; secure-session mode cannot.

## 7. Android platform contract

### Permissions

The manifest requests only:

- `INTERNET`;
- `ACCESS_NETWORK_STATE`;
- `POST_NOTIFICATIONS`;
- `FOREGROUND_SERVICE`;
- `FOREGROUND_SERVICE_SPECIAL_USE`.

QR scanning uses Google Code Scanner and does not add an app-owned camera permission.

### Backup and transport policy

- `allowBackup=false`.
- Legacy full backup is disabled.
- Android data-extraction rules exclude root, files, databases, shared preferences, and external storage from cloud backup and device transfer.
- Cleartext traffic is disabled both in the manifest and network-security configuration.
- The app does not implement its own certificate pinning.

### Foreground service declaration

`TradingMonitorService` uses the `specialUse` type with the exact subtype explanation: “User-initiated continuous on-device market monitoring, risk evaluation, and open-position protection”. It is user-started, not boot-started. It is `stopWithTask=false`, so dismissing the task does not by itself stop protection. A future Google Play distribution needs a fresh policy review; this declaration is currently aimed at private sideloading.

### Battery settings

Settings opens Android's battery-optimization settings screen. The app does not request an automatic exemption and holds no permanent wake lock.

## 8. Visual system and UI conventions

### Design direction

The interface is a fixed dark, compact, data-oriented Material 3 UI. It uses near-black backgrounds, slate cards, thin outlines, restrained typography, and green/red/amber/blue semantic accents. Mock values are not used to fill missing runtime data.

### Color tokens

| Token | Hex | Use |
| --- | --- | --- |
| `StartExBackground` | `#090A0C` | App and system-bar background. |
| `StartExSurface` | `#121519` | Primary cards and containers. |
| `StartExSurfaceRaised` | `#191D22` | Elevated/selected surfaces. |
| `StartExOutline` | `#2A3038` | Thin borders and separators. |
| `StartExText` | `#F3F5F7` | Primary text. |
| `StartExMuted` | `#A9B1BB` | Secondary text. |
| `StartExGreen` | `#48D98A` | Healthy, Paper-active, positive, safe action. |
| `StartExRed` | `#FF6B73` | Live warning, loss, destructive/critical state. |
| `StartExAmber` | `#F2B84B` | Attention, degraded, pending state. |
| `StartExBlue` | `#6CA6FF` | Informational/accent state. |

### Typography

| Style | Size / line height | Weight / detail |
| --- | --- | --- |
| `headlineMedium` | 28 / 34 sp | Bold |
| `headlineSmall` | 23 / 30 sp | Bold |
| `titleLarge` | 20 / 26 sp | Semi-bold, tabular numbers |
| `titleMedium` | 16 / 22 sp | Semi-bold, tabular numbers |
| `bodyLarge` | 16 / 24 sp | Normal |
| `bodyMedium` | 14 / 21 sp | Normal |
| `bodySmall` | 12 / 18 sp | Normal |
| `labelLarge` | 14 / 20 sp | Semi-bold |
| `labelMedium` | 12 / 18 sp | Semi-bold |

The theme uses 8, 12, and 16 dp shape radii. `SectionCard` currently uses a 14 dp corner radius, 1 dp outline, and 16 dp internal padding. Standard screen padding is 18 dp horizontal and 16 dp vertical. Settings rows are at least 56 dp high; principal buttons and sensitive text buttons use at least 48 dp touch height.

### Shared UI components

- `ScreenColumn`: standard scrollable screen frame and padding.
- `ScreenHeader`: title/subtitle hierarchy with explicit dark-theme text colors.
- `SectionCard`: outlined surface grouping.
- `StatusPill`: compact semantic status label.
- `SectionHeading`: title plus optional supporting/action content.
- `MetricRow`: aligned label/value presentation.
- `EmptyState`: honest unavailable/no-data message.
- `SettingsRow`: minimum-height tappable settings item.
- `AddressText` and `abbreviateAddress`: monospaced/public-address presentation.

### Navigation

The bottom navigation contains exactly five destinations:

1. Home
2. Watch
3. Wallet
4. History
5. Settings

There is no navigation-framework back stack. Top-level destination and modal/full-screen state are owned by Compose/ViewModel state. Entering Settings refreshes device health. Entering Home refreshes balance when the real wallet is unlocked. Entering Wallet refreshes balance, holdings, and activity when the real wallet is unlocked.

## 9. Screen-by-screen behavior

### Onboarding

Onboarding has nine individually acknowledged pages:

1. purpose and total-loss risk;
2. dedicated local wallet;
3. offline recovery backup;
4. secure-session versus unattended security;
5. Helius, PumpPortal, Jupiter, and keyless Kraken providers;
6. trusted withdrawal address;
7. strategy and risk limits;
8. Paper-first operation;
9. completion checklist.

Each page requires its local acknowledgement before Continue. The final checklist reports incomplete setup honestly, but completion is not blocked by every operational prerequisite. Completing onboarding ensures the default configuration exists and then persists `onboarding_complete=true`.

### Home

Home is the operational dashboard:

- monitor-state and Demo/Paper/Live warning pills;
- recovery card when the latest session needs attention;
- wallet balance and EUR estimate, or explicit unavailable/loading/error text;
- today’s UTC-day performance for the selected mode;
- open Paper positions with current executable sell quote, P&L context, and Sell Now;
- monitor start/preflight, pause, resume, stop, and recovery actions;
- Stop after close when positions are open;
- authenticated Emergency exit for Paper positions.

Monitoring controls are hidden/disabled according to `MonitorState`, Demo mode, provider/preflight state, and open positions. An ordinary Stop while positions are open warns that ongoing position protection will end.

### Watch

Watch has three tabs:

- Candidates: recent candidate records, state, score, source, symbol/name/mint, and a detail dialog with rejection evidence.
- Rejected: the same bounded candidate feed filtered to rejected rows.
- Events: recent redacted app events with severity, category, code, related ID, and time.

Candidates can be searched by symbol, name, or mint. The UI observes up to 250 candidates and 100 recent events.

### Wallet

Without a wallet, the screen offers Create and Restore. With a wallet, it shows:

- the mainnet public address;
- SOL balance, EUR estimate, and observed slot;
- Receive and Send SOL actions;
- standard SPL holdings;
- Token-2022 holdings in a separate section;
- recent address-signature activity;
- trusted addresses;
- recovery-phrase reveal;
- explicit wallet lock.

Holdings and activity depend on an activated Helius key. Metadata is not resolved beyond RPC-provided mint/program/amount facts. Each holdings/activity section is capped at ten displayed rows. Token-2022 assets are visible but excluded from transfer support.

### Receive SOL

Receive creates a 512-pixel QR image from the public address, shows the address and balance, and permits copying or sharing that public address. It explicitly labels the network as mainnet. Clipboard use is limited to public address data; mnemonic/provider secrets are not copy actions.

### Send SOL

Send is limited to a stored trusted address and a stopped or paused monitor with no open position. It shows amount, estimated fee, reserve, total debit, destination, and last-valid block height before authentication. The first transfer to an address requires the last four address characters. Submission and confirmation states are visible, including uncertain, rejected, expired, and finalized outcomes. A Solscan transaction link uses `https://solscan.io/tx/{signature}`.

### Trusted addresses

- Add requires current authentication.
- Labels are trimmed and required; addresses are normalized as Solana addresses.
- QR input accepts a raw address or a `solana:` URI and strips the query component.
- New addresses are locked by default in the UI.
- Changing/deleting a locked entry requires an explicit locked-change confirmation and authentication.
- Unlocking a locked entry requires the last four address characters and authentication.
- An unlocked entry can be deleted after confirmation and authentication.
- First-withdrawal verification is persisted before signing.

### History

History supports mode filters All/Paper/Live and date filters All/Today/7 days, calculated in UTC. It displays bounded trade-analysis rows and aggregates net P&L, fees, and wins/losses. The UI observes at most 250 history rows. JSON and CSV export use Android sharing and are disabled in Demo mode.

### Settings

Settings exposes:

- Demo mode toggle;
- current Paper/Live label (Live remains locked; there is no production mode-switch callback);
- strategy and risk editor;
- provider setup and configured/active count;
- secure-session versus unattended security mode;
- notification status (system-managed; there is no settings toggle in the current UI);
- device-health diagnostics and preflight;
- Android battery-optimization settings;
- local-only data explanation;
- Emergency stop/exit when a session is active.

Retention and display-currency values exist in DataStore but are not currently editable in the production UI.

### Configuration editor

The editor creates new immutable strategy and risk versions; it does not mutate historical rows. Saving is blocked while a monitoring session is active. If open positions exist while monitoring is stopped, saving remains possible but the UI explains that those positions keep their frozen exit rules.

Only 13 fields are exposed:

- risk: maximum trade, maximum exposure, maximum open positions, maximum daily loss, fee/exit reserve, maximum slippage, maximum hold;
- strategy: minimum score, take profit, hard stop, trailing activation, trailing distance, observation time.

All other entity fields carry forward unchanged. Input syntax is unsigned decimal with a dot, no whitespace/sign/exponent, and a maximum of 64 characters. SOL supports at most 9 decimals and percentages at most 2.

### Provider setup

The screen contains credential cards for Helius, PumpPortal, and Jupiter plus a keyless Kraken card. Credential fields use password-style display. Users can save/replace/remove keys. Helius and Jupiter have direct read-only tests. PumpPortal health is established by the monitoring WebSocket rather than a separate screen test.

### Preflight

Preflight reports:

- wallet configured;
- wallet unlocked;
- recovery backup confirmed;
- provider credentials configured/activated;
- latest risk configuration present;
- reserve (required in Live, marked not required in Paper);
- notification state/permission;
- device-health facts.

Start is enabled only for ready Paper operation. The service repeats authoritative checks when starting; the UI checklist is not the only safety boundary.

### Lock screen

Secure-session mode presents Unlock. If monitoring needs attention, the lock screen also exposes authenticated recovery and stop actions. The screen does not imply that background monitoring can sign while the wallet is locked.

### Demo mode

Demo is deterministic local UI state:

- synthetic balance: 0.025 SOL;
- synthetic EUR value: 3.25;
- synthetic slot: 250,000,000;
- one synthetic 12.5-token holding with 6 decimals;
- one synthetic activity item.

Demo supplies no real wallet address, providers, history, sessions, positions, candidates, or events. It blocks wallet/provider/service/transfer/export actions. Enabling it requires stopped monitoring and locks any runtime wallet.

## 10. Wallet lifecycle and secret handling

### Creation and restore

- Wallet creation first requests non-crypto authentication.
- A 24-word BIP-39 mnemonic is generated.
- Solana derivation uses Trust Wallet Core at `m/44'/501'/0'/0'`.
- The phrase is shown on a protected screen with an offline-backup acknowledgement.
- A cryptographically random three-position word challenge must pass.
- The review screen shows the derived public address and derivation path.
- Saving uses a crypto-bound strong-biometric operation in secure-session mode.
- Restore accepts a mnemonic in mutable character storage, validates BIP-39, derives the same path, and enters the same review/save flow.

### Secret envelope

Wallet mnemonic bytes and provider-key bytes are encrypted with AES-256-GCM. The envelope contains:

- format version 1;
- public authenticated metadata (`publicAddress`, or provider envelope identifier);
- a 12-byte IV;
- ciphertext including the 16-byte GCM tag.

Envelope version and public metadata are authenticated as AES-GCM additional authenticated data. Room stores ciphertext and IV, never plaintext. Mutable JVM byte/character arrays controlled by the application are cleared best-effort after use. This cannot prove immediate clearing of immutable strings, library internals, native memory, OS buffers, or a compromised process.

### Keystore modes

| Mode | Alias | Keystore behavior | Runtime behavior |
| --- | --- | --- | --- |
| Secure session | `startex_wallet_secure_v1` | AES-GCM, per-use authentication, `BIOMETRIC_STRONG`, invalidated by biometric enrollment, unlocked device required | Locks on background/process restart; no unattended signing. |
| Unattended | `startex_wallet_unattended_v1` | AES-GCM without user-auth requirement | Wallet can be decrypted after process start; explicitly weaker hot-wallet posture. |

Crypto-bound biometric operations allow only `BIOMETRIC_STRONG`. Non-crypto authentication prompts allow strong biometric or device credential.

### Security-mode change

Changing mode requires:

- stopped monitoring;
- a configured wallet;
- current authentication;
- for unattended mode, acknowledgements for a dedicated low-balance wallet and reduced security;
- positive total-exposure and daily-loss caps;
- maximum open positions in the supported 1–2 range.

The mnemonic is decrypted and re-encrypted under the target Keystore alias. Authentication state is cleared after the mode change. Provider credentials deliberately always use the unattended Keystore alias so network adapters can restore keys after process startup; this is independent of wallet signing mode.

### In-memory ownership

- `LocalWallet` owns a copied 32-byte Ed25519 private seed and rebuilds a Sol4k keypair only for public-key/signing work.
- Runtime seed data is cleared when the wallet is closed or locked.
- `SessionApiKeySource` stores per-provider mutable character arrays behind a mutex, clears replaced/removed values, and returns a String only at the provider boundary.
- Provider keys are rehydrated from encrypted Room rows during ViewModel startup.
- No StartEx log call records secret material; event rows use stable codes and optional redacted messages.

## 11. Provider integrations

All provider calls return typed `ProviderResult.Success` or `ProviderResult.Failure`. Errors distinguish missing key, invalid request/response, unauthorized, rate limit, HTTP/remote failure, network unavailability, closed connection, stale data, rejected execution, and uncertain submission.

### Provider matrix

| Provider | Endpoint / transport | Authentication | Current use |
| --- | --- | --- | --- |
| Helius RPC | `https://mainnet.helius-rpc.com/` JSON-RPC | `api-key` query parameter | Balance, blockhash/height, fees, account info, simulation, status, token holdings, signatures, send transaction. |
| Helius WebSocket | Same Helius host upgraded to WebSocket | `api-key` query parameter | Wallet account-change subscription. |
| PumpPortal | `https://pumpportal.fun/api/data` WebSocket | `api-key` query parameter | New-token and migration discovery only. |
| Jupiter Tokens | `https://api.jup.ag/tokens/v2/search` | `x-api-key` header | Token metadata/audit/stats/liquidity/price snapshots. |
| Jupiter Swap v2 | `https://api.jup.ag/swap/v2` | `x-api-key` header | Read-only order/quote flow in production; execute contract exists but has no production caller. |
| Kraken | `https://api.kraken.com/0/public/OHLC` | None | SOL/EUR conversion. |

### Transport limits and parsing

- HTTP bodies are capped at 2 MiB and rejected before/while reading if the cap is exceeded.
- PumpPortal events and Helius WebSocket messages are capped at 64 KiB of text.
- PumpPortal further caps acknowledgement, event type, signature, address, name, symbol, metadata URI, and decimal-field lengths.
- Helius permits at most 64 simultaneous WebSocket subscriptions; production uses the wallet account subscription.
- Helius signature history defaults to 25 and validates a maximum of 1,000.
- Jupiter token snapshots and Kraken rates default to a 120-second maximum age.
- Parsing is schema-validating and returns typed failure instead of silently accepting missing/invalid critical fields.
- Redirects and implicit OkHttp retries are disabled to keep endpoint and submission behavior explicit.

### Credential and health semantics

Helius, PumpPortal, and Jupiter keys are all required for a new monitoring session. Kraken is keyless. Service start checks that key-backed providers are present in the in-memory source. Helius and Jupiter can be tested before monitoring; PumpPortal becomes healthy only after its WebSocket produces data.

Provider health rows track state, consecutive failures, last success/failure, latency, retry-after, stable failure code, and update time. Ordinary call failures become `DEGRADED`; the fifth consecutive failure becomes `UNAVAILABLE`. Open-position monitoring loss generates an additional risk event.

## 12. Foreground monitoring service

### Commands

The service accepts explicit internal actions for Start, authenticated Recover, Pause, Resume, Sell Now, Emergency exit, Stop after close, and Stop. Sell Now also carries a bounded position ID. Unknown actions move the session to attention state.

### Session states

- `RUNNING`: discovery, entries, and position protection.
- `PROTECTING`: no new candidates admitted; existing positions continue to be monitored until closed.
- `PAUSED`: the foreground service remains available for resume/stop; the active monitor job is not running.
- `STOPPED`: user/normal terminal state.
- `NEEDS_ATTENTION`: recovery, provider, storage, configuration, or internal failure requires operator action.

### Startup and recovery

Before monitoring, the service:

1. loads the latest strategy/risk or the recoverable session’s frozen versions;
2. expires stale sessions using the heartbeat freshness policy;
3. loads retention settings and performs bounded cleanup;
4. refuses start if the candidate cap cannot be made safe;
5. requires Paper mode and configured Helius/PumpPortal/Jupiter keys;
6. validates the risk row;
7. records a new session or evaluates recovery policy;
8. reports provider-health recovery separately from authentication recovery.

Secure-session recovery can require explicit authentication. Unattended recovery may resume monitoring, but it cannot bypass provider/risk checks. A recoverable `PROTECTING` session stays protection-only.

### Concurrent service jobs

One service session supervises:

- PumpPortal discovery;
- a bounded discovery-event consumer;
- a bounded serialized Paper-candidate consumer;
- Paper position monitoring;
- a 30-second session heartbeat;
- a 10-second Helius blockhash health probe;
- Helius wallet-account WebSocket monitoring.

The PumpPortal event channel holds 64 events. The candidate-work channel holds 16 mints. Overflow is explicit: event-buffer overflow stops with attention; candidate-queue overflow rejects the affected candidate with `OBSERVATION_QUEUE_FULL`. Candidate dispatch suppresses concurrent duplicates.

PumpPortal reconnect uses bounded exponential delay and provider `retryAfterMillis` where available. A connection with no sufficiently fresh event becomes stale and reconnects. Helius wallet WebSocket retry starts at one second and caps at 60 seconds.

### Position protection controls

- Sell Now marks a Paper position `EXIT_REQUESTED`; the position monitor performs the actual quote/retry/close decision.
- Stop after close moves the session to `PROTECTING`, stops admitting entries, and stops the service once no open positions remain.
- Emergency exit marks all eligible Paper positions for exit and enters `PROTECTING`.
- Ordinary Stop can end the service even with open positions; the UI warns about the resulting loss of protection.

### Foreground notification

The ongoing private notification shows mode/session state, open-position count, aggregate quote-based P&L where calculable, and age of the latest market success. It offers Pause or Resume as appropriate plus Open. It is refreshed on heartbeats and material session/position/provider events.

## 13. Candidate evaluation pipeline

### Discovery and persistence

PumpPortal `NEW_TOKEN` and migration events become `TokenCandidateEntity` rows before enrichment. Candidate source, signature, creator, display metadata, mint, discovery time, and state are preserved. Candidate insertion is bounded by retention policy and protects active states from trimming.

### Observation

The coordinator is serialized by a mutex and remembers up to 250 completed mints in a process-local bounded LRU-style history. The default strategy requires three observations spanning at least 60 seconds. Supported configuration is 2–20 observations; the interval is the ceiling of minimum-window divided by observation gaps.

For every observation the coordinator obtains:

- a Jupiter token snapshot;
- independent Helius mint-account evidence;
- a fresh WSOL USD price for liquidity conversion;
- a Jupiter buy order for the configured maximum trade amount with no taker;
- a Jupiter full-sell order for the quoted token amount.

A snapshot row is stored even when the pair is incomplete or invalid. A usable quote pair must:

- match WSOL-to-mint buy and mint-to-WSOL full sell amounts;
- contain no unsigned transaction bytes;
- use router `metis`, `jupiterz`, `dflow`, or `okx`;
- use mode `ultra` or `manual`;
- have nonblank provider request IDs;
- stay within slippage, priority-fee, transaction-cost, fee-ratio, and minimum-sell-output limits.

Paper-only quote semantics are explicitly validated; the production path does not pretend quote-only data is a decoded Live transaction.

### Safety proof

The evidence must agree on the candidate mint, use a normalized Solana address, be fresh, and show a non-executable mint account whose owner matches Jupiter’s token-program value. Suspicious activity includes Jupiter’s suspicious flag or active mint/freeze authority. Liquidity USD is converted to lamports using a fresh WSOL USD unit price.

Legacy SPL tokens can pass the extension check. Token-2022 requires a fresh extension proof, but the current Helius adapter does not expose parsed Token-2022 extensions, so production deliberately supplies no proof and fails those candidates closed.

### Hard filter thresholds

| Rule | Current threshold/source |
| --- | --- |
| Creator/developer balance | At most 10% |
| Top holders | At most 30% |
| Holder count | At least 20 |
| Unique organic buyers | At least 10 |
| Buy/sell count ratio | At least 1.1 |
| Buy/sell volume ratio | At least 1.1 |
| Liquidity | At least current strategy minimum; default 5 SOL equivalent |
| Buy price impact | At most risk slippage cap; default 3% |
| Round-trip loss | At most 1,500 bps / 15% |
| Total fee ratio | At most current risk fee cap; default 2,000 bps / 20% |
| Candidate age | Minimum of strategy and risk maximum; default 5 minutes |
| Pre-entry rise | At most current risk cap; default 20% |
| Data age | At most current risk freshness; default 15 seconds |

Additional mandatory checks cover valid mint, supported program/extensions, disabled mint/freeze authorities, provider warnings, buyer growth, organic activity, falling liquidity, buy/sell route presence, provider agreement, allowlisted program semantics, and unsupported route behavior. Rejections use stable enum or pipeline codes and are persisted with the candidate/decision evidence.

### Deterministic score

The domain defines 14 factors:

- buyer growth;
- buy/sell imbalance;
- organic activity;
- holder growth;
- liquidity growth;
- creator concentration;
- top-holder concentration;
- price momentum;
- already-pumped penalty;
- route quality;
- round-trip cost;
- data consistency;
- token age;
- large-sell activity.

Each supplied factor is normalized to 0–1. The score is a weighted 0–100 value rounded half-up. Missing configured factors fail completeness. Hard failures prevent scoring/eligibility; a high score never overrides them.

The default weight JSON uses eight factors totaling 100:

| Factor | Weight |
| --- | ---: |
| Buyer growth | 15 |
| Organic activity | 15 |
| Holder growth | 10 |
| Liquidity growth | 15 |
| Top-holder concentration (`concentration`) | 15 |
| Route quality | 15 |
| Round-trip cost | 10 |
| Data consistency | 5 |

The default minimum score is 75. Decisions store strategy/risk versions, snapshot IDs/times, completeness, factor JSON, score, action, and ordered rejection codes.

## 14. Independent risk layer

Candidate acceptance is followed by `HardRiskController`; UI state and strategy score cannot bypass it. Entry checks cover:

- circuit-breaker state;
- per-trade SOL and EUR;
- total exposure and open-position count;
- rolling 24-hour trade count;
- UTC-day realized loss and fees;
- consecutive losses;
- loss and failed-transaction cooldowns;
- spendable balance after reserve;
- slippage, priority fee, transaction cost, and total fee ratio;
- planned hold time;
- candidate age and pre-entry rise;
- data freshness;
- required provider health.

Daily loss, daily fees, or inadequate provider health can activate a circuit breaker. The breaker blocks new entries but does not disable reconciliation or position exit attempts. Reset requires authenticated recovery and preserves accounting.

### Default risk configuration

| Field | Default |
| --- | ---: |
| Maximum trade | 0.01 SOL |
| Maximum trade display cap | EUR 5.00 |
| Maximum exposure | 0.01 SOL |
| Maximum open positions | 1 |
| Maximum rolling-day trades | 10 |
| Maximum daily realized loss | 0.015 SOL |
| Maximum daily fees | 0.005 SOL |
| Maximum consecutive losses | 2 |
| Loss cooldown | 60 minutes |
| Failed-transaction cooldown | 15 minutes |
| Minimum wallet reserve | 0.005 SOL |
| Maximum slippage | 3% / 300 bps |
| Maximum priority fee | 0.0005 SOL |
| Maximum transaction cost | 0.001 SOL |
| Maximum fee ratio | 20% / 2,000 bps |
| Maximum holding time | 10 minutes |
| Maximum candidate age | 5 minutes |
| Maximum pre-entry rise | 20% |
| Minimum data freshness | 15 seconds |
| Minimum provider health | `HEALTHY` |

### Default strategy/exit configuration

| Field | Default |
| --- | ---: |
| Minimum entry score | 75 |
| Observation window | 60 seconds |
| Maximum candidate age | 5 minutes |
| Minimum liquidity | 5 SOL equivalent |
| Minimum sell output | 0.005 SOL |
| Required snapshots | 3 |
| Take profit | 25% |
| Hard stop | 15% |
| Trailing activation | 15% |
| Trailing distance | 8% |
| Minimum exit safety score | 50 |

### Editable safe ranges

| Input | Allowed range |
| --- | --- |
| Maximum trade | 0.001–1 SOL |
| Maximum exposure | 0.001–2 SOL |
| Open positions | 1–2 |
| Daily loss | 0.001–2 SOL |
| Fee/exit reserve | 0.001–1 SOL |
| Slippage | 0.1–5% |
| Hold | 1–60 minutes |
| Minimum score | 50–100 |
| Take profit | 1–100% |
| Hard stop | 0.5–50% |
| Trailing activation | 1–100% |
| Trailing distance | 0.5–50% |
| Observation | 15–300 seconds |

Cross-field rules require trade ≤ exposure, hard stop ≤ take profit, trailing activation ≤ take profit, trailing distance < trailing activation, and observation ≤ the carried-forward maximum candidate age. Version and timestamp overflow fail validation.

## 15. Paper entry execution

Paper mode uses real read-only quotes but never calls Jupiter execute, signs a swap, or broadcasts one.

After observation, scoring, and initial risk approval:

1. the simulator waits the configured decision-to-submit latency (default two seconds);
2. it refreshes buy and full-sell quotes;
3. refreshed buy costs must exactly equal the initial accepted cost object;
4. quote semantics and all limits are revalidated;
5. execution slippage is applied to the expected token output;
6. a sell quote is requested for the actual simulated received amount;
7. runtime risk facts are fetched again;
8. a shared final-state mutex rechecks the risk decision and persists the fill atomically.

The Paper buy creates, in one repository transaction:

- candidate state `POSITION_OPEN`;
- decision action `PAPER_BUY`;
- position state `OPEN` with frozen exit JSON and strategy:risk version;
- buy intent state `PAPER_FILLED`;
- UTC-day performance fee/trade-count delta.

The idempotency key is `paper:{sessionId}:{mint}:{strategyVersion}:BUY`. Entry cost equals simulated total debit minus requested trade input.

## 16. Position value and Paper exits

Position value uses a fresh executable full-position sell quote, not a spot-price multiplication. Costs model platform/DEX fee, signature/base fee, priority fee, associated-token-account rent, transfer fee where supported, and reclaimed rent. Net P&L compares net sell output against gross entry debit.

### Exit triggers

- take profit;
- hard stop;
- trailing drawdown after activation;
- maximum hold time;
- momentum collapse;
- liquidity collapse, including a quote below half the prior high;
- suspicious creator activity;
- large-holder sell risk;
- safety score below the frozen threshold;
- unsafe token evidence;
- route unavailable;
- Sell Now;
- Emergency exit.

The position stores the exit policy JSON at entry. Later configuration versions do not change an open position’s thresholds.

### Monitor behavior

- The service checks open/requested/blocked Paper positions every two seconds.
- Exit safety requires fresh Jupiter data, a matching non-executable Helius mint account, and the legacy token program; unavailable safety facts fail closed as token unsafe.
- A route/cost-valid quote updates latest/highest/lowest executable values.
- Exit waits two seconds, then makes at most three immediate attempts.
- Initial simulated execution slippage is 100 bps, increasing by 100 bps per retry but never above the risk cap.
- Retry delays start at 250 ms and cap at one second.
- A per-order fee cap is 2,000 bps and all configured priority/transaction-cost caps still apply.
- A successful Paper close atomically persists position `CLOSED`, sell intent `PAPER_FILLED`, and UTC-day P&L/fee/win-loss delta.
- Exhausted attempts persist `EXIT_BLOCKED`; the app never fabricates a close.

## 17. Manual mainnet SOL transfer

Manual SOL sending is a separate wallet feature, not the automated trading pipeline. It is real in debug and release.

### Preconditions

- Demo mode off.
- Monitor `Stopped` or `Paused`.
- No open positions.
- Existing trusted destination selected.
- Positive decimal SOL amount with at most 9 decimals.
- Wallet configured and unlocked.
- Current risk row available.
- No unresolved prior manual transaction; reconciliation is started instead of creating a duplicate.

### Prepare phase

`WalletTransferCoordinator`:

1. normalizes source/destination base58 addresses;
2. rejects an existing destination account if executable or not owned by the system program;
3. obtains a current blockhash and last-valid block height;
4. constructs a legacy System Program transfer with one signature slot;
5. obtains the exact fee for the unsigned message;
6. enforces the configured transaction-cost fee cap;
7. checks balance ≥ amount + fee + reserve using exact overflow-safe arithmetic;
8. simulates the unsigned transaction through Helius;
9. creates a single-use `PreparedSolTransfer` with SHA-256 message idempotency key.

### Authentication, write-ahead, and submission

- The user reviews destination, amount, fee, reserve, total, and validity height.
- First use of a trusted address requires its final four characters.
- A non-crypto authentication prompt authorizes submission.
- The runtime wallet signs the exact prepared legacy message.
- Source signer, 64-byte signature length, and reserialized message equality are verified.
- Before broadcast, Room receives a `BlockchainTransactionEntity` with status `SIGNED_NOT_BROADCAST`, local signature, serialized SHA-256, idempotency key, and last-valid block height.
- Helius `sendTransaction` must return the same signature. A mismatch or ambiguous transport/server result becomes `SUBMISSION_UNCERTAIN`, not success or safe retry.

### Confirmation tracking

The tracker polls every two seconds and progresses monotonically through `SUBMITTED`, `PROCESSED`, `CONFIRMED`, and `FINALIZED`. Chain errors become `CHAIN_REJECTED`. A missing signature only becomes `EXPIRED_UNCONFIRMED` after current block height passes the last-valid height. Provider or persistence failures retain an uncertain result and block a new attempt. Manual-transfer notifications distinguish confirmed, uncertain, and failed states.

## 18. Future Live transaction safety scaffold

The codebase contains reusable contracts for a future Live path, but they are not composed into production automated trading.

`TransactionSafetyValidator` can compare parsed transaction facts with a stored intent and reject:

- decode failure;
- missing expected signer or unexpected app signer;
- input/output mint, recipient, or amount mismatch;
- SOL debit or priority-fee excess;
- unrelated SOL transfer;
- authority change, delegate approval, or unrelated account closure;
- unknown program;
- compute limit/price excess;
- unusable recent blockhash;
- unresolved v0 address lookup tables.

`SolanaMobileUnsignedTransactionParser` separates raw legacy/v0 structure parsing from application-provided resolvers for address lookup tables, controlled signers, blockhash usability, and instruction semantics. Production does not provide the reviewed Jupiter instruction resolver, approved/versioned program allowlist, current sanitized golden fixtures, or full Token-2022 semantics required to unlock Live.

`TransactionReconciler` keeps uncertain attempts non-repeatable until failure/expiry and expected balance effects are proven. This generic scaffold is distinct from the currently connected manual-transfer confirmation tracker.

## 19. Persistence model

Room schema version 1 contains 17 entities.

| Table | Primary data | Relationships / deletion behavior |
| --- | --- | --- |
| `wallet_profiles` | Single profile ID, public address, derivation path, creation and backup-confirmed times | Public address unique. |
| `wallet_secret_envelopes` | Wallet ciphertext, IV, envelope version, Keystore mode, update time | Profile FK, cascade delete. |
| `provider_credentials` | Provider ID, encrypted key, IV, envelope/mode metadata | Independent credential row per provider. |
| `trusted_addresses` | Wallet, label/address, kind, verification, locked and first-transfer state | Profile FK cascade; address unique. |
| `strategy_configs` | Immutable scoring/observation/exit thresholds and weight JSON | Version PK; sessions/decisions restrict deletion. |
| `risk_configs` | Immutable exposure, loss, fee, cooldown, reserve, age/freshness caps | Version PK; sessions/decisions restrict deletion. |
| `bot_sessions` | Mode, status, frozen config versions, start/stop/reason/heartbeat | Strategy/risk FKs restrict deletion. |
| `token_candidates` | Discovery identity/metadata, program, state, score/rejection, timestamps | Mint PK; active/terminal retention policy. |
| `token_snapshots` | Time, liquidity, executable values, holders, route, source | Candidate FK cascade. |
| `decisions` | Session/candidate/config versions, action, score, factors/rejections, time | Session cascade; candidate/config restrict. |
| `positions` | Entry relation, amounts/costs, quotes/extrema, token metadata, frozen exits, state/signatures/reconciliation | Session/candidate/decision restrict. |
| `trade_intents` | Session/position, unique idempotency key, side/mint/amount/cost/request/status | Session restrict; position becomes null on delete. |
| `blockchain_transactions` | Intent, idempotency, signature/hash, validator/result, amounts, state, validity/confirmation/failure | Intent becomes null on delete; signature and serialized hash unique. |
| `fee_records` | Transaction, fee type/mint/amount/lamport equivalent/time | Transaction FK cascade. |
| `daily_performance` | UTC epoch day + mode, P&L/fees/counts/loss streak/circuit breaker | Composite PK `(epochDay, mode)`. |
| `provider_health` | Health state, failure count, success/failure/latency/retry/code timestamps | Provider PK. |
| `app_events` | Severity/category/stable code/redacted message/related ID/time | Bounded chronological operational log. |

### Transactional repository operations

Notable Room transactions include:

- wallet profile and secret envelope saved together;
- strategy and risk versions saved together after checking no `RUNNING`, `PAUSED`, `PROTECTING`, or `NEEDS_ATTENTION` session;
- candidate plus decision persisted together;
- Paper entry candidate/decision/position/intent/performance persisted together;
- Paper exit position/intent/performance persisted together;
- future settlement transaction/fees/position/performance persisted together;
- analysis export snapshot read consistently across related tables.

The repository is the main persistence boundary. Direct DAO use remains in a few orchestration paths for exact lookups, but multi-row state changes use repository transactions.

### Retention

Defaults are seven snapshot days, 1,000 events, and 250 candidates. Supported model limits are 1–365 days, 1–100,000 events, and 25–5,000 candidates. Cleanup removes old snapshots, terminal candidate history, and overflow while preserving active candidate states. If protected rows prevent making room, monitoring stops with a visible storage-cap reason. The current service passes the stored snapshot-day and event limits into `RetentionPolicy` but leaves `maximumCandidates` at its fixed 250 default; `maximumRememberedCandidates` is therefore not operationally applied.

### DataStore preferences

| Setting | Default | Notes |
| --- | --- | --- |
| Onboarding complete | `false` | UI gate. |
| Operating mode | `PAPER` | No production caller currently switches it to Live. |
| Wallet access | secure session | Stored operationally through `unattended_mode`; read model derives secure as its inverse. |
| Demo | `false` | Local synthetic UI only. |
| Notifications | `true` | Modeled and writable in `AppSettingsStore`, but not consumed by the current UI or alert dispatcher. |
| Snapshot retention | 7 days | No current settings UI. |
| Maximum events | 1,000 | No current settings UI. |
| Maximum candidates | 250 | No current settings UI. |
| Display currency | `EUR` | Validated three-letter code; no current settings UI. |

The `secure_session` key is written during mode changes but the read path derives `secureSession` from `unattended_mode`. Review any future preference migration with this asymmetry in mind.

## 20. Export and privacy boundary

History export supports pretty JSON and a deterministic 21-column CSV. The export snapshot includes:

- candidates;
- snapshots;
- decisions;
- positions;
- trade intents;
- blockchain transactions;
- fee records.

It intentionally excludes wallet profile/envelope, provider credentials, trusted-address records, provider health, app events, and daily-performance rows. Public mints, addresses embedded in evidence, transaction signatures, provider request IDs, and frozen rules may be present. Export is therefore non-secret by design but not anonymous.

## 21. Device health and entry policy

The Android provider reports:

- validated network connectivity and transport (`WIFI`, `CELLULAR`, `ETHERNET`, `VPN`, `BLUETOOTH`, `OTHER`, `NONE`);
- battery percentage, low-battery flag, and charging consistency;
- thermal state;
- best-effort foreground-service running state;
- provider RTT and latest-event age supplied by the caller.

New candidate entries fail closed when mandatory device facts are unavailable, the network is disconnected, the battery is critical while not charging, or thermal state is severe/critical/emergency/shutdown. Device health does not close existing positions; position protection continues where possible.

## 22. Notifications and operational events

Five notification channels are created at process startup:

| Channel | Importance | Purpose |
| --- | --- | --- |
| `bot_status` | Low | Ongoing foreground monitor state. |
| `trades` | Default | Entry/exit and manual-transfer lifecycle. |
| `critical_safety` | High | Attention and critical risk events. |
| `provider_health` | Default | Provider degradation/unavailability. |
| `candidate_activity` | Low | Candidate/discovery activity. |

App events persist stable codes rather than raw provider bodies. Notifications use private visibility and immutable PendingIntents. Alert delivery currently checks Android 13+ runtime permission, the system-wide notification setting, a 15-minute per-subject deduplication window, and notification policy. It does not read the DataStore `notificationsEnabled` preference. Candidate-category alerts are explicitly suppressed, so the created candidate channel has no current alert producer. The foreground-service notification remains part of Android service operation.

## 23. State vocabularies worth preserving

### UI monitor state

`Stopped`, `Running`, `Paused`, `Protecting`, `NeedsAttention`.

### Domain candidate state machine

`DISCOVERED`, `OBSERVING`, `REJECTED`, `ELIGIBLE`, `ENTRY_QUEUED`, `ENTRY_SUBMITTED`, `POSITION_OPEN`, `EXIT_QUEUED`, `EXIT_SUBMITTED`, `CLOSED`, `QUARANTINED`, `EXPIRED`, `ROUTE_UNAVAILABLE`, `TRANSACTION_UNCERTAIN`, `FAILED`.

The current Paper coordinator persists only the subset needed by its reachable pipeline. Do not assume every domain transition is used by production service code.

### Domain position states

`ENTRY_PENDING`, `OPEN`, `EXIT_QUEUED`, `EXIT_SUBMITTED`, `EXIT_BLOCKED`, `TRANSACTION_UNCERTAIN`, `CLOSED`, `FAILED`.

Room Paper rows currently use `OPEN`, `EXIT_REQUESTED`, `EXIT_BLOCKED`, and `CLOSED` in the connected monitor path.

### Authentication purposes

Creation, save, unlock, reveal mnemonic, add/delete/unlock trusted address, submit transfer, change security mode, Sell Now, Emergency exit, and monitoring recovery are distinguished so the prompt and continuation match the requested action.

## 24. Testing and verification

### Current test inventory

The checked-in test source contains 234 `@Test` methods under `app/src/test` and 17 under `app/src/androidTest`.

JVM suites cover exact amounts, configuration, candidate decisions, hard risk, Paper candidate/entry/exit/final-state behavior, transaction parsing/validation/reconciliation, provider protocols/parsers/transport/retry, repository configuration safety, retention/export, wallet creation/codec/address/transfer/tracking, UI-state/security/session behavior, notification policy, device health, and Roborazzi UI rendering.

Instrumentation suites cover Room behavior, Compose UI flows, and the Trust Wallet Core Solana derivation vector.

### Standard commands

From PowerShell with JDK 17 and Android SDK 37:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
.\gradlew.bat :app:lint
.\gradlew.bat ktlintCheck
.\gradlew.bat detekt
```

With a suitable emulator or device:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

The full recorded 2026-08-09 verification and its exact worker/cache flags are in [TESTING.md](TESTING.md). Test counts in this document describe the current source inventory; a passing count should only be claimed after running the relevant task.

### Verification boundaries

- JVM tests do not prove hardware-backed Keystore or biometric behavior.
- Compose/Robolectric tests do not prove Pixel 9 rendering, TalkBack, camera scanner, thermal/battery behavior, or long-running foreground-service survival.
- An API 37 compile does not prove runtime on every supported API level.
- Read-only provider fixtures do not prove current third-party API compatibility.
- A quote does not prove a fill.
- No development verification should sign an automated mainnet trade.
- Manual Send SOL is an exception in product behavior and must never be exercised with funds as an incidental test.

## 25. Code-review invariants

Every review should preserve these unless the requested change explicitly alters the product contract:

1. Paper remains the default and automated Live remains unreachable.
2. Demo remains local and side-effect free.
3. Manual SOL is clearly identified as real mainnet behavior.
4. Amount arithmetic stays in integer atomic units; fiat/ratios use `BigDecimal` with explicit rounding.
5. Missing, stale, malformed, contradictory, unhealthy, or over-cap critical data fails closed.
6. Candidate scoring cannot bypass hard filters or the independent risk controller.
7. Risk is rechecked at the final serialized state mutation, not only before latency/network work.
8. Circuit breakers block entries without disabling exits/reconciliation.
9. Open positions retain frozen exit rules and configuration versions.
10. Requested/attempted exits are not recorded as closed without Paper fill or confirmed chain evidence.
11. Ambiguous submission is uncertain, and uncertain work blocks duplicate submission.
12. Signed manual transactions are durably recorded before broadcast.
13. Provider-created future Live transaction bytes are untrusted until fully parsed, resolved, validated, and simulated.
14. Plaintext mnemonic/private key/provider keys do not enter Room, DataStore, logs, events, notifications, clipboard, exports, saved state, or backups.
15. Secure-session wallet access is lost on background/process restart.
16. Unattended mode cannot bypass exposure, daily-loss, reserve, fee, provider, or device-health limits.
17. Provider failures keep stable redacted codes; raw response bodies and keys are not operational logs.
18. Candidate/event storage remains bounded without deleting protected active state.
19. UI unavailable/empty states remain honest and do not invent balances, positions, health, or performance.
20. Foreground-service recovery does not silently start a new configuration over an old recoverable session.

## 26. Review map by change type

### UI or navigation work

Review `StartExApp.kt`, `StartExUiState.kt`, the relevant `ui/screens` file, shared components/theme, strings, and `StartExViewModel.kt`. Check secure top-level routing, back/dismiss behavior, 48 dp targets, large text, unavailable states, Demo behavior, authentication purpose, and whether `FLAG_SECURE` changes screenshot-based QA.

### Wallet/security work

Review `MainActivity.kt`, `StartExViewModel.kt`, `security/*`, `wallet/*`, Room envelope entities/transactions, manifest extraction rules, and security tests. Trace every mutable secret copy and every failure/cancellation branch. Treat biometric success as authorization for one named continuation, not a global boolean.

### Provider work

Review the provider adapter, `ProviderModels.kt`, `HttpTransport.kt`, `SessionApiKeySource.kt`, provider-health persistence, preflight, service retry/freshness behavior, fixture tests, and redaction. Validate current official schemas externally when changing provider behavior.

### Candidate/strategy/risk work

Review discovery persistence, safety proof, quote-pair validation, hard filters, weight mapping, initial and final risk checks, circuit-breaker persistence, and atomic fill. Verify exact units and UTC/rolling-window semantics.

### Position/exit work

Review frozen `exitRulesJson`, full-position quote amount, gross/net/cost definitions, safety failure behavior, retry caps, state persistence, daily performance, notifications, and protection-only service state.

### Manual transfer work

Trace trusted-address authorization, unresolved-transaction gate, destination account ownership, blockhash/fee/balance/simulation, exact message reserialization, write-ahead record, provider ambiguity classification, confirmation monotonicity, expiry, and retry blocking.

### Database work

Update entities, DAOs, database version/migration, exported schema JSON, repository transaction tests, foreign-key behavior, export mapping, retention, and any UI observers together. Never rely only on data-class compilation for a Room change.

### Build/release work

Inspect resolved dependencies, generated merged manifest, debug/release feature constants, R8 output, signing configuration absence, backup/network configuration, and packaged native AAR behavior. A source declaration alone is not release-artifact proof.

## 27. Known current limits and review hotspots

These are implementation facts or boundaries, not promises that an unrelated change should fix them:

- Automated Live execution is not implemented. The Jupiter execute client and generic safety scaffold are test/design boundaries only.
- `LIVE_TRADING_BUILD_ENABLED` is generated but not consumed by production Kotlin; runtime service policy and absence of a mode-switch caller currently provide the effective lock. Review both layers if Live work ever begins.
- Manual SPL sending is disabled. ATA creation, checked-transfer semantics, complete Token-2022 extension validation, and production UI are absent.
- Trading Paper code duplicates a Token-2022 program-ID literal that differs from the Helius/UI literal. Production Token-2022 candidate entry already fails closed because extension evidence is unavailable, but the duplicated constants are a concrete consistency hotspot for future support.
- Provider credential envelopes use the unattended Keystore key even when wallet mode is secure session; this is intentional for process-start provider use but expands the value of a compromised unlocked device/process.
- The ViewModel and main screen files are large orchestration/UI units. Changes need focused call-path review even though detekt thresholds permit their size.
- DataStore writes `secure_session`, but its read model derives secure mode solely as the inverse of `unattended_mode`.
- Retention and display currency are modeled but not user-editable.
- The service does not currently apply the stored `maximumRememberedCandidates` value; its candidate cap remains 250.
- The stored `notificationsEnabled` preference has no production UI setter and is not checked by `AppNotificationDispatcher`; alert delivery is system-permission/settings driven.
- No metadata service supplies friendly token name/icon data beyond current Helius/Jupiter response fields.
- The app has no certificate pinning and relies on Android TLS plus provider HTTPS endpoints.
- Secure-session mode cannot sign an exit after process death/background lock until the user authenticates; stronger secret protection trades off unattended exit capability.
- Android can stop or defer long-running work. There is no boot auto-start and no permanent wake lock.
- The foreground-service `specialUse` declaration needs policy reassessment before Play distribution.
- Trust Wallet Core is vendored, Sol4k is pre-1.0, and Solana Mobile Web3 Core is experimental; dependency updates and native/runtime behavior need explicit review.
- `FLAG_SECURE` intentionally makes ordinary Android screenshots black, so visual regression uses Robolectric/Roborazzi rather than device screenshots.
- Provider keys, system images, Android hardware, and funded accounts are external inputs. Static/build success is not live integration evidence.

## 28. Change discipline

- Prefer the smallest change that satisfies and proves the requested outcome.
- Preserve the single-module architecture unless a concrete need justifies a new build boundary.
- Reuse existing value types, repository transactions, provider result types, UI components, and state models.
- Keep secrets mutable for the shortest practical scope and clear controlled buffers in `finally` blocks.
- Do not add sample financial data to real runtime states.
- Add focused regression coverage for a proven defect; do not broaden refactors around it.
- Preserve unrelated working-tree changes.
- For dependency changes, inspect the resolved graph and release artifact, not only the version catalog.
- For provider changes, use sanitized fixtures and do not run mainnet signing/submission as verification.
- Update this document when a change materially alters product scope, runtime composition, UI behavior, persistent schema, safety invariant, provider contract, or verification command.

## 29. Glossary and units

| Term | Meaning in this project |
| --- | --- |
| Lamport | Integer atomic SOL unit; 1 SOL = 1,000,000,000 lamports. |
| Atomic token amount | Integer base units plus an explicit decimal count. |
| Basis point | 1/100 of one percent; 100 bps = 1%. |
| Paper | Quote-backed simulated execution with no swap signature/broadcast. |
| Live | Automated on-chain trading mode; currently hard-locked/unimplemented. |
| Demo | Synthetic local UI mode with no provider or wallet side effects. |
| Secure session | Wallet secret requires strong biometric Keystore operation and locks on background/restart. |
| Unattended | Explicitly weaker Keystore mode allowing process-start wallet decryption. |
| Protecting | No new entries; continue exit monitoring for open positions. |
| Uncertain | Submission may have reached the network; do not retry until reconciled/expired. |
| Executable quote | Full-amount route quote with costs and bounds, not a spot price. |
| Frozen exit rules | Strategy/risk exit values serialized into the position at entry. |
| Provider health | Persisted success/failure/freshness state used by preflight and risk. |

## 30. Final source-of-truth rule

This reference is intentionally detailed, but it is still secondary to executable evidence. For any review or implementation question, verify the relevant path in this order:

1. current Kotlin/XML/Gradle source;
2. generated Room schema, merged manifest, resolved dependency graph, or built artifact when applicable;
3. focused automated tests and their actual result;
4. device/runtime observation for behavior that static checks cannot prove;
5. this document and the focused project documents for context.

Do not infer that a planned/scaffolded type is production-connected merely because it exists, and do not infer that a successful build proves provider, biometric, hardware, foreground-service, or mainnet behavior.
