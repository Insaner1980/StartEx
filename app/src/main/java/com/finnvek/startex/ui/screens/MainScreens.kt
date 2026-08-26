@file:Suppress("TooManyFunctions")

package com.finnvek.startex.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.finnvek.startex.BuildConfig
import com.finnvek.startex.R
import com.finnvek.startex.bootstrap.DefaultConfiguration
import com.finnvek.startex.data.local.AppEventEntity
import com.finnvek.startex.data.local.DailyPerformanceEntity
import com.finnvek.startex.data.local.PositionEntity
import com.finnvek.startex.data.local.ProviderHealthEntity
import com.finnvek.startex.data.local.RiskConfigEntity
import com.finnvek.startex.data.local.StrategyConfigEntity
import com.finnvek.startex.data.local.TokenCandidateEntity
import com.finnvek.startex.data.local.TradeExportRow
import com.finnvek.startex.data.local.freshSellQuoteLamports
import com.finnvek.startex.device.DeviceHealthEntryPolicy
import com.finnvek.startex.device.DeviceHealthSnapshot
import com.finnvek.startex.device.DeviceThermalStatus
import com.finnvek.startex.device.NetworkTransport
import com.finnvek.startex.domain.ConfigurationErrorCode
import com.finnvek.startex.domain.ConfigurationField
import com.finnvek.startex.domain.ConfigurationFieldError
import com.finnvek.startex.domain.ConfigurationSafeRanges
import com.finnvek.startex.domain.RiskConfigurationInput
import com.finnvek.startex.domain.StrategyConfigurationInput
import com.finnvek.startex.formatUserNumber
import com.finnvek.startex.formatUserTimestamp
import com.finnvek.startex.network.ProviderId
import com.finnvek.startex.ui.ConfigurationSaveState
import com.finnvek.startex.ui.CredentialProviders
import com.finnvek.startex.ui.HistoryScreenState
import com.finnvek.startex.ui.HomeScreenState
import com.finnvek.startex.ui.MonitorState
import com.finnvek.startex.ui.PersistedAppState
import com.finnvek.startex.ui.PreflightState
import com.finnvek.startex.ui.TradingMode
import com.finnvek.startex.ui.WalletActivity
import com.finnvek.startex.ui.WalletScreenState
import com.finnvek.startex.ui.WalletTokenHolding
import com.finnvek.startex.ui.canEnableUnattendedMode
import com.finnvek.startex.ui.canRequestEmergencyExit
import com.finnvek.startex.ui.canRequestSellNow
import com.finnvek.startex.ui.canRequestStopAfterClose
import com.finnvek.startex.ui.components.AddressText
import com.finnvek.startex.ui.components.EmptyState
import com.finnvek.startex.ui.components.MetricRow
import com.finnvek.startex.ui.components.ScreenColumn
import com.finnvek.startex.ui.components.ScreenHeader
import com.finnvek.startex.ui.components.SectionCard
import com.finnvek.startex.ui.components.SectionHeading
import com.finnvek.startex.ui.components.SettingsRow
import com.finnvek.startex.ui.components.StatusPill
import com.finnvek.startex.ui.components.abbreviateAddress
import com.finnvek.startex.ui.historyScreenState
import com.finnvek.startex.ui.homeScreenState
import com.finnvek.startex.ui.theme.StartExAmber
import com.finnvek.startex.ui.theme.StartExGreen
import com.finnvek.startex.ui.theme.StartExOutline
import com.finnvek.startex.ui.theme.StartExRed
import com.finnvek.startex.ui.walletScreenState
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// Public-state adapters intentionally mirror the internal projected-state screen contracts.
// CPD-OFF
@Composable
fun HomeScreen(
    state: PersistedAppState,
    onPreflight: () -> Unit,
    onRecover: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSellNow: (String) -> Unit,
    onEmergencyExit: () -> Unit,
    onStopAfterClose: () -> Unit,
    modifier: Modifier = Modifier,
    preflightFocusRequester: FocusRequester? = null,
) {
    HomeScreen(
        state = state.homeScreenState(),
        onPreflight = onPreflight,
        onRecover = onRecover,
        onPause = onPause,
        onResume = onResume,
        onStop = onStop,
        onSellNow = onSellNow,
        onEmergencyExit = onEmergencyExit,
        onStopAfterClose = onStopAfterClose,
        modifier = modifier,
        preflightFocusRequester = preflightFocusRequester,
    )
}
// CPD-ON

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal fun HomeScreen(
    state: HomeScreenState,
    onPreflight: () -> Unit,
    onRecover: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSellNow: (String) -> Unit,
    onEmergencyExit: () -> Unit,
    onStopAfterClose: () -> Unit,
    modifier: Modifier = Modifier,
    preflightFocusRequester: FocusRequester? = null,
) {
    var quoteNowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.openPositions.isNotEmpty()) {
        while (state.openPositions.isNotEmpty()) {
            delay(1_000)
            quoteNowMillis = System.currentTimeMillis()
        }
    }
    ScreenColumn(modifier = modifier) {
        ScreenHeader(
            title = stringResource(R.string.home_title),
            subtitle = stringResource(R.string.home_subtitle),
            trailing = {
                StatusPill(
                    text =
                        if (state.demoMode) {
                            stringResource(R.string.demo_badge)
                        } else {
                            monitorLabel(state.monitorState)
                        },
                    color = if (state.demoMode) StartExAmber else monitorColor(state.monitorState),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            },
        )
        if (state.demoMode) {
            Spacer(modifier = Modifier.height(14.dp))
            DemoModeNotice()
        }
        StatusPill(
            text =
                stringResource(
                    when {
                        state.demoMode -> R.string.demo_synthetic_label
                        state.mode == TradingMode.Paper -> R.string.paper_label
                        else -> R.string.live_warning
                    },
                ),
            color =
                when {
                    state.demoMode -> StartExAmber
                    state.mode == TradingMode.Paper -> StartExGreen
                    else -> StartExRed
                },
            modifier = Modifier.padding(top = 14.dp),
        )
        if (state.monitorState == MonitorState.NeedsAttention) {
            Spacer(modifier = Modifier.height(14.dp))
            SectionCard(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                SectionHeading(
                    stringResource(R.string.recovery_required_title),
                    subtitle = stringResource(R.string.recovery_required_body),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onRecover,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.resume_recovery))
                    }
                    OutlinedButton(
                        onClick = onStop,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.StopCircle, contentDescription = null)
                        Text(stringResource(R.string.emergency_stop), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        SectionCard {
            SectionHeading(stringResource(R.string.wallet_balance))
            state.walletAddress?.let { address ->
                Spacer(modifier = Modifier.height(4.dp))
                AddressText(address)
            }
            Spacer(modifier = Modifier.height(16.dp))
            MetricRow(
                stringResource(R.string.available_balance),
                when {
                    state.walletBalanceLoading -> stringResource(R.string.balance_loading)
                    state.walletBalanceError != null -> stringResource(R.string.balance_unavailable)
                    else -> balanceSummary(state.walletBalanceLamports, state.walletBalanceEur)
                },
            )
            state.walletBalanceError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(error),
                    style = MaterialTheme.typography.bodySmall,
                    color = StartExRed,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            MetricRow(stringResource(R.string.reserved_balance), stringResource(R.string.balance_unavailable))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.balance_requires_healthy_rpc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        SectionCard {
            SectionHeading(stringResource(R.string.today_performance))
            val today =
                state.dailyPerformance.firstOrNull {
                    it.epochDay == LocalDate.now(ZoneOffset.UTC).toEpochDay() &&
                        it.mode ==
                        when (state.mode) {
                            TradingMode.Paper -> DailyPerformanceEntity.MODE_PAPER
                            TradingMode.Live -> DailyPerformanceEntity.MODE_LIVE
                        }
                }
            Spacer(modifier = Modifier.height(16.dp))
            MetricRow(
                stringResource(R.string.realized_pnl),
                today?.netPnlLamports?.let { formatSignedSol(it) }
                    ?: stringResource(R.string.no_recorded_value),
            )
            Spacer(modifier = Modifier.height(10.dp))
            MetricRow(
                stringResource(R.string.fees_paid),
                today?.totalFeesLamports?.let {
                    stringResource(R.string.sol_balance_value, formatSol(it))
                }
                    ?: stringResource(R.string.no_recorded_value),
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        SectionCard {
            SectionHeading(stringResource(R.string.open_positions))
            if (state.openPositions.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Visibility,
                    title =
                        stringResource(
                            if (state.demoMode) R.string.demo_no_positions_title else R.string.no_positions_title,
                        ),
                    body =
                        stringResource(
                            if (state.demoMode) R.string.demo_no_positions_body else R.string.no_positions_body,
                        ),
                )
            } else {
                state.openPositions.forEachIndexed { index, position ->
                    if (index > 0) HorizontalDivider(color = StartExOutline)
                    Column(modifier = Modifier.padding(vertical = 12.dp)) {
                        val maximumQuoteAgeMillis = state.risk?.minimumDataFreshnessMillis ?: -1
                        val freshQuote =
                            position.freshSellQuoteLamports(
                                nowMillis = quoteNowMillis,
                                maximumAgeMillis = maximumQuoteAgeMillis,
                            )
                        val staleQuote = position.hasStaleSellQuote(quoteNowMillis, maximumQuoteAgeMillis)
                        val quoteAgeSeconds = position.sellQuoteAgeSeconds(quoteNowMillis)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            AddressText(position.mint, modifier = Modifier.weight(1f))
                            StatusPill(
                                text = position.status.replace('_', ' '),
                                color =
                                    when (position.status) {
                                        "OPEN" -> StartExGreen
                                        "EXIT_BLOCKED" -> StartExRed
                                        else -> StartExAmber
                                    },
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        MetricRow(
                            stringResource(R.string.executable_exit_quote),
                            freshQuote?.let {
                                pluralStringResource(
                                    R.plurals.lamport_value,
                                    if (it == 1L) 1 else 2,
                                    it,
                                )
                            }
                                ?: stringResource(
                                    if (staleQuote) R.string.quote_stale else R.string.quote_unavailable,
                                ),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        MetricRow(
                            stringResource(R.string.executable_quote_age),
                            quoteAgeSeconds?.let { marketAgeText(it) }
                                ?: stringResource(R.string.value_unavailable),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        MetricRow(
                            stringResource(R.string.estimated_gross_pnl),
                            freshQuote
                                ?.let { paperGrossPnlLamports(it, position.netInputLamports) }
                                ?.let { formatSignedSol(it) }
                                ?: stringResource(
                                    if (staleQuote) R.string.quote_stale else R.string.no_recorded_value,
                                ),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        val sellNowEnabled = canRequestSellNow(position, state.monitorState, state.demoMode)
                        val sellNowDisabledReason =
                            if (sellNowEnabled) {
                                null
                            } else {
                                stringResource(
                                    homeActionDisabledReason(
                                        demoMode = state.demoMode,
                                        monitorState = state.monitorState,
                                        unavailableReason = R.string.sell_now_unavailable,
                                    ),
                                )
                            }
                        OutlinedButton(
                            onClick = { onSellNow(position.id) },
                            enabled = sellNowEnabled,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StartExRed),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .semantics {
                                        sellNowDisabledReason?.let { stateDescription = it }
                                    },
                        ) {
                            Text(stringResource(R.string.sell_now))
                        }
                    }
                }
                val stopAfterCloseEnabled =
                    canRequestStopAfterClose(
                        state.openPositions,
                        state.monitorState,
                        state.demoMode,
                    )
                val stopAfterCloseDisabledReason =
                    if (stopAfterCloseEnabled) {
                        null
                    } else {
                        stringResource(
                            homeActionDisabledReason(
                                demoMode = state.demoMode,
                                monitorState = state.monitorState,
                                unavailableReason = R.string.stop_after_close_unavailable,
                            ),
                        )
                    }
                OutlinedButton(
                    onClick = onStopAfterClose,
                    enabled = stopAfterCloseEnabled,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .heightIn(min = 48.dp)
                            .semantics {
                                stopAfterCloseDisabledReason?.let { stateDescription = it }
                            },
                ) {
                    Icon(Icons.Outlined.StopCircle, contentDescription = null)
                    Text(stringResource(R.string.stop_after_close), Modifier.padding(start = 8.dp))
                }
                val emergencyExitEnabled =
                    canRequestEmergencyExit(
                        state.openPositions,
                        state.monitorState,
                        state.demoMode,
                    )
                val emergencyExitDisabledReason =
                    if (emergencyExitEnabled) {
                        null
                    } else {
                        stringResource(
                            homeActionDisabledReason(
                                demoMode = state.demoMode,
                                monitorState = state.monitorState,
                                unavailableReason = R.string.emergency_exit_unavailable,
                            ),
                        )
                    }
                OutlinedButton(
                    onClick = onEmergencyExit,
                    enabled = emergencyExitEnabled,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StartExRed),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .heightIn(min = 48.dp)
                            .semantics {
                                emergencyExitDisabledReason?.let { stateDescription = it }
                            },
                ) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                    Text(
                        stringResource(R.string.emergency_exit_and_stop),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        if (state.demoMode) {
            SectionCard {
                SectionHeading(
                    stringResource(R.string.demo_monitoring_disabled),
                    subtitle = stringResource(R.string.demo_monitoring_disabled_body),
                )
            }
        } else {
            when (state.monitorState) {
                MonitorState.Stopped -> {
                    Button(
                        onClick = onPreflight,
                        modifier =
                            (preflightFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.start_preflight))
                    }
                }

                MonitorState.Running -> {
                    Button(
                        onClick = onPause,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.PauseCircle, contentDescription = null)
                        Text(stringResource(R.string.pause_entries), Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = onStop,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.StopCircle, contentDescription = null)
                        Text(stringResource(R.string.stop_monitoring), Modifier.padding(start = 8.dp))
                    }
                }

                MonitorState.Paused -> {
                    // CPD-OFF
                    Button(
                        onClick = onResume,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.PlayCircle, contentDescription = null)
                        Text(stringResource(R.string.resume_entries), Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = onStop,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.StopCircle, contentDescription = null)
                        Text(stringResource(R.string.stop_monitoring), Modifier.padding(start = 8.dp))
                    }
                    // CPD-ON
                }

                MonitorState.NeedsAttention -> {
                    Unit
                }
            }
        }
        Text(
            text = stringResource(R.string.session_limit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 14.dp),
        )
    }
}

internal fun PositionEntity.hasStaleSellQuote(
    nowMillis: Long,
    maximumAgeMillis: Long,
): Boolean {
    if (!routeAvailable || maximumAgeMillis < 0 || latestSellQuoteLamports?.let { it > 0 } != true) return false
    val observedAt = latestSellQuoteAtMillis ?: return false
    val age = runCatching { Math.subtractExact(nowMillis, observedAt) }.getOrNull() ?: return true
    return age !in 0..maximumAgeMillis
}

internal fun PositionEntity.sellQuoteAgeSeconds(nowMillis: Long): Long? {
    if (!routeAvailable || latestSellQuoteLamports?.let { it > 0 } != true) return null
    val observedAt = latestSellQuoteAtMillis ?: return null
    val ageMillis = runCatching { Math.subtractExact(nowMillis, observedAt) }.getOrNull() ?: return null
    return ageMillis.takeIf { it >= 0 }?.div(1_000)
}

internal fun homeActionDisabledReason(
    demoMode: Boolean,
    monitorState: MonitorState,
    unavailableReason: Int,
): Int =
    when {
        demoMode -> R.string.demo_action_unavailable
        monitorState != MonitorState.Running -> R.string.monitoring_must_be_running
        else -> unavailableReason
    }

internal fun paperGrossPnlLamports(
    sellQuoteLamports: Long,
    netInputLamports: Long,
): Long? = runCatching { Math.subtractExact(sellQuoteLamports, netInputLamports) }.getOrNull()

@Composable
fun WatchScreen(
    candidates: List<TokenCandidateEntity>,
    events: List<AppEventEntity>,
    modifier: Modifier = Modifier,
    demoMode: Boolean = false,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCandidateMint by remember { mutableStateOf<String?>(null) }
    val selectedCandidate = selectedCandidateMint?.let { mint -> candidates.firstOrNull { it.mint == mint } }
    val candidateItems = candidates.filter { it.state != "REJECTED" && it.state != "EXPIRED" }
    val rejectedItems = candidates.filter { it.state == "REJECTED" || it.state == "EXPIRED" }
    val filteredCandidates =
        (if (selectedTab == 0) candidateItems else rejectedItems).filter { candidate ->
            query.isBlank() ||
                listOfNotNull(candidate.symbol, candidate.name, candidate.mint)
                    .any { it.contains(query, ignoreCase = true) }
        }
    val tabs =
        listOf(
            stringResource(R.string.watch_candidates_count, candidateItems.size),
            stringResource(R.string.watch_rejected_count, rejectedItems.size),
            stringResource(R.string.watch_events_count, events.size),
        )

    if (demoMode) {
        ScreenColumn(modifier = modifier) {
            ScreenHeader(title = stringResource(R.string.watch_title))
            Spacer(modifier = Modifier.height(14.dp))
            // Demo and persisted-data modes intentionally keep the same navigation controls.
            // CPD-OFF
            DemoModeNotice()
            Spacer(modifier = Modifier.height(14.dp))
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(label) },
                    )
                }
            }
            if (selectedTab != 2) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.search_symbol_or_mint)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                )
            }
            // CPD-ON
            EmptyState(
                icon = if (selectedTab == 2) Icons.Outlined.History else Icons.Outlined.CloudOff,
                title = stringResource(R.string.demo_watch_empty_title),
                body = stringResource(R.string.demo_watch_empty_body),
                modifier = Modifier.padding(vertical = 28.dp),
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            ScreenHeader(title = stringResource(R.string.watch_title))
            Spacer(modifier = Modifier.height(14.dp))
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(label) },
                    )
                }
            }
            if (selectedTab != 2) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.search_symbol_or_mint)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                )
            }
        }
        when {
            selectedTab == 2 && events.isEmpty() -> {
                EmptyState(
                    icon = Icons.Outlined.History,
                    title = stringResource(R.string.events_empty_title),
                    body = stringResource(R.string.events_empty_body),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 28.dp),
                )
            }

            selectedTab == 2 -> {
                // Event and candidate lists intentionally share the screen's list geometry.
                // CPD-OFF
                LazyColumn(
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(
                            start = 18.dp,
                            end = 18.dp,
                            bottom = 20.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(events, key = { it.id }) { event -> EventCard(event) }
                }
                // CPD-ON
            }

            filteredCandidates.isEmpty() -> {
                EmptyState(
                    icon = if (selectedTab == 1) Icons.Outlined.ErrorOutline else Icons.Outlined.CloudOff,
                    title =
                        stringResource(
                            when {
                                query.isNotBlank() -> R.string.search_empty_title
                                selectedTab == 1 -> R.string.watch_rejected_empty_title
                                else -> R.string.watch_empty_title
                            },
                        ),
                    body =
                        stringResource(
                            when {
                                query.isNotBlank() -> R.string.search_empty_body
                                selectedTab == 1 -> R.string.watch_rejected_empty_body
                                else -> R.string.watch_empty_body
                            },
                        ),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 28.dp),
                )
            }

            else -> {
                LazyColumn(
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(
                            start = 18.dp,
                            end = 18.dp,
                            bottom = 20.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filteredCandidates, key = { it.mint }) { candidate ->
                        CandidateCard(candidate, onClick = { selectedCandidateMint = candidate.mint })
                    }
                }
            }
        }
    }

    selectedCandidate?.let { candidate ->
        CandidateDialog(candidate = candidate, onDismiss = { selectedCandidateMint = null })
    }
}

