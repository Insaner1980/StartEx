package com.finnvek.startex.ui.screens

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContactEmergency
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.finnvek.startex.R
import com.finnvek.startex.ui.CredentialProviders
import com.finnvek.startex.ui.PersistedAppState
import com.finnvek.startex.ui.TradingMode
import com.finnvek.startex.ui.components.AddressText
import com.finnvek.startex.ui.components.SectionCard
import com.finnvek.startex.ui.theme.StartExAmber
import com.finnvek.startex.ui.theme.StartExGreen
import com.finnvek.startex.ui.theme.StartExOutline

private data class SetupPage(
    @StringRes val title: Int,
    @StringRes val body: Int,
    val icon: ImageVector,
)

private val setupPages =
    listOf(
        SetupPage(R.string.setup_welcome_title, R.string.setup_welcome_body, Icons.Outlined.CloudOff),
        SetupPage(R.string.setup_wallet_title, R.string.setup_wallet_body, Icons.Outlined.AccountBalanceWallet),
        SetupPage(R.string.setup_backup_title, R.string.setup_backup_body, Icons.Outlined.Key),
        SetupPage(R.string.setup_security_title, R.string.setup_security_body, Icons.Outlined.Security),
        SetupPage(R.string.setup_provider_title, R.string.setup_provider_body, Icons.Outlined.Lock),
        SetupPage(R.string.setup_trusted_title, R.string.setup_trusted_body, Icons.Outlined.ContactEmergency),
        SetupPage(R.string.setup_risk_limits_title, R.string.setup_risk_limits_body, Icons.Outlined.Shield),
        SetupPage(R.string.setup_mode_title, R.string.setup_mode_body, Icons.Outlined.QueryStats),
        SetupPage(R.string.setup_ready_title, R.string.setup_ready_body, Icons.Outlined.Security),
    )

@Composable
fun OnboardingScreen(
    state: PersistedAppState,
    onCreateWallet: () -> Unit,
    onRestoreWallet: () -> Unit,
    onConfigureProviders: () -> Unit,
    onTrustedAddresses: () -> Unit,
    onRequestSecurityMode: (Boolean, Boolean, Boolean) -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    providersFocusRequester: FocusRequester? = null,
) {
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    var acknowledgedMask by rememberSaveable { mutableIntStateOf(0) }
    val page = setupPages[pageIndex]
    val isAcknowledged = acknowledgedMask and (1 shl pageIndex) != 0
    val contentScrollState = rememberScrollState()
    val pageHeadingFocusRequester = remember { FocusRequester() }

    LaunchedEffect(pageIndex) {
        contentScrollState.scrollTo(0)
        pageHeadingFocusRequester.requestFocus()
    }

    BackHandler(enabled = pageIndex > 0) { pageIndex-- }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 18.dp)
                .testTag("onboarding"),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.setup_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.setup_progress, pageIndex + 1, setupPages.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("onboarding_progress"),
        )
        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { (pageIndex + 1f) / setupPages.size },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        progressBarRangeInfo =
                            androidx.compose.ui.semantics.ProgressBarRangeInfo(
                                current = pageIndex + 1f,
                                range = 1f..setupPages.size.toFloat(),
                                steps = setupPages.size - 2,
                            )
                    },
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .verticalScroll(contentScrollState),
        ) {
            Spacer(modifier = Modifier.height(20.dp))
            Surface(
                modifier = Modifier.size(64.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.large,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = page.icon,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = stringResource(page.title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier
                        .focusRequester(pageHeadingFocusRequester)
                        .focusable()
                        .semantics { heading() },
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(page.body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(18.dp))
            SetupStepContent(
                pageIndex = pageIndex,
                state = state,
                onCreateWallet = onCreateWallet,
                onRestoreWallet = onRestoreWallet,
                onConfigureProviders = onConfigureProviders,
                onTrustedAddresses = onTrustedAddresses,
                onRequestSecurityMode = onRequestSecurityMode,
                providersFocusRequester = providersFocusRequester,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = isAcknowledged,
                            role = Role.Checkbox,
                            onValueChange = { checked ->
                                acknowledgedMask =
                                    if (checked) {
                                        acknowledgedMask or (1 shl pageIndex)
                                    } else {
                                        acknowledgedMask and (1 shl pageIndex).inv()
                                    }
                            },
                        ).testTag("onboarding_acknowledge"),
            ) {
                Checkbox(
                    checked = isAcknowledged,
                    onCheckedChange = null,
                )
                Text(
                    text =
                        stringResource(
                            if (pageIndex == setupPages.lastIndex) {
                                R.string.setup_final_acknowledge
                            } else {
                                R.string.setup_acknowledge
                            },
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!isAcknowledged) {
                Text(
                    text = stringResource(R.string.setup_acknowledgement_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = StartExAmber,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (pageIndex > 0) {
                    OutlinedButton(
                        onClick = { pageIndex-- },
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("onboarding_previous"),
                    ) {
                        Text(stringResource(R.string.setup_previous))
                    }
                }
                Button(
                    onClick = {
                        if (pageIndex == setupPages.lastIndex) onComplete() else pageIndex++
                    },
                    enabled = isAcknowledged,
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("onboarding_next"),
                ) {
                    Text(
                        stringResource(
                            if (pageIndex == setupPages.lastIndex) R.string.setup_finish else R.string.setup_next,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupStepContent(
    pageIndex: Int,
    state: PersistedAppState,
    onCreateWallet: () -> Unit,
    onRestoreWallet: () -> Unit,
    onConfigureProviders: () -> Unit,
    onTrustedAddresses: () -> Unit,
    onRequestSecurityMode: (Boolean, Boolean, Boolean) -> Unit,
    providersFocusRequester: FocusRequester?,
) {
    when (pageIndex) {
        0 -> {
            WarningCard(
                title = stringResource(R.string.setup_risk_title),
                body = stringResource(R.string.setup_risk_body),
            )
        }

        1 -> {
            WalletSetupActions(state, onCreateWallet, onRestoreWallet)
        }

        2 -> {
            SetupStatusCard(
                title = stringResource(R.string.backup_status),
                ready = state.walletBackupConfirmed,
                readyText = stringResource(R.string.backup_verified),
                missingText = stringResource(R.string.backup_not_verified),
            )
        }

        3 -> {
            SecurityModeCard(state, onRequestSecurityMode)
        }

        4 -> {
            ProviderSetupCard(state, onConfigureProviders, providersFocusRequester)
        }

        5 -> {
            TrustedAddressSetupCard(state, onTrustedAddresses)
        }

        6 -> {
            RiskLimitCard(state)
        }

        7 -> {
            PaperModeCard(state.mode)
        }

        8 -> {
            CompletionChecklist(state)
        }
    }
}

@Composable
private fun WalletSetupActions(
    state: PersistedAppState,
    onCreateWallet: () -> Unit,
    onRestoreWallet: () -> Unit,
) {
    Column {
        if (state.walletAddress != null) {
            SetupStatusCard(
                title = stringResource(R.string.wallet_status),
                ready = true,
                readyText = stringResource(R.string.wallet_configured),
                missingText = "",
            )
            Spacer(modifier = Modifier.height(8.dp))
            AddressText(state.walletAddress, abbreviated = false)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onCreateWallet,
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.create_wallet))
                }
                OutlinedButton(
                    onClick = onRestoreWallet,
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.restore_wallet))
                }
            }
            Text(
                text = stringResource(R.string.wallet_setup_can_continue),
                style = MaterialTheme.typography.bodySmall,
                color = StartExAmber,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun SecurityModeCard(
    state: PersistedAppState,
    onRequestSecurityMode: (Boolean, Boolean, Boolean) -> Unit,
) {
    SectionCard {
        SecurityModeControl(
            state = state,
            onRequestSecurityMode = onRequestSecurityMode,
        )
    }
}

@Composable
private fun ProviderSetupCard(
    state: PersistedAppState,
    onConfigureProviders: () -> Unit,
    focusRequester: FocusRequester?,
) {
    SectionCard {
        SetupStatusRow(
            label = stringResource(R.string.provider_keys),
            ready =
                state.configuredProviders.size == CredentialProviders.size &&
                    state.activatedProviders.size == CredentialProviders.size,
            detail =
                pluralStringResource(
                    R.plurals.provider_stored_active_count,
                    state.configuredProviders.size,
                    state.configuredProviders.size,
                    state.activatedProviders.size,
                    CredentialProviders.size,
                ),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onConfigureProviders,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(
                        if (focusRequester != null) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        },
                    ),
        ) {
            Text(stringResource(R.string.configure_providers))
        }
    }
}

@Composable
private fun TrustedAddressSetupCard(
    state: PersistedAppState,
    onTrustedAddresses: () -> Unit,
) {
    SectionCard {
        SetupStatusRow(
            label = stringResource(R.string.trusted_addresses),
            ready = state.trustedAddresses.isNotEmpty(),
            detail =
                if (state.trustedAddresses.isEmpty()) {
                    stringResource(R.string.optional_not_configured)
                } else {
                    pluralStringResource(
                        R.plurals.trusted_address_count,
                        state.trustedAddresses.size,
                        state.trustedAddresses.size,
                    )
                },
        )
        if (state.walletAddress != null) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onTrustedAddresses,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.manage_trusted_addresses))
            }
        }
    }
}

@Composable
private fun RiskLimitCard(state: PersistedAppState) {
    SectionCard {
        if (state.risk == null) {
            Text(
                text = stringResource(R.string.default_limits_on_finish),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SetupStatusRow(
                label = stringResource(R.string.hard_limits),
                ready = true,
                detail =
                    pluralStringResource(
                        R.plurals.risk_summary,
                        state.risk.maximumOpenPositions,
                        state.risk.maximumOpenPositions,
                        state.risk.maximumTradesPerDay,
                    ),
            )
        }
    }
}

@Composable
private fun PaperModeCard(mode: TradingMode) {
    SectionCard {
        Row(
            modifier =
                Modifier.selectable(
                    selected = mode == TradingMode.Paper,
                    enabled = false,
                    role = Role.RadioButton,
                    onClick = {},
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = mode == TradingMode.Paper, onClick = null, enabled = false)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.mode_paper), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.paper_no_funds),
                    style = MaterialTheme.typography.bodySmall,
                    color = StartExGreen,
                )
            }
        }
        HorizontalDivider(color = StartExOutline)
        Row(
            modifier =
                Modifier.selectable(
                    selected = false,
                    enabled = false,
                    role = Role.RadioButton,
                    onClick = {},
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = false, onClick = null, enabled = false)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.mode_live),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.live_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = StartExAmber,
                )
            }
        }
    }
}