// Public-state adapters intentionally mirror the internal projected-state screen contracts.
// CPD-OFF
@Composable
fun WalletScreen(
    state: PersistedAppState,
    onCreateWallet: () -> Unit,
    onRestoreWallet: () -> Unit,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onTrustedAddresses: () -> Unit,
    onReveal: () -> Unit,
    onLock: () -> Unit,
    onRefreshBalance: () -> Unit,
    modifier: Modifier = Modifier,
    receiveFocusRequester: FocusRequester? = null,
) {
    WalletScreen(
        state = state.walletScreenState(),
        onCreateWallet = onCreateWallet,
        onRestoreWallet = onRestoreWallet,
        onReceive = onReceive,
        onSend = onSend,
        onTrustedAddresses = onTrustedAddresses,
        onReveal = onReveal,
        onLock = onLock,
        onRefreshBalance = onRefreshBalance,
        modifier = modifier,
        receiveFocusRequester = receiveFocusRequester,
    )
}
// CPD-ON

@Composable
internal fun WalletScreen(
    state: WalletScreenState,
    onCreateWallet: () -> Unit,
    onRestoreWallet: () -> Unit,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onTrustedAddresses: () -> Unit,
    onReveal: () -> Unit,
    onLock: () -> Unit,
    onRefreshBalance: () -> Unit,
    modifier: Modifier = Modifier,
    receiveFocusRequester: FocusRequester? = null,
) {
    ScreenColumn(modifier = modifier) {
        ScreenHeader(
            title = stringResource(R.string.wallet_title),
            subtitle = stringResource(R.string.wallet_dedicated),
            trailing = {
                if (state.walletUnlocked) {
                    StatusPill(stringResource(R.string.wallet_status_unlocked), StartExGreen)
                }
            },
        )
        Spacer(modifier = Modifier.height(16.dp))
        val address = state.walletAddress
        if (state.demoMode) {
            DemoModeNotice()
            Spacer(modifier = Modifier.height(14.dp))
            SectionCard {
                SectionHeading(
                    stringResource(R.string.demo_wallet_title),
                    subtitle = stringResource(R.string.demo_wallet_body),
                )
                Spacer(modifier = Modifier.height(14.dp))
                MetricRow(
                    stringResource(R.string.available_balance),
                    balanceSummary(state.walletBalanceLamports, state.walletBalanceEur),
                )
            }
            WalletDataSections(state = state, onRefresh = null)
        } else if (address == null) {
            SectionCard {
                EmptyState(
                    icon = Icons.Outlined.AccountBalanceWallet,
                    title = stringResource(R.string.wallet_not_created),
                    body = stringResource(R.string.wallet_not_created_body),
                )
                Button(
                    onClick = onCreateWallet,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.create_wallet), Modifier.padding(start = 8.dp))
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onRestoreWallet,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Key, contentDescription = null)
                    Text(stringResource(R.string.restore_wallet), Modifier.padding(start = 8.dp))
                }
            }
        } else {
            SectionCard {
                SectionHeading(
                    stringResource(R.string.wallet_address),
                    subtitle = stringResource(R.string.network_mainnet),
                )
                Spacer(modifier = Modifier.height(12.dp))
                AddressText(address, abbreviated = false)
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.available_balance),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = balanceText(state.walletBalanceLamports),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        state.walletBalanceEur?.let { eur ->
                            Text(
                                text = eurBalanceText(eur),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    val refreshBalanceDescription = stringResource(R.string.refresh_balance)
                    IconButton(
                        onClick = onRefreshBalance,
                        enabled = !state.walletBalanceLoading,
                        modifier = Modifier.semantics { contentDescription = refreshBalanceDescription },
                    ) {
                        if (state.walletBalanceLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                            )
                        }
                    }
                }
                state.walletBalanceSlot?.let { slot ->
                    Text(
                        text = stringResource(R.string.balance_slot, slot),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.walletBalanceError?.let { error ->
                    Text(
                        text = stringResource(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = StartExAmber,
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onReceive,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .then(
                                    receiveFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                                ),
                    ) {
                        Icon(Icons.Outlined.ArrowDownward, contentDescription = null)
                        Text(stringResource(R.string.receive), Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = onSend,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = null)
                        Text(stringResource(R.string.send), Modifier.padding(start = 8.dp))
                    }
                }
            }
            WalletDataSections(state = state, onRefresh = onRefreshBalance)
            Spacer(modifier = Modifier.height(14.dp))
            SectionCard {
                SettingsRow(
                    icon = Icons.Outlined.Wallet,
                    title = stringResource(R.string.trusted_addresses),
                    supportingText =
                        pluralStringResource(
                            R.plurals.trusted_address_count,
                            state.trustedAddresses.size,
                            state.trustedAddresses.size,
                        ),
                    onClick = onTrustedAddresses,
                ) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) }
                SettingsRow(
                    icon = Icons.Outlined.Visibility,
                    title = stringResource(R.string.reveal_recovery_phrase),
                    supportingText = stringResource(R.string.authentication_required),
                    onClick = onReveal,
                ) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) }
                SettingsRow(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.lock_wallet_now),
                    supportingText = stringResource(R.string.secure_session_description),
                    onClick = onLock,
                )
            }
        }
    }
}

@Composable
private fun WalletDataSections(
    state: WalletScreenState,
    onRefresh: (() -> Unit)?,
) {
    val standardHoldings = state.tokenHoldings.filterNot { holding -> holding.isToken2022 }
    val displayedHoldings = walletHoldingsForDisplay(state.tokenHoldings)
    val displayedStandardHoldings = displayedHoldings.filterNot { holding -> holding.isToken2022 }
    val displayedToken2022Holdings = displayedHoldings.filter { holding -> holding.isToken2022 }
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(14.dp))
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeading(
                    stringResource(
                        if (state.demoMode) R.string.demo_token_holdings else R.string.token_holdings,
                    ),
                    subtitle =
                        state.tokenHoldingsSlot?.let {
                            stringResource(
                                if (state.demoMode) R.string.demo_synthetic_slot else R.string.balance_slot,
                                it,
                            )
                        },
                )
                if (state.walletDataLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            WalletDataFreshness(state)
            state.walletDataError?.let { error ->
                Text(
                    text = stringResource(error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StartExAmber,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            when {
                state.walletDataLoading && state.tokenHoldings.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.wallet_data_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                state.walletDataError != null && state.tokenHoldings.isEmpty() -> {
                    Unit
                }

                state.tokenHoldings.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Outlined.AccountBalanceWallet,
                        title = stringResource(R.string.token_holdings_empty_title),
                        body = stringResource(R.string.token_holdings_empty_body),
                    )
                }

                else -> {
                    displayedStandardHoldings.forEach { holding ->
                        TokenHoldingRow(holding)
                    }
                    if (displayedToken2022Holdings.isNotEmpty()) {
                        HorizontalDivider(color = StartExOutline, modifier = Modifier.padding(vertical = 10.dp))
                        Text(
                            text = stringResource(R.string.token_2022_caution),
                            style = MaterialTheme.typography.bodyMedium,
                            color = StartExAmber,
                        )
                        displayedToken2022Holdings.forEach { holding ->
                            TokenHoldingRow(holding)
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { Unit },
                enabled =
                    BuildConfig.SPL_TRANSFERS_BUILD_ENABLED &&
                        standardHoldings.isNotEmpty() &&
                        !state.walletDataStale,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.send_spl_token))
            }
            Text(
                text = stringResource(R.string.spl_send_disabled_reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (onRefresh != null) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !state.walletDataLoading,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Text(stringResource(R.string.refresh_wallet_data), Modifier.padding(start = 8.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        SectionCard {
            SectionHeading(
                stringResource(
                    if (state.demoMode) R.string.demo_recent_wallet_activity else R.string.recent_wallet_activity,
                ),
            )
            if (state.walletDataStale) {
                WalletDataFreshness(state)
            }
            when {
                // CPD-OFF
                state.walletDataLoading && state.recentWalletActivity.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.wallet_data_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                // CPD-ON

                state.walletDataError != null && state.recentWalletActivity.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.wallet_activity_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = StartExAmber,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                state.recentWalletActivity.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Outlined.History,
                        title = stringResource(R.string.wallet_activity_empty_title),
                        body = stringResource(R.string.wallet_activity_empty_body),
                    )
                }

                else -> {
                    state.recentWalletActivity
                        .take(WALLET_DATA_DISPLAY_LIMIT)
                        .forEach { activity ->
                            WalletActivityRow(activity, state.walletDataStale, state.demoMode)
                        }
                }
            }
        }
    }
}

internal fun walletHoldingsForDisplay(holdings: List<WalletTokenHolding>): List<WalletTokenHolding> {
    val standard = holdings.filterNot { holding -> holding.isToken2022 }
    val token2022 = holdings.filter { holding -> holding.isToken2022 }
    if (standard.isEmpty()) return token2022.take(WALLET_DATA_DISPLAY_LIMIT)
    if (token2022.isEmpty()) return standard.take(WALLET_DATA_DISPLAY_LIMIT)
    val displayedStandard = standard.take(WALLET_DATA_DISPLAY_LIMIT - 1)
    return displayedStandard + token2022.take(WALLET_DATA_DISPLAY_LIMIT - displayedStandard.size)
}

@Composable
private fun WalletDataFreshness(state: WalletScreenState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (state.demoMode) {
            Text(
                text = stringResource(R.string.demo_wallet_data_fixed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        state.walletDataUpdatedAtMillis?.let { updatedAt ->
            Text(
                text = stringResource(R.string.wallet_data_updated_at, formattedTimestamp(updatedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.walletDataStale) {
            Text(
                text = stringResource(R.string.wallet_data_stale),
                style = MaterialTheme.typography.bodyMedium,
                color = StartExAmber,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun TokenHoldingRow(holding: WalletTokenHolding) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = StartExOutline, modifier = Modifier.padding(vertical = 10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.unknown_token), style = MaterialTheme.typography.titleSmall)
            if (holding.isToken2022) {
                StatusPill(stringResource(R.string.token_2022_badge), StartExAmber)
            }
        }
        AddressText(holding.mint, abbreviated = false)
        Text(
            text =
                stringResource(
                    R.string.token_amount_details,
                    formatUserNumber(
                        value = BigDecimal(holding.amountAtomic, holding.decimals).stripTrailingZeros(),
                        maximumFractionDigits = holding.decimals,
                        locale = LocalConfiguration.current.locales[0],
                    ),
                    holding.amountAtomic.toString(),
                ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = stringResource(R.string.token_metadata_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
// CPD-OFF
private fun WalletActivityRow(
    activity: WalletActivity,
    stale: Boolean,
    demoMode: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = StartExOutline, modifier = Modifier.padding(vertical = 10.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.transaction_signature), style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(6.dp))
            StatusPill(
                text =
                    stringResource(
                        when {
                            demoMode -> R.string.demo_transaction_synthetic
                            activity.failed -> R.string.transaction_failed
                            stale -> R.string.transaction_observed_stale
                            else -> R.string.transaction_observed
                        },
                    ),
                color =
                    when {
                        demoMode -> StartExAmber
                        activity.failed -> StartExRed
                        stale -> StartExAmber
                        else -> StartExGreen
                    },
            )
        }
        AddressText(activity.signature, abbreviated = true)
        Text(
            text =
                stringResource(
                    if (demoMode) R.string.demo_synthetic_slot else R.string.wallet_activity_slot,
                    activity.slot,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        activity.blockTimeMillis?.let { timestamp ->
            Text(
                text = formattedTimestamp(timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
// CPD-ON

internal enum class HistoryModeFilter { All, Paper, Live }

internal enum class HistoryDateFilter { All, Today, SevenDays }

internal data class ActualPerformanceSummary(
    val netPnlLamports: BigInteger,
    val totalFeesLamports: BigInteger,
    val wins: Long,
    val losses: Long,
)

@Composable
fun HistoryScreen(
    state: PersistedAppState,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HistoryScreen(
        state = state.historyScreenState(),
        onExportCsv = onExportCsv,
        onExportJson = onExportJson,
        modifier = modifier,
    )
}

@Composable
internal fun HistoryScreen(
    state: HistoryScreenState,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var modeFilter by rememberSaveable { mutableStateOf(HistoryModeFilter.All) }
    var dateFilter by rememberSaveable { mutableStateOf(HistoryDateFilter.All) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(millisUntilNextUtcDay(nowMillis))
        }
    }
    val trades = filterTradeHistory(state.tradeHistory, modeFilter, dateFilter, nowMillis)
    val performance = summarizeTradeHistory(trades)
    val exportDisabledReason =
        if (state.demoMode) stringResource(R.string.history_export_unavailable_demo) else null

    ScreenColumn(modifier = modifier) {
        ScreenHeader(
            title = stringResource(R.string.history_title),
            subtitle =
                stringResource(
                    if (state.demoMode) R.string.demo_history_subtitle else R.string.history_actual_subtitle,
                ),
        )
        if (state.demoMode) {
            Spacer(modifier = Modifier.height(14.dp))
            DemoModeNotice()
        }
        Spacer(modifier = Modifier.height(14.dp))
        HistoryModeFilters(modeFilter) { modeFilter = it }
        Spacer(modifier = Modifier.height(8.dp))
        HistoryDateFilters(dateFilter) { dateFilter = it }
        Spacer(modifier = Modifier.height(14.dp))
        SectionCard {
            SectionHeading(
                stringResource(R.string.actual_performance_summary),
                subtitle = stringResource(R.string.performance_summary_filtered),
            )
            if (performance == null) {
                Text(
                    text =
                        stringResource(
                            if (state.demoMode) R.string.demo_performance_hidden else R.string.performance_empty,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                MetricRow(
                    stringResource(R.string.net_pnl),
                    formatAtomicSol(performance.netPnlLamports, signed = true),
                )
                MetricRow(
                    stringResource(R.string.fees_paid),
                    formatAtomicSol(performance.totalFeesLamports, signed = false),
                )
                MetricRow(
                    stringResource(R.string.wins_losses),
                    pluralStringResource(
                        R.plurals.wins_losses_value,
                        performance.wins.toInt(),
                        performance.wins,
                        performance.losses,
                    ),
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        SectionHeading(
            stringResource(R.string.actual_trade_history),
            subtitle = pluralStringResource(R.plurals.trade_count, trades.size, trades.size),
        )
        if (trades.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.History,
                title =
                    stringResource(
                        if (state.demoMode) R.string.demo_history_hidden_title else R.string.trade_history_empty_title,
                    ),
                body =
                    stringResource(
                        if (state.demoMode) R.string.demo_history_hidden_body else R.string.trade_history_empty_body,
                    ),
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            trades.take(HISTORY_DISPLAY_LIMIT).forEachIndexed { index, trade ->
                if (index > 0) Spacer(modifier = Modifier.height(10.dp))
                TradeHistoryCard(trade)
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onExportCsv,
                enabled = !state.demoMode,
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics {
                            exportDisabledReason?.let { stateDescription = it }
                        },
            ) {
                Text(stringResource(R.string.export_csv))
            }
            OutlinedButton(
                onClick = onExportJson,
                enabled = !state.demoMode,
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics {
                            exportDisabledReason?.let { stateDescription = it }
                        },
            ) {
                Text(stringResource(R.string.export_json))
            }
        }
        if (exportDisabledReason != null) {
            Text(
                text = exportDisabledReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Text(
            text = stringResource(R.string.export_redaction_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun HistoryModeFilters(
    selected: HistoryModeFilter,
    onSelect: (HistoryModeFilter) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HistoryModeFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = {
                    Text(
                        stringResource(
                            when (filter) {
                                HistoryModeFilter.All -> R.string.filter_all_modes
                                HistoryModeFilter.Paper -> R.string.mode_paper
                                HistoryModeFilter.Live -> R.string.mode_live
                            },
                        ),
                    )
                },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun HistoryDateFilters(
    selected: HistoryDateFilter,
    onSelect: (HistoryDateFilter) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HistoryDateFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = {
                    Text(
                        stringResource(
                            when (filter) {
                                HistoryDateFilter.All -> R.string.filter_all_dates
                                HistoryDateFilter.Today -> R.string.filter_today
                                HistoryDateFilter.SevenDays -> R.string.filter_seven_days
                            },
                        ),
                    )
                },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun TradeHistoryCard(trade: TradeExportRow) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    trade.symbol?.takeIf { symbol -> symbol.isNotBlank() }
                        ?: stringResource(R.string.unknown_token),
                style = MaterialTheme.typography.titleMedium,
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics { heading() },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusPill(
                text = trade.mode,
                color =
                    when (trade.mode) {
                        DailyPerformanceEntity.MODE_PAPER -> StartExGreen
                        DailyPerformanceEntity.MODE_LIVE -> StartExRed
                        else -> StartExAmber
                    },
            )
        }
        AddressText(trade.mint, abbreviated = false)
        Spacer(modifier = Modifier.height(10.dp))
        MetricRow(
            stringResource(R.string.trade_side_status),
            stringResource(
                R.string.trade_side_status_value,
                trade.side,
                trade.transactionStatus ?: stringResource(R.string.status_not_recorded),
            ),
        )
        MetricRow(
            stringResource(R.string.trade_score_strategy),
            stringResource(
                R.string.trade_score_strategy_value,
                trade.entryScore?.toString() ?: stringResource(R.string.value_unavailable),
                trade.strategyVersion?.let { version -> stringResource(R.string.strategy_version_value, version) }
                    ?: stringResource(R.string.value_unavailable),
            ),
        )
        MetricRow(
            stringResource(R.string.trade_exit),
            trade.exitReason ?: stringResource(R.string.status_not_recorded),
        )
        MetricRow(
            stringResource(R.string.trade_time),
            formattedTimestamp(trade.createdAtMillis),
        )
        MetricRow(
            stringResource(R.string.fees_paid),
            stringResource(R.string.sol_balance_value, formatSol(trade.totalFeeLamports)),
        )
    }
}

internal fun filterTradeHistory(
    rows: List<TradeExportRow>,
    modeFilter: HistoryModeFilter,
    dateFilter: HistoryDateFilter,
    nowMillis: Long,
): List<TradeExportRow> {
    val today =
        Instant
            .ofEpochMilli(nowMillis)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .toEpochDay()
    return rows.take(HISTORY_DISPLAY_LIMIT).filter { row ->
        val modeMatches =
            when (modeFilter) {
                HistoryModeFilter.All -> true
                HistoryModeFilter.Paper -> row.mode == DailyPerformanceEntity.MODE_PAPER
                HistoryModeFilter.Live -> row.mode == DailyPerformanceEntity.MODE_LIVE
            }
        modeMatches && dateMatches(row.createdAtMillis.utcEpochDay(), dateFilter, today)
    }
}

internal fun summarizeTradeHistory(rows: List<TradeExportRow>): ActualPerformanceSummary? {
    if (rows.isEmpty()) return null
    var netPnlLamports = BigInteger.ZERO
    var totalFeesLamports = BigInteger.ZERO
    var wins = 0L
    var losses = 0L
    rows.forEach { row ->
        totalFeesLamports += BigInteger.valueOf(row.totalFeeLamports)
        val realizedNetPnl = row.realizedNetPnlLamports() ?: return@forEach
        netPnlLamports += realizedNetPnl
        if (realizedNetPnl.signum() >= 0) wins++ else losses++
    }
    return ActualPerformanceSummary(netPnlLamports, totalFeesLamports, wins, losses)
}

private fun TradeExportRow.realizedNetPnlLamports(): BigInteger? {
    if (side != "SELL" || closedAtMillis == null) return null
    val outputAtomic =
        when (mode) {
            DailyPerformanceEntity.MODE_PAPER -> expectedOutputAtomic
            DailyPerformanceEntity.MODE_LIVE -> actualOutputAtomic ?: return null
            else -> return null
        }
    val inputLamports = grossInputLamports ?: return null
    return outputAtomic.toBigIntegerOrNull()?.subtract(BigInteger.valueOf(inputLamports))
}

private fun dateMatches(
    epochDay: Long,
    filter: HistoryDateFilter,
    today: Long,
): Boolean =
    when (filter) {
        HistoryDateFilter.All -> true
        HistoryDateFilter.Today -> epochDay == today
        HistoryDateFilter.SevenDays -> epochDay in (today - 6)..today
    }

private fun Long.utcEpochDay(): Long =
    Instant
        .ofEpochMilli(this)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .toEpochDay()

internal fun millisUntilNextUtcDay(nowMillis: Long): Long {
    val nextDayStart =
        Instant
            .ofEpochMilli(nowMillis)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    return (nextDayStart - nowMillis).coerceAtLeast(1L)
}

@Composable
internal fun SecurityModeControl(
    state: PersistedAppState,
    onRequestSecurityMode: (Boolean, Boolean, Boolean) -> Unit,
) {
    var showEnableDialog by rememberSaveable { mutableStateOf(false) }
    var showDisableDialog by rememberSaveable { mutableStateOf(false) }
    var dedicatedWalletAcknowledged by rememberSaveable { mutableStateOf(false) }
    var reducedSecurityAcknowledged by rememberSaveable { mutableStateOf(false) }
    val controlsEnabled = !state.demoMode && state.monitorState == MonitorState.Stopped
    val disabledReason =
        if (controlsEnabled) {
            null
        } else {
            stringResource(
                if (state.demoMode) {
                    R.string.demo_action_unavailable
                } else {
                    R.string.security_mode_requires_stopped_monitoring
                },
            )
        }

    Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(
                        selected = !state.unattendedMode,
                        enabled = controlsEnabled,
                        role = Role.RadioButton,
                        onClick = { if (state.unattendedMode) showDisableDialog = true },
                    ).semantics {
                        disabledReason?.let { stateDescription = it }
                    },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = !state.unattendedMode,
                onClick = null,
                enabled = controlsEnabled,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.secure_session), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.secure_session_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = StartExOutline)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(top = 8.dp)
                    .selectable(
                        selected = state.unattendedMode,
                        enabled = controlsEnabled,
                        role = Role.RadioButton,
                        onClick = { if (!state.unattendedMode) showEnableDialog = true },
                    ).semantics {
                        disabledReason?.let { stateDescription = it }
                    },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = state.unattendedMode,
                onClick = null,
                enabled = controlsEnabled,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.unattended_mode), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text =
                        stringResource(
                            if (state.unattendedMode) {
                                R.string.unattended_mode_active
                            } else {
                                R.string.unattended_mode_description
                            },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.unattendedMode) StartExAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text =
                        state.risk?.let { risk ->
                            stringResource(
                                R.string.unattended_caps_summary,
                                formatSol(risk.maximumExposureLamports),
                                formatSol(risk.maximumDailyLossLamports),
                            )
                        } ?: stringResource(R.string.unattended_caps_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (state.unattendedRiskCapsReady) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            StartExAmber
                        },
                )
            }
        }
        if (!controlsEnabled) {
            Text(
                text =
                    stringResource(
                        if (state.demoMode) {
                            R.string.demo_action_unavailable
                        } else {
                            R.string.security_mode_requires_stopped_monitoring
                        },
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (showEnableDialog) {
            val canEnable =
                canEnableUnattendedMode(
                    walletConfigured = state.walletAddress != null,
                    risk = state.risk,
                    monitorState = state.monitorState,
                    dedicatedWalletAcknowledged = dedicatedWalletAcknowledged,
                    reducedSecurityAcknowledged = reducedSecurityAcknowledged,
                )
            AlertDialog(
                onDismissRequest = {
                    showEnableDialog = false
                    dedicatedWalletAcknowledged = false
                    reducedSecurityAcknowledged = false
                },
                modifier = Modifier.verticalScroll(rememberScrollState()),
                title = { Text(stringResource(R.string.enable_unattended_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.enable_unattended_body))
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = dedicatedWalletAcknowledged,
                                        role = Role.Checkbox,
                                        onValueChange = { dedicatedWalletAcknowledged = it },
                                    ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = dedicatedWalletAcknowledged,
                                onCheckedChange = null,
                            )
                            Text(
                                stringResource(R.string.unattended_ack_dedicated_wallet),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = reducedSecurityAcknowledged,
                                        role = Role.Checkbox,
                                        onValueChange = { reducedSecurityAcknowledged = it },
                                    ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = reducedSecurityAcknowledged,
                                onCheckedChange = null,
                            )
                            Text(
                                stringResource(R.string.unattended_ack_reduced_security),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (!state.unattendedRiskCapsReady) {
                            Text(
                                stringResource(R.string.unattended_caps_missing),
                                color = StartExAmber,
                            )
                        }
                    }
                },
                confirmButton = {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = {
                                showEnableDialog = false
                                onRequestSecurityMode(
                                    true,
                                    dedicatedWalletAcknowledged,
                                    reducedSecurityAcknowledged,
                                )
                                dedicatedWalletAcknowledged = false
                                reducedSecurityAcknowledged = false
                            },
                            enabled = canEnable,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.enable_unattended_action))
                        }
                        TextButton(
                            onClick = {
                                showEnableDialog = false
                                dedicatedWalletAcknowledged = false
                                reducedSecurityAcknowledged = false
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                },
            )
        }

        if (showDisableDialog) {
            AlertDialog(
                onDismissRequest = { showDisableDialog = false },
                title = { Text(stringResource(R.string.enable_secure_session_title)) },
                text = { Text(stringResource(R.string.enable_secure_session_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDisableDialog = false
                            onRequestSecurityMode(false, false, false)
                        },
                    ) {
                        Text(stringResource(R.string.enable_secure_session_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDisableDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun DemoModeNotice() {
    SectionCard {
        StatusPill(stringResource(R.string.demo_badge), StartExAmber)
        Text(
            text = stringResource(R.string.demo_mode_notice),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

internal data class ConfigurationInputs(
    val risk: RiskConfigurationInput,
    val strategy: StrategyConfigurationInput,
)

private val configurationInputsSaver =
    listSaver<ConfigurationInputs, String>(
        save = { inputs ->
            listOf(
                inputs.risk.maximumTradeSol,
                inputs.risk.maximumExposureSol,
                inputs.risk.maximumOpenPositions,
                inputs.risk.maximumDailyLossSol,
                inputs.risk.feeReserveSol,
                inputs.risk.maximumSlippagePercent,
                inputs.risk.maximumHoldMinutes,
                inputs.strategy.minimumScore,
                inputs.strategy.takeProfitPercent,
                inputs.strategy.hardStopPercent,
                inputs.strategy.trailingActivationPercent,
                inputs.strategy.trailingDistancePercent,
                inputs.strategy.observationSeconds,
            )
        },
        restore = { values ->
            ConfigurationInputs(
                risk =
                    RiskConfigurationInput(
                        maximumTradeSol = values[0],
                        maximumExposureSol = values[1],
                        maximumOpenPositions = values[2],
                        maximumDailyLossSol = values[3],
                        feeReserveSol = values[4],
                        maximumSlippagePercent = values[5],
                        maximumHoldMinutes = values[6],
                    ),
                strategy =
                    StrategyConfigurationInput(
                        minimumScore = values[7],
                        takeProfitPercent = values[8],
                        hardStopPercent = values[9],
                        trailingActivationPercent = values[10],
                        trailingDistancePercent = values[11],
                        observationSeconds = values[12],
                    ),
            )
        },
    )

@Composable
@Suppress("LongMethod")
fun ConfigurationEditorScreen(
    state: PersistedAppState,
    saveState: ConfigurationSaveState,
    onSave: (RiskConfigurationInput, StrategyConfigurationInput) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val risk = state.risk
    val strategy = state.strategy
    if (risk == null || strategy == null) {
        ScreenColumn(modifier = modifier) {
            ScreenHeader(title = stringResource(R.string.strategy_risk_title))
            EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = stringResource(R.string.configuration_unavailable),
                body = stringResource(R.string.configuration_unavailable_body),
            )
            OutlinedButton(
                onClick = onBack,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.back))
            }
        }
        return
    }
    var inputs by rememberSaveable(risk.version, strategy.version, stateSaver = configurationInputsSaver) {
        mutableStateOf(configurationInputs(risk, strategy))
    }
    val validationError = (saveState as? ConfigurationSaveState.Invalid)?.error
    val invalidField = validationError?.field
    val validationMessage = validationError?.let { configurationValidationMessage(it) }
    val saving = saveState is ConfigurationSaveState.Saving
    val editingEnabled = state.configurationEditingAllowed && !saving

    ScreenColumn(modifier = modifier) {
        ScreenHeader(
            title = stringResource(R.string.strategy_risk_title),
            subtitle = stringResource(R.string.configuration_versions, strategy.version, risk.version),
        )
        Text(
            text = stringResource(R.string.configuration_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
        )
        if (!state.configurationEditingAllowed) {
            SectionCard {
                Text(
                    text = stringResource(R.string.configuration_session_active),
                    color = StartExAmber,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
        } else if (state.openPositions.isNotEmpty()) {
            SectionCard {
                Text(
                    text = stringResource(R.string.frozen_exit_rules_notice),
                    color = StartExAmber,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
        }
        SectionCard {
            SectionHeading(stringResource(R.string.risk_limits_title))
            ConfigurationFieldInput(
                value = inputs.risk.maximumTradeSol,
                enabled = editingEnabled,
                onValueChange = { inputs = inputs.copy(risk = inputs.risk.copy(maximumTradeSol = it)) },
                label = stringResource(R.string.config_max_trade),
                unit = stringResource(R.string.unit_sol),
                range = "${ConfigurationSafeRanges.minimumTradeSol}–${ConfigurationSafeRanges.maximumTradeSol}",
                errorMessage =
                    if (invalidField == ConfigurationField.MAXIMUM_TRADE_SOL) validationMessage else null,
            )
            ConfigurationFieldInput(
                value = inputs.risk.maximumExposureSol,
                enabled = editingEnabled,
                onValueChange = {
                    inputs = inputs.copy(risk = inputs.risk.copy(maximumExposureSol = it))
                },
                label = stringResource(R.string.config_max_exposure),
                unit = stringResource(R.string.unit_sol),
                range =
                    safeRange(
                        ConfigurationSafeRanges.minimumExposureSol,
                        ConfigurationSafeRanges.maximumExposureSol,
                    ),
                errorMessage =
                    if (invalidField == ConfigurationField.MAXIMUM_EXPOSURE_SOL) validationMessage else null,
            )
            ConfigurationFieldInput(
                value = inputs.risk.maximumOpenPositions,
                enabled = editingEnabled,
                onValueChange = {
                    inputs = inputs.copy(risk = inputs.risk.copy(maximumOpenPositions = it))
                },
                label = stringResource(R.string.config_max_positions),
                unit = stringResource(R.string.unit_count),
                range =
                    safeRange(
                        ConfigurationSafeRanges.MINIMUM_OPEN_POSITIONS,
                        ConfigurationSafeRanges.MAXIMUM_OPEN_POSITIONS,
                    ),
                errorMessage =
                    if (invalidField == ConfigurationField.MAXIMUM_OPEN_POSITIONS) validationMessage else null,
                keyboardType = KeyboardType.Number,
            )
            ConfigurationFieldInput(
                value = inputs.risk.maximumDailyLossSol,
                enabled = editingEnabled,
                onValueChange = {
                    inputs = inputs.copy(risk = inputs.risk.copy(maximumDailyLossSol = it))
                },
                label = stringResource(R.string.config_daily_loss),
                unit = stringResource(R.string.unit_sol),
                range =
                    safeRange(
                        ConfigurationSafeRanges.minimumDailyLossSol,
                        ConfigurationSafeRanges.maximumDailyLossSol,
                    ),
                errorMessage =
                    if (invalidField == ConfigurationField.MAXIMUM_DAILY_LOSS_SOL) validationMessage else null,
            )
            ConfigurationFieldInput(
                value = inputs.risk.feeReserveSol,
                enabled = editingEnabled,
                onValueChange = { inputs = inputs.copy(risk = inputs.risk.copy(feeReserveSol = it)) },
                label = stringResource(R.string.config_fee_reserve),
                unit = stringResource(R.string.unit_sol),
                range =
                    safeRange(
                        ConfigurationSafeRanges.minimumFeeReserveSol,
                        ConfigurationSafeRanges.maximumFeeReserveSol,
                    ),
                errorMessage =
                    if (invalidField == ConfigurationField.FEE_RESERVE_SOL) validationMessage else null,
            )
            ConfigurationFieldInput(
                value = inputs.risk.maximumSlippagePercent,
                enabled = editingEnabled,
                onValueChange = {
                    inputs = inputs.copy(risk = inputs.risk.copy(maximumSlippagePercent = it))
                },
                label = stringResource(R.string.config_max_slippage),
                unit = stringResource(R.string.unit_percent),
                range =
                    safeRange(
                        ConfigurationSafeRanges.minimumSlippagePercent,
                        ConfigurationSafeRanges.maximumSlippagePercent,
                    ),
                errorMessage =
                    if (invalidField == ConfigurationField.MAXIMUM_SLIPPAGE_PERCENT) validationMessage else null,
            )
            ConfigurationFieldInput(
                value = inputs.risk.maximumHoldMinutes,
                enabled = editingEnabled,
                onValueChange = { inputs = inputs.copy(risk = inputs.risk.copy(maximumHoldMinutes = it)) },
                label = stringResource(R.string.config_max_hold),
                unit = stringResource(R.string.unit_minutes),
                range =
                    safeRange(
                        ConfigurationSafeRanges.MINIMUM_HOLD_MINUTES,
                        ConfigurationSafeRanges.MAXIMUM_HOLD_MINUTES,
                    ),
                errorMessage =
                    if (invalidField == ConfigurationField.MAXIMUM_HOLD_MINUTES) validationMessage else null,
                keyboardType = KeyboardType.Number,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        SectionCard {
            SectionHeading(stringResource(R.string.strategy_title))
            ConfigurationFieldInput(
                value = inputs.strategy.minimumScore,
                enabled = editingEnabled,
                onValueChange = {
                    inputs = inputs.copy(strategy = inputs.strategy.copy(minimumScore = it))
                },
                label = stringResource(R.string.config_min_score),
                unit = stringResource(R.string.unit_score),
                range =
                    safeRange(
                        ConfigurationSafeRanges.MINIMUM_SCORE,
                        ConfigurationSafeRanges.MAXIMUM_SCORE,
                    ),
                errorMessage =
                    if (invalidField == ConfigurationField.MINIMUM_SCORE) validationMessage else null,
                keyboardType = KeyboardType.Number,
            )
            ConfigurationFieldInput(
                value = inputs.strategy.takeProfitPercent,
                enabled = editingEnabled,
                onValueChange = {
                    inputs = inputs.copy(strategy = inputs.strategy.copy(takeProfitPercent = it))
                },
                label = stringResource(R.string.config_take_profit),
                unit = stringResource(R.string.unit_percent),
                range =
                    safeRange(
                        ConfigurationSafeRanges.minimumTakeProfitPercent,
                        ConfigurationSafeRanges.maximumTakeProfitPercent,
                    ),
                errorMessage =
                    if (invalidField == ConfigurationField.TAKE_PROFIT_PERCENT) validationMessage else null,
            )
            ConfigurationFieldInput(
                value = inputs.strategy.hardStopPercent,
                enabled = editingEnabled,
                onValueChange = {
                    inputs = inputs.copy(strategy = inputs.strategy.copy(hardStopPercent = it))
                },
                label = stringResource(R.string.config_hard_stop),
                unit = stringResource(R.string.unit_percent),
                range =
                    safeRange(
                        ConfigurationSafeRanges.minimumHardStopPercent,
                        ConfigurationSafeRanges.maximumHardStopPercent,
                    ),
                errorMessage =
                    if (invalidField == ConfigurationField.HARD_STOP_PERCENT) validationMessage else null,
            )
            ConfigurationFieldInput(
                value = inputs.strategy.trailingActivationPercent,
                enabled = editingEnabled,
                onValueChange = {
                    inputs = inputs.copy(strategy = inputs.strategy.copy(trailingActivationPercent = it))
                },
                label = stringResource(R.string.config_trailing_activation),
                unit = stringResource(R.string.unit_percent),
                range =
                    safeRange(
                        ConfigurationSafeRanges.minimumTrailingActivationPercent,
                        ConfigurationSafeRanges.maximumTrailingActivationPercent,
                    ),
                errorMessage =
                    if (invalidField == ConfigurationField.TRAILING_ACTIVATION_PERCENT) validationMessage else null,
            )
            ConfigurationFieldInput(
                value = inputs.strategy.trailingDistancePercent,
                enabled = editingEnabled,
                onValueChange = {
                    inputs = inputs.copy(strategy = inputs.strategy.copy(trailingDistancePercent = it))
                },
                label = stringResource(R.string.config_trailing_distance),
                unit = stringResource(R.string.unit_percent),
                range =
                    safeRange(
                        ConfigurationSafeRanges.minimumTrailingDistancePercent,
                        ConfigurationSafeRanges.maximumTrailingDistancePercent,
                    ),
                errorMessage =
                    if (invalidField == ConfigurationField.TRAILING_DISTANCE_PERCENT) validationMessage else null,
            )
            ConfigurationFieldInput(
                value = inputs.strategy.observationSeconds,
                enabled = editingEnabled,
                onValueChange = {
                    inputs = inputs.copy(strategy = inputs.strategy.copy(observationSeconds = it))
                },
                label = stringResource(R.string.config_observation),
                unit = stringResource(R.string.unit_seconds),
                range =
                    safeRange(
                        ConfigurationSafeRanges.MINIMUM_OBSERVATION_SECONDS,
                        ConfigurationSafeRanges.MAXIMUM_OBSERVATION_SECONDS,
                    ),
                errorMessage =
                    if (invalidField == ConfigurationField.OBSERVATION_SECONDS) validationMessage else null,
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            )
        }
        ConfigurationSaveStatus(saveState)
        Spacer(modifier = Modifier.height(14.dp))
        OutlinedButton(
            onClick = { inputs = paperConfigurationInputs() },
            enabled = editingEnabled,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.reset_paper_defaults))
        }
        Button(
            onClick = { onSave(inputs.risk, inputs.strategy) },
            enabled = editingEnabled,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .heightIn(min = 48.dp),
        ) {
            if (saving) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.save_configuration))
            }
        }
        TextButton(
            onClick = onBack,
            enabled = !saving,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.back))
        }
    }
}

@Composable
private fun ConfigurationFieldInput(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    label: String,
    unit: String,
    range: String,
    errorMessage: String?,
    keyboardType: KeyboardType = KeyboardType.Decimal,
    imeAction: ImeAction = ImeAction.Next,
) {
    val focusManager = LocalFocusManager.current
    val errorFocusRequester = remember { FocusRequester() }
    val isError = errorMessage != null
    LaunchedEffect(isError) {
        if (isError) errorFocusRequester.requestFocus()
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(errorFocusRequester)
                .then(
                    if (errorMessage == null) {
                        Modifier
                    } else {
                        Modifier.semantics { error(errorMessage) }
                    },
                ).padding(top = 10.dp),
        label = { Text(label) },
        suffix = { Text(unit) },
        supportingText = {
            Text(errorMessage ?: stringResource(R.string.configuration_safe_range, range, unit))
        },
        enabled = enabled,
        isError = isError,
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.ContentOrLtr),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions =
            KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Next) },
                onDone = { focusManager.clearFocus() },
            ),
    )
}

@Composable
private fun ConfigurationSaveStatus(state: ConfigurationSaveState) {
    when (state) {
        ConfigurationSaveState.Idle -> {
            Unit
        }

        ConfigurationSaveState.Saving -> {
            Text(
                text = stringResource(R.string.configuration_saving),
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        ConfigurationSaveState.Saved -> {
            Text(
                text = stringResource(R.string.configuration_saved),
                color = StartExGreen,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        ConfigurationSaveState.SessionActive -> {
            Text(
                text = stringResource(R.string.configuration_session_active),
                color = StartExAmber,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        ConfigurationSaveState.Failed -> {
            Text(
                text = stringResource(R.string.configuration_save_failed),
                color = StartExRed,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        is ConfigurationSaveState.Invalid -> {
            if (
                state.error.field == ConfigurationField.VERSION ||
                state.error.field == ConfigurationField.CREATED_AT
            ) {
                ConfigurationValidationError(state.error)
            }
        }
    }
}

@Composable
private fun ConfigurationValidationError(error: ConfigurationFieldError) {
    Text(
        text = configurationValidationMessage(error),
        color = StartExRed,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun configurationValidationMessage(error: ConfigurationFieldError): String {
    val field = stringResource(configurationFieldLabel(error.field))
    val reason = stringResource(configurationErrorLabel(error.code))
    val related = error.relatedField?.let { stringResource(configurationFieldLabel(it)) }
    return if (related == null) {
        stringResource(R.string.configuration_invalid_value, field, reason)
    } else {
        stringResource(R.string.configuration_invalid_related, field, reason, related)
    }
}

internal fun configurationInputs(
    risk: RiskConfigEntity,
    strategy: StrategyConfigEntity,
): ConfigurationInputs =
    ConfigurationInputs(
        risk =
            RiskConfigurationInput(
                maximumTradeSol = risk.maximumTradeLamports.lamportsInput(),
                maximumExposureSol = risk.maximumExposureLamports.lamportsInput(),
                maximumOpenPositions = risk.maximumOpenPositions.toString(),
                maximumDailyLossSol = risk.maximumDailyLossLamports.lamportsInput(),
                feeReserveSol = risk.minimumWalletReserveLamports.lamportsInput(),
                maximumSlippagePercent = risk.maximumSlippageBps.bpsInput(),
                maximumHoldMinutes = (risk.maximumHoldingMillis / 60_000L).toString(),
            ),
        strategy =
            StrategyConfigurationInput(
                minimumScore = strategy.minimumEntryScore.toString(),
                takeProfitPercent = strategy.takeProfitBps.bpsInput(),
                hardStopPercent = strategy.hardStopLossBps.bpsInput(),
                trailingActivationPercent = strategy.trailingActivationBps.bpsInput(),
                trailingDistancePercent = strategy.trailingDistanceBps.bpsInput(),
                observationSeconds = (strategy.minimumObservationMillis / 1_000L).toString(),
            ),
    )

internal fun paperConfigurationInputs(): ConfigurationInputs =
    configurationInputs(
        DefaultConfiguration.risk(createdAtMillis = 0),
        DefaultConfiguration.strategy(createdAtMillis = 0),
    )

private fun Long.lamportsInput(): String =
    BigDecimal
        .valueOf(this)
        .movePointLeft(9)
        .stripTrailingZeros()
        .toPlainString()

private fun Int.bpsInput(): String =
    BigDecimal
        .valueOf(toLong())
        .movePointLeft(2)
        .stripTrailingZeros()
        .toPlainString()

private fun safeRange(
    minimum: Any,
    maximum: Any,
): String = "$minimum–$maximum"

private fun configurationFieldLabel(field: ConfigurationField): Int =
    when (field) {
        ConfigurationField.MAXIMUM_TRADE_SOL -> R.string.config_max_trade
        ConfigurationField.MAXIMUM_EXPOSURE_SOL -> R.string.config_max_exposure
        ConfigurationField.MAXIMUM_OPEN_POSITIONS -> R.string.config_max_positions
        ConfigurationField.MAXIMUM_DAILY_LOSS_SOL -> R.string.config_daily_loss
        ConfigurationField.FEE_RESERVE_SOL -> R.string.config_fee_reserve
        ConfigurationField.MAXIMUM_SLIPPAGE_PERCENT -> R.string.config_max_slippage
        ConfigurationField.MAXIMUM_HOLD_MINUTES -> R.string.config_max_hold
        ConfigurationField.MINIMUM_SCORE -> R.string.config_min_score
        ConfigurationField.TAKE_PROFIT_PERCENT -> R.string.config_take_profit
        ConfigurationField.HARD_STOP_PERCENT -> R.string.config_hard_stop
        ConfigurationField.TRAILING_ACTIVATION_PERCENT -> R.string.config_trailing_activation
        ConfigurationField.TRAILING_DISTANCE_PERCENT -> R.string.config_trailing_distance
        ConfigurationField.OBSERVATION_SECONDS -> R.string.config_observation
        ConfigurationField.VERSION, ConfigurationField.CREATED_AT -> R.string.configuration_internal_field
    }

private fun configurationErrorLabel(code: ConfigurationErrorCode): Int =
    when (code) {
        ConfigurationErrorCode.REQUIRED -> R.string.configuration_error_required
        ConfigurationErrorCode.INVALID_FORMAT -> R.string.configuration_error_format
        ConfigurationErrorCode.TOO_PRECISE -> R.string.configuration_error_precision
        ConfigurationErrorCode.OUT_OF_RANGE -> R.string.configuration_error_range
        ConfigurationErrorCode.INCONSISTENT -> R.string.configuration_error_inconsistent
        ConfigurationErrorCode.OVERFLOW -> R.string.configuration_error_overflow
    }

@Composable
@Suppress("LongParameterList")
fun SettingsScreen(
    state: PersistedAppState,
    onProviders: () -> Unit,
    onPreflight: () -> Unit,
    onStop: () -> Unit,
    onSetDemoMode: (Boolean) -> Unit,
    onRequestSecurityMode: (Boolean, Boolean, Boolean) -> Unit,
    onStrategyAndRisk: () -> Unit,
    onBatteryOptimizationSettings: () -> Unit,
    modifier: Modifier = Modifier,
    notificationsAllowed: Boolean = false,
    providersFocusRequester: FocusRequester? = null,
    strategyFocusRequester: FocusRequester? = null,
    healthFocusRequester: FocusRequester? = null,
) {
    val demoDisabledReason = if (state.demoMode) stringResource(R.string.demo_action_unavailable) else null
    ScreenColumn(modifier = modifier) {
        ScreenHeader(title = stringResource(R.string.settings_title))
        if (state.demoMode) {
            Spacer(modifier = Modifier.height(14.dp))
            DemoModeNotice()
        }
        Spacer(modifier = Modifier.height(16.dp))
        SectionCard {
            SettingsRow(
                icon = Icons.Outlined.Visibility,
                title = stringResource(R.string.demo_mode),
                supportingText =
                    stringResource(
                        if (!state.demoMode && state.monitorState != MonitorState.Stopped) {
                            R.string.demo_requires_stopped_monitoring
                        } else {
                            R.string.demo_mode_settings_body
                        },
                    ),
                onClick = { onSetDemoMode(!state.demoMode) },
                enabled = state.demoMode || state.monitorState == MonitorState.Stopped,
                disabledReason =
                    if (!state.demoMode && state.monitorState != MonitorState.Stopped) {
                        stringResource(R.string.demo_requires_stopped_monitoring)
                    } else {
                        null
                    },
                toggleState = state.demoMode,
            ) {
                Switch(
                    checked = state.demoMode,
                    onCheckedChange = null,
                    enabled = state.demoMode || state.monitorState == MonitorState.Stopped,
                )
            }
            SettingsRow(
                icon = Icons.Outlined.Policy,
                title = stringResource(R.string.settings_mode),
                supportingText =
                    stringResource(
                        if (state.mode == TradingMode.Paper) R.string.paper_default else R.string.live_locked,
                    ),
            ) {
                StatusPill(
                    text =
                        stringResource(
                            if (state.mode == TradingMode.Paper) R.string.mode_paper else R.string.mode_live,
                        ),
                    color = if (state.mode == TradingMode.Paper) StartExGreen else StartExRed,
                )
            }
            SettingsRow(
                icon = Icons.Outlined.Tune,
                title = stringResource(R.string.settings_strategy),
                supportingText =
                    state.risk?.let {
                        pluralStringResource(
                            R.plurals.risk_summary,
                            it.maximumOpenPositions,
                            it.maximumOpenPositions,
                            it.maximumTradesPerDay,
                        )
                    } ?: stringResource(R.string.status_action_required),
                onClick = onStrategyAndRisk,
                enabled = !state.demoMode,
                disabledReason = demoDisabledReason,
                focusRequester = strategyFocusRequester,
            ) {
                if (!state.demoMode) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                }
            }
            SettingsRow(
                icon = Icons.Outlined.SettingsEthernet,
                title = stringResource(R.string.settings_providers),
                supportingText =
                    pluralStringResource(
                        R.plurals.provider_stored_active_count,
                        state.configuredProviders.size,
                        state.configuredProviders.size,
                        state.activatedProviders.size,
                        CredentialProviders.size,
                    ),
                onClick = onProviders,
                enabled = !state.demoMode,
                disabledReason = demoDisabledReason,
                focusRequester = providersFocusRequester,
            ) {
                StatusPill(
                    text =
                        stringResource(
                            when {
                                state.providersHealthy -> R.string.provider_healthy
                                state.providersReadyForStart -> R.string.provider_ready_to_connect
                                else -> R.string.status_action_required
                            },
                        ),
                    color = if (state.providersHealthy) StartExGreen else StartExAmber,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            SettingsRow(
                icon = Icons.Outlined.Shield,
                title = stringResource(R.string.settings_security),
                supportingText =
                    stringResource(
                        if (state.unattendedMode) {
                            R.string.unattended_mode_active
                        } else {
                            R.string.secure_session_active
                        },
                    ),
            ) { Icon(Icons.Outlined.Lock, contentDescription = null) }
            SecurityModeControl(
                state = state,
                onRequestSecurityMode = onRequestSecurityMode,
            )
            SettingsRow(
                icon = Icons.Outlined.Notifications,
                title = stringResource(R.string.settings_notifications),
                supportingText = stringResource(R.string.notifications_system_managed),
            ) {
                StatusPill(
                    text =
                        stringResource(
                            if (notificationsAllowed) R.string.status_ready else R.string.status_action_required,
                        ),
                    color = if (notificationsAllowed) StartExGreen else StartExAmber,
                    modifier =
                        Modifier
                            .testTag("settings_notification_status")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            SettingsRow(
                icon = Icons.Outlined.BatteryChargingFull,
                title = stringResource(R.string.settings_health),
                supportingText = stringResource(R.string.session_limit_short),
                onClick = onPreflight,
                enabled = !state.demoMode,
                disabledReason = demoDisabledReason,
                focusRequester = healthFocusRequester,
            ) {
                if (!state.demoMode) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                }
            }
            SettingsRow(
                icon = Icons.Outlined.BatteryChargingFull,
                title = stringResource(R.string.battery_optimization_title),
                supportingText = stringResource(R.string.battery_optimization_body),
                onClick = onBatteryOptimizationSettings,
            ) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) }
            SettingsRow(
                icon = Icons.Outlined.DataObject,
                title = stringResource(R.string.settings_data),
                supportingText = stringResource(R.string.local_only_data),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        DeviceHealthDiagnosticsCard(state.deviceHealth)
        Spacer(modifier = Modifier.height(16.dp))
        SectionCard {
            SectionHeading(
                stringResource(R.string.live_locked),
                subtitle = stringResource(R.string.live_locked_body),
            )
        }
        if (state.monitorState != MonitorState.Stopped) {
            // CPD-OFF
            Spacer(modifier = Modifier.height(16.dp))
            SectionCard {
                SectionHeading(
                    stringResource(R.string.emergency_controls),
                    subtitle = stringResource(R.string.emergency_controls_body),
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onStop,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.StopCircle, contentDescription = null)
                    Text(stringResource(R.string.emergency_stop), Modifier.padding(start = 8.dp))
                }
            }
            // CPD-ON
        }
    }
}

@Composable
fun PreflightScreen(
    state: PreflightState,
    mode: TradingMode,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onRequestNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val title = stringResource(R.string.preflight_title)
    val blockingChecks =
        buildList {
            if (!state.walletReady) add(stringResource(R.string.check_wallet))
            if (!state.backupVerified) add(stringResource(R.string.check_backup))
            if (!state.providersHealthy) add(stringResource(R.string.check_providers))
            if (!state.limitsConfigured) add(stringResource(R.string.check_limits))
            if (state.reserveRequired && !state.reserveReady) add(stringResource(R.string.check_reserve))
            if (!state.notificationsAllowed) add(stringResource(R.string.check_notifications))
            if (!state.deviceHealthReady) add(stringResource(R.string.check_device_health))
        }
    val checklistBlockedReason =
        blockingChecks.takeIf { it.isNotEmpty() }?.let {
            stringResource(R.string.preflight_blocked, it.joinToString(separator = "; "))
        }
    val liveBlockedReason =
        if (mode == TradingMode.Live) stringResource(R.string.live_locked_body) else null
    val startDisabledReason = listOfNotNull(liveBlockedReason, checklistBlockedReason).joinToString(" ")

    ScreenColumn(modifier = modifier.semantics { paneTitle = title }) {
        ScreenHeader(title = title)
        Text(
            text = stringResource(R.string.preflight_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        StatusPill(
            text =
                stringResource(
                    if (mode == TradingMode.Paper) R.string.paper_label else R.string.live_warning,
                ),
            color = if (mode == TradingMode.Paper) StartExGreen else StartExRed,
        )
        Spacer(modifier = Modifier.height(14.dp))
        SectionCard {
            PreflightRow(
                stringResource(R.string.check_wallet),
                state.walletReady,
                stringResource(R.string.preflight_wallet_required),
            )
            PreflightRow(
                stringResource(R.string.check_backup),
                state.backupVerified,
                stringResource(R.string.preflight_backup_required),
            )
            if (state.providersHealthy && !state.pumpHealthCheckedOnStart) {
                PreflightReadyToConnectRow(stringResource(R.string.check_providers))
            } else {
                PreflightRow(
                    stringResource(R.string.check_providers),
                    state.providersHealthy,
                    stringResource(R.string.preflight_providers_required),
                )
            }
            PreflightRow(
                stringResource(R.string.check_limits),
                state.limitsConfigured,
                stringResource(R.string.preflight_limits_required),
            )
            if (state.reserveRequired) {
                PreflightRow(
                    stringResource(R.string.check_reserve),
                    state.reserveReady,
                    stringResource(R.string.preflight_reserve_required),
                )
            } else {
                PreflightNotRequiredRow(stringResource(R.string.check_reserve))
            }
            PreflightRow(
                stringResource(R.string.check_notifications),
                state.notificationsAllowed,
                stringResource(R.string.preflight_notifications_required),
            )
            PreflightRow(
                stringResource(R.string.check_device_health),
                state.deviceHealthReady,
                stringResource(R.string.preflight_device_health_required),
            )
        }
        Spacer(modifier = Modifier.height(22.dp))
        if (startDisabledReason.isNotEmpty()) {
            Text(
                text = startDisabledReason,
                color = StartExAmber,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        if (!state.notificationsAllowed) {
            OutlinedButton(
                onClick = onRequestNotifications,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.Notifications, contentDescription = null)
                Text(stringResource(R.string.allow_notifications), Modifier.padding(start = 8.dp))
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        Button(
            onClick = onStart,
            enabled = state.isReady && mode == TradingMode.Paper,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics {
                        if (startDisabledReason.isNotEmpty()) {
                            stateDescription = startDisabledReason
                        }
                    },
        ) {
            Text(stringResource(R.string.start_paper_monitoring))
        }
        OutlinedButton(
            onClick = onBack,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.back))
        }
    }
}

@Composable
private fun DeviceHealthDiagnosticsCard(snapshot: DeviceHealthSnapshot?) {
    val unavailable = stringResource(R.string.value_unavailable)
    val healthReady = snapshot?.let { DeviceHealthEntryPolicy.blockReason(it) == null } == true
    SectionCard {
        SectionHeading(
            stringResource(R.string.device_health_diagnostics_title),
            subtitle = stringResource(R.string.device_health_diagnostics_body),
        )
        Spacer(modifier = Modifier.height(12.dp))
        MetricRow(
            stringResource(R.string.device_health_network),
            snapshot?.networkValue(unavailable) ?: unavailable,
        )
        Spacer(modifier = Modifier.height(8.dp))
        MetricRow(
            stringResource(R.string.device_health_battery),
            snapshot?.batteryValue(unavailable) ?: unavailable,
        )
        Spacer(modifier = Modifier.height(8.dp))
        MetricRow(
            stringResource(R.string.device_health_thermal),
            snapshot?.thermalStatus?.displayValue() ?: unavailable,
        )
        Spacer(modifier = Modifier.height(8.dp))
        MetricRow(
            stringResource(R.string.device_health_foreground_service),
            when (snapshot?.foregroundServiceRunning) {
                true -> stringResource(R.string.status_running)
                false -> stringResource(R.string.status_stopped)
                null -> unavailable
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
        MetricRow(
            stringResource(R.string.device_health_provider_rtt),
            snapshot?.providerRttMillis?.let {
                stringResource(R.string.device_health_milliseconds, it)
            } ?: unavailable,
        )
        Spacer(modifier = Modifier.height(8.dp))
        MetricRow(
            stringResource(R.string.device_health_last_event_age),
            snapshot?.lastEventAgeMillis?.let {
                stringResource(R.string.device_health_milliseconds_ago, it)
            } ?: unavailable,
        )
        Spacer(modifier = Modifier.height(12.dp))
        StatusPill(
            text =
                stringResource(
                    if (healthReady) R.string.status_ready else R.string.status_action_required,
                ),
            color = if (healthReady) StartExGreen else StartExAmber,
        )
    }
}

@Composable
private fun DeviceHealthSnapshot.networkValue(unavailable: String): String {
    val connection =
        when (networkConnected) {
            true -> stringResource(R.string.device_health_connected)
            false -> stringResource(R.string.device_health_disconnected)
            null -> unavailable
        }
    val transport =
        when (networkTransport) {
            NetworkTransport.NONE -> stringResource(R.string.device_health_transport_none)
            NetworkTransport.WIFI -> stringResource(R.string.device_health_transport_wifi)
            NetworkTransport.CELLULAR -> stringResource(R.string.device_health_transport_cellular)
            NetworkTransport.ETHERNET -> stringResource(R.string.device_health_transport_ethernet)
            NetworkTransport.VPN -> stringResource(R.string.device_health_transport_vpn)
            NetworkTransport.BLUETOOTH -> stringResource(R.string.device_health_transport_bluetooth)
            NetworkTransport.OTHER -> stringResource(R.string.device_health_transport_other)
            null -> unavailable
        }
    return stringResource(R.string.device_health_network_value, connection, transport)
}

@Composable
private fun DeviceHealthSnapshot.batteryValue(unavailable: String): String {
    val level =
        batteryLevelPercent?.let {
            stringResource(R.string.device_health_battery_percent, it)
        } ?: unavailable
    val charge =
        when (charging) {
            true -> stringResource(R.string.device_health_charging)
            false -> stringResource(R.string.device_health_not_charging)
            null -> unavailable
        }
    val critical =
        when (batteryCritical) {
            true -> stringResource(R.string.device_health_battery_critical)
            false -> stringResource(R.string.device_health_battery_normal)
            null -> unavailable
        }
    return stringResource(R.string.device_health_battery_value, level, charge, critical)
}

@Composable
private fun DeviceThermalStatus.displayValue(): String =
    stringResource(
        when (this) {
            DeviceThermalStatus.NONE -> R.string.device_health_thermal_none
            DeviceThermalStatus.LIGHT -> R.string.device_health_thermal_light
            DeviceThermalStatus.MODERATE -> R.string.device_health_thermal_moderate
            DeviceThermalStatus.SEVERE -> R.string.device_health_thermal_severe
            DeviceThermalStatus.CRITICAL -> R.string.device_health_thermal_critical
            DeviceThermalStatus.EMERGENCY -> R.string.device_health_thermal_emergency
            DeviceThermalStatus.SHUTDOWN -> R.string.device_health_thermal_shutdown
        },
    )

@Composable
fun LockScreen(
    recoveryRequired: Boolean,
    onUnlock: () -> Unit,
    onRestore: () -> Unit,
    onRecover: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.wallet_locked_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.wallet_locked_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        if (recoveryRequired) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.lock_recovery_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = StartExAmber,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onUnlock,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
        ) {
            Icon(Icons.Outlined.LockOpen, contentDescription = null)
            Text(stringResource(R.string.unlock_wallet), Modifier.padding(start = 8.dp))
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
            onClick = onRestore,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.restore_wallet_instead))
        }
        if (recoveryRequired) {
            // CPD-OFF
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onRecover,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.resume_recovery))
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onStop,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.StopCircle, contentDescription = null)
                Text(stringResource(R.string.emergency_stop), Modifier.padding(start = 8.dp))
            }
            // CPD-ON
        }
    }
}

@Composable
fun ProviderSetupScreen(
    configuredProviders: Set<ProviderId>,
    activatedProviders: Set<ProviderId>,
    providerHealth: List<ProviderHealthEntity>,
    onSave: (ProviderId, CharArray) -> Unit,
    onRemove: (ProviderId) -> Unit,
    onTest: (ProviderId) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    testingProviders: Set<ProviderId> = emptySet(),
) {
    BackHandler(onBack = onBack)
    val title = stringResource(R.string.provider_setup_title)
    ScreenColumn(modifier = modifier.semantics { paneTitle = title }) {
        ScreenHeader(
            title = title,
            subtitle = stringResource(R.string.provider_setup_subtitle),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.provider_storage_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        CredentialProviders.forEach { provider ->
            ProviderCard(
                provider = provider,
                configured = provider in configuredProviders,
                active = provider in activatedProviders,
                health = providerHealth.firstOrNull { it.provider == provider.name },
                onSave = onSave,
                onRemove = onRemove,
                onTest = onTest,
                testing = provider in testingProviders,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        KeylessProviderCard(
            health = providerHealth.firstOrNull { it.provider == ProviderId.KRAKEN.name },
            onTest = { onTest(ProviderId.KRAKEN) },
            testing = ProviderId.KRAKEN in testingProviders,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onBack,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.back))
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderId,
    configured: Boolean,
    active: Boolean,
    health: ProviderHealthEntity?,
    onSave: (ProviderId, CharArray) -> Unit,
    onRemove: (ProviderId) -> Unit,
    onTest: (ProviderId) -> Unit,
    testing: Boolean,
) {
    var key by remember(provider) { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val submitKey = {
        val secret = key.toCharArray()
        key = ""
        onSave(provider, secret)
    }
    val providerName =
        when (provider) {
            ProviderId.HELIUS -> "Helius"
            ProviderId.PUMP_PORTAL -> "PumpPortal"
            ProviderId.JUPITER -> "Jupiter"
            ProviderId.KRAKEN -> "Kraken"
        }
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SectionHeading(
                    providerName,
                    subtitle = stringResource(providerDescription(provider)),
                )
            }
            StatusPill(
                text = providerStatus(configured, active, health),
                color = providerStatusColor(configured, active, health),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.provider_api_key, providerName)) },
            supportingText = {
                Text(
                    stringResource(
                        if (key.isBlank()) {
                            R.string.provider_key_save_requirement
                        } else {
                            R.string.api_key_not_recoverable
                        },
                    ),
                )
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        if (key.isNotBlank()) {
                            focusManager.clearFocus()
                            submitKey()
                        }
                    },
                ),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = submitKey,
                enabled = key.isNotBlank(),
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (configured) R.string.replace_key else R.string.save_key))
            }
            if (configured) {
                OutlinedButton(
                    onClick = { onRemove(provider) },
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.remove_key))
                }
            }
        }
        if (configured && !active) {
            Text(
                text = stringResource(R.string.provider_activation_failed),
                style = MaterialTheme.typography.bodySmall,
                color = StartExRed,
                modifier = Modifier.padding(top = 10.dp),
            )
        } else if (configured && health?.state != "HEALTHY") {
            Text(
                text = stringResource(R.string.provider_health_pending),
                style = MaterialTheme.typography.bodySmall,
                color = StartExAmber,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (configured && active && provider != ProviderId.PUMP_PORTAL) {
            Spacer(modifier = Modifier.height(10.dp))
            // Keyed and keyless providers intentionally expose the same read-only test control.
            // CPD-OFF
            OutlinedButton(
                onClick = { onTest(provider) },
                enabled = !testing,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics {
                            if (testing) liveRegion = LiveRegionMode.Polite
                        },
            ) {
                Text(
                    stringResource(
                        if (testing) R.string.provider_test_in_progress else R.string.test_provider_read_only,
                        providerName,
                    ),
                )
            }
            // CPD-ON
        }
        if (configured && active && provider == ProviderId.PUMP_PORTAL) {
            Text(
                text = stringResource(R.string.provider_pump_test_at_monitor_start),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun KeylessProviderCard(
    health: ProviderHealthEntity?,
    onTest: () -> Unit,
    testing: Boolean,
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SectionHeading(
                    "Kraken",
                    subtitle = stringResource(R.string.provider_kraken_description),
                )
            }
            StatusPill(
                text = providerStatus(configured = true, active = true, health = health),
                color = providerStatusColor(configured = true, active = true, health = health),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.provider_kraken_keyless),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
            onClick = onTest,
            enabled = !testing,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics {
                        if (testing) liveRegion = LiveRegionMode.Polite
                    },
        ) {
            Text(
                stringResource(
                    if (testing) R.string.provider_test_in_progress else R.string.test_provider_read_only,
                    "Kraken",
                ),
            )
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: TokenCandidateEntity,
    onClick: () -> Unit,
) {
    val displayName = candidateDisplayName(candidate.symbol, candidate.name, stringResource(R.string.unknown_token))
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                AddressText(candidate.mint)
            }
            candidate.score?.let { score ->
                StatusPill(
                    text = stringResource(R.string.score_value, score),
                    color =
                        when {
                            score >= 75 -> StartExGreen
                            score >= 55 -> StartExAmber
                            else -> StartExRed
                        },
                )
            }
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.view_candidate_details_for, displayName),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text =
                candidate.rejectionCode?.let {
                    stringResource(R.string.rejection_reason, it.replace('_', ' '))
                } ?: candidate.state.replace('_', ' '),
            style = MaterialTheme.typography.bodySmall,
            color = if (candidate.rejectionCode == null) MaterialTheme.colorScheme.onSurfaceVariant else StartExRed,
        )
    }
}

@Composable
private fun CandidateDialog(
    candidate: TokenCandidateEntity,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(candidateDisplayName(candidate.symbol, candidate.name, stringResource(R.string.unknown_token)))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AddressText(candidate.mint, abbreviated = false)
                MetricRow(stringResource(R.string.candidate_state), candidate.state.replace('_', ' '))
                MetricRow(
                    stringResource(R.string.candidate_score),
                    candidate.score?.toString() ?: stringResource(R.string.no_recorded_value),
                )
                MetricRow(
                    stringResource(R.string.candidate_source),
                    candidate.source,
                )
                candidate.rejectionCode?.let {
                    Text(
                        text = stringResource(R.string.rejection_reason, it.replace('_', ' ')),
                        color = StartExRed,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = stringResource(R.string.candidate_detail_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

internal fun candidateDisplayName(
    symbol: String?,
    name: String?,
    unknownToken: String,
): String = symbol?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() } ?: unknownToken

@Composable
private fun EventCard(event: AppEventEntity) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = event.category.replace('_', ' '),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            StatusPill(
                text = eventSeverityLabel(event.severity),
                color =
                    when (event.severity) {
                        "ERROR", "CRITICAL" -> StartExRed
                        "WARN", "WARNING" -> StartExAmber
                        else -> MaterialTheme.colorScheme.secondary
                    },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = event.redactedMessage ?: event.code.replace('_', ' '),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = event.code,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun eventSeverityLabel(severity: String): String =
    when (severity) {
        "CRITICAL" -> stringResource(R.string.severity_critical)
        "ERROR" -> stringResource(R.string.severity_error)
        "WARN", "WARNING" -> stringResource(R.string.severity_warning)
        "INFO" -> stringResource(R.string.severity_information)
        else -> severity.replace('_', ' ')
    }

@Composable
private fun PreflightRow(
    label: String,
    ready: Boolean,
    actionRequiredReason: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (ready) StartExGreen else StartExAmber,
            )
            Text(
                text = label,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = stringResource(if (ready) R.string.status_ready else R.string.preflight_status_blocking),
            color = if (ready) StartExGreen else StartExAmber,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 36.dp, bottom = if (ready) 8.dp else 0.dp),
        )
        if (!ready) {
            Text(
                text = actionRequiredReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 36.dp, top = 4.dp, bottom = 8.dp),
            )
        }
        HorizontalDivider(color = StartExOutline)
    }
}

@Composable
private fun PreflightNotRequiredRow(label: String) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = stringResource(R.string.preflight_status_optional),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 36.dp, bottom = 8.dp),
        )
        HorizontalDivider(color = StartExOutline)
    }
}

@Composable
// CPD-OFF
private fun PreflightReadyToConnectRow(label: String) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = StartExAmber,
            )
            Text(
                text = label,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = stringResource(R.string.preflight_status_start_time_check),
            color = StartExAmber,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 36.dp),
        )
        Text(
            text = stringResource(R.string.pump_health_checked_on_start),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp, start = 36.dp),
        )
        HorizontalDivider(color = StartExOutline)
    }
}
// CPD-ON

@Composable
private fun monitorLabel(state: MonitorState): String =
    stringResource(
        when (state) {
            MonitorState.Stopped -> R.string.mode_not_running
            MonitorState.Running -> R.string.monitor_running
            MonitorState.Paused -> R.string.monitor_paused
            MonitorState.NeedsAttention -> R.string.monitor_needs_attention
        },
    )

@Composable
private fun monitorColor(state: MonitorState) =
    when (state) {
        MonitorState.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant
        MonitorState.Running -> StartExGreen
        MonitorState.Paused -> StartExAmber
        MonitorState.NeedsAttention -> StartExRed
    }

@Composable
private fun providerStatus(
    configured: Boolean,
    active: Boolean,
    health: ProviderHealthEntity?,
): String =
    when {
        !configured -> stringResource(R.string.provider_not_configured)
        !active -> stringResource(R.string.provider_inactive)
        health?.state == "HEALTHY" -> stringResource(R.string.provider_healthy)
        health?.state == "STALE" -> stringResource(R.string.provider_stale)
        health?.state == "RATE_LIMITED" -> stringResource(R.string.provider_rate_limited)
        health?.state == "UNAVAILABLE" -> stringResource(R.string.notification_provider_unavailable_title)
        health?.state == "DEGRADED" -> stringResource(R.string.status_action_required)
        health?.state == "OFFLINE" -> stringResource(R.string.health_offline)
        else -> stringResource(R.string.provider_check_pending)
    }

@Composable
private fun providerStatusColor(
    configured: Boolean,
    active: Boolean,
    health: ProviderHealthEntity?,
) = when {
    !configured -> MaterialTheme.colorScheme.onSurfaceVariant
    !active -> StartExRed
    health?.state == "HEALTHY" -> StartExGreen
    health?.state == "UNAVAILABLE" || health?.state == "OFFLINE" -> StartExRed
    else -> StartExAmber
}

private fun providerDescription(provider: ProviderId): Int =
    when (provider) {
        ProviderId.HELIUS -> R.string.provider_helius_description
        ProviderId.PUMP_PORTAL -> R.string.provider_pumpportal_description
        ProviderId.JUPITER -> R.string.provider_jupiter_description
        ProviderId.KRAKEN -> R.string.provider_kraken_description
    }

@Composable
private fun balanceText(walletBalanceLamports: Long?): String {
    val lamports = walletBalanceLamports ?: return stringResource(R.string.balance_unavailable)
    return stringResource(R.string.sol_balance_value, formatSol(lamports))
}

@Composable
private fun balanceSummary(
    walletBalanceLamports: Long?,
    walletBalanceEur: BigDecimal?,
): String {
    val sol = balanceText(walletBalanceLamports)
    val eur = walletBalanceEur ?: return sol
    return stringResource(R.string.balance_summary, sol, eurBalanceText(eur))
}

@Composable
private fun eurBalanceText(value: BigDecimal): String =
    stringResource(
        R.string.eur_balance_value,
        formatUserNumber(
            value = value.setScale(EUR_DISPLAY_DECIMALS, RoundingMode.HALF_UP),
            maximumFractionDigits = EUR_DISPLAY_DECIMALS,
            locale = LocalConfiguration.current.locales[0],
        ),
    )

@Composable
private fun marketAgeText(seconds: Long): String {
    val (resource, value) =
        when {
            seconds < 60 -> R.plurals.market_age_seconds to seconds
            seconds < 3_600 -> R.plurals.market_age_minutes to seconds / 60
            else -> R.plurals.market_age_hours to seconds / 3_600
        }
    return pluralStringResource(resource, if (value == 1L) 1 else 2, value)
}

@Composable
private fun formatSol(lamports: Long): String =
    formatUserNumber(
        value =
            BigDecimal
                .valueOf(lamports)
                .divide(BigDecimal.valueOf(LAMPORTS_PER_SOL), SOL_DISPLAY_DECIMALS, RoundingMode.DOWN)
                .stripTrailingZeros(),
        maximumFractionDigits = SOL_DISPLAY_DECIMALS,
        locale = LocalConfiguration.current.locales[0],
    )

@Composable
private fun formatSignedSol(lamports: Long): String = formatAtomicSol(BigInteger.valueOf(lamports), signed = true)

@Composable
private fun formatAtomicSol(
    lamports: BigInteger,
    signed: Boolean,
): String {
    val value =
        formatUserNumber(
            value = BigDecimal(lamports, SOL_DISPLAY_DECIMALS).stripTrailingZeros(),
            maximumFractionDigits = SOL_DISPLAY_DECIMALS,
            locale = LocalConfiguration.current.locales[0],
        )
    return stringResource(
        if (signed && lamports.signum() > 0) R.string.positive_sol_value else R.string.sol_balance_value,
        value,
    )
}

@Composable
private fun formattedTimestamp(timestampMillis: Long): String = formatUserTimestamp(timestampMillis, LocalConfiguration.current.locales[0])

private const val HISTORY_DISPLAY_LIMIT = 250
private const val WALLET_DATA_DISPLAY_LIMIT = 10
private const val LAMPORTS_PER_SOL = 1_000_000_000L
private const val SOL_DISPLAY_DECIMALS = 9
private const val EUR_DISPLAY_DECIMALS = 2