@Composable
private fun CompletionChecklist(state: PersistedAppState) {
    SectionCard {
        SetupStatusRow(
            stringResource(R.string.check_wallet),
            state.walletAddress != null,
            readyOrRequired(state.walletAddress != null),
        )
        SetupStatusRow(
            stringResource(R.string.check_backup),
            state.walletBackupConfirmed,
            readyOrRequired(state.walletBackupConfirmed),
        )
        SetupStatusRow(
            stringResource(R.string.provider_keys),
            state.configuredProviders.size == CredentialProviders.size &&
                state.activatedProviders.size == CredentialProviders.size,
            pluralStringResource(
                R.plurals.provider_stored_active_count,
                state.configuredProviders.size,
                state.configuredProviders.size,
                state.activatedProviders.size,
                CredentialProviders.size,
            ),
        )
        SetupStatusRow(
            stringResource(R.string.check_limits),
            state.risk != null,
            if (state.risk == null) {
                stringResource(R.string.created_on_finish)
            } else {
                stringResource(R.string.status_ready)
            },
        )
        Text(
            text = stringResource(R.string.checklist_degraded_note),
            style = MaterialTheme.typography.bodySmall,
            color = StartExAmber,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun WarningCard(
    title: String,
    body: String,
) {
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = StartExAmber)
    }
}

@Composable
private fun SetupStatusCard(
    title: String,
    ready: Boolean,
    readyText: String,
    missingText: String,
) {
    SectionCard {
        SetupStatusRow(title, ready, if (ready) readyText else missingText)
    }
}

@Composable
private fun SetupStatusRow(
    label: String,
    ready: Boolean,
    detail: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = if (ready) StartExGreen else StartExAmber,
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun readyOrRequired(ready: Boolean): String =
    stringResource(
        if (ready) R.string.status_ready else R.string.status_action_required,
    )
