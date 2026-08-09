package com.finnvek.startex.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.finnvek.startex.R
import com.finnvek.startex.data.local.TrustedAddressEntity
import com.finnvek.startex.ui.PersistedAppState
import com.finnvek.startex.ui.WalletOverlay
import com.finnvek.startex.ui.WalletSetupState
import com.finnvek.startex.ui.WalletTransferState
import com.finnvek.startex.ui.components.AddressText
import com.finnvek.startex.ui.components.EmptyState
import com.finnvek.startex.ui.components.MetricRow
import com.finnvek.startex.ui.components.ScreenColumn
import com.finnvek.startex.ui.components.ScreenHeader
import com.finnvek.startex.ui.components.SectionCard
import com.finnvek.startex.ui.components.SectionHeading
import com.finnvek.startex.ui.theme.StartExAmber
import com.finnvek.startex.ui.theme.StartExBlue
import com.finnvek.startex.ui.theme.StartExGreen
import com.finnvek.startex.ui.theme.StartExOutline
import com.finnvek.startex.ui.theme.StartExRed
import com.finnvek.startex.wallet.ManualTransferStatus
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun WalletFlowScreen(
    state: WalletSetupState,
    onMnemonicSaved: () -> Unit,
    onVerifyBackup: (Map<Int, String>) -> Unit,
    onRestore: (CharArray) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    when (state) {
        WalletSetupState.Closed -> {
            Unit
        }

        WalletSetupState.Working,
        WalletSetupState.Saving,
        -> {
            WalletWorkingScreen(saving = state is WalletSetupState.Saving, onCancel = onCancel)
        }

        is WalletSetupState.Mnemonic -> {
            MnemonicBackupScreen(
                state = state,
                onContinue = onMnemonicSaved,
                onCancel = onCancel,
            )
        }

        is WalletSetupState.BackupChallenge -> {
            BackupChallengeScreen(
                state = state,
                onVerify = onVerifyBackup,
                onCancel = onCancel,
            )
        }

        is WalletSetupState.RestoreInput -> {
            RestoreWalletScreen(
                state = state,
                onRestore = onRestore,
                onCancel = onCancel,
            )
        }

        is WalletSetupState.ReviewWallet -> {
            WalletReviewScreen(
                state = state,
                onSave = onSave,
                onCancel = onCancel,
            )
        }
    }
}

@Composable
fun WalletOverlayScreen(
    overlay: WalletOverlay,
    state: PersistedAppState,
    transferState: WalletTransferState,
    trustedAddresses: List<TrustedAddressEntity>,
    onDismiss: () -> Unit,
    onRefreshBalance: () -> Unit,
    onPrepareTransfer: (Long, String) -> Unit,
    onSubmitTransfer: (String) -> Unit,
    onResetTransfer: () -> Unit,
    onAddTrustedAddress: (String, String, Boolean) -> Unit,
    onDeleteTrustedAddress: (Long, String) -> Unit,
    onUnlockTrustedAddress: (Long, String) -> Unit,
) {
    BackHandler {
        if (transferState != WalletTransferState.Submitting) onDismiss()
    }
    when (overlay) {
        WalletOverlay.None -> {
            Unit
        }

        WalletOverlay.Receive -> {
            ReceiveScreen(state, onDismiss, onRefreshBalance)
        }

        WalletOverlay.Send -> {
            SendScreen(
                appState = state,
                transferState = transferState,
                addresses = trustedAddresses,
                onPrepare = onPrepareTransfer,
                onSubmit = onSubmitTransfer,
                onReset = onResetTransfer,
                onBack = onDismiss,
            )
        }

        WalletOverlay.TrustedAddresses -> {
            TrustedAddressesScreen(
                addresses = trustedAddresses,
                onAdd = onAddTrustedAddress,
                onDelete = onDeleteTrustedAddress,
                onUnlock = onUnlockTrustedAddress,
                onBack = onDismiss,
            )
        }

        is WalletOverlay.RevealedMnemonic -> {
            RevealedMnemonicScreen(
                phrase = overlay.phrase,
                onDone = onDismiss,
            )
        }
    }
}

@Composable
private fun MnemonicBackupScreen(
    state: WalletSetupState.Mnemonic,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    var savedOffline by rememberSaveable { mutableStateOf(false) }
    val words =
        remember(state.phrase) {
            state.phrase
                .concatToString()
                .split(Regex("\\s+"))
                .filter(String::isNotBlank)
        }
    ScreenColumn {
        SensitiveHeader(stringResource(R.string.backup_phrase_title), onCancel)
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.backup_phrase_warning),
            color = StartExAmber,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(16.dp))
        SectionCard {
            words.withIndex().chunked(2).forEach { rowWords ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowWords.forEach { indexedWord ->
                        Surface(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = "${indexedWord.index + 1}  ${indexedWord.value}",
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    if (rowWords.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        AddressText(state.publicAddress, abbreviated = false)
        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = savedOffline, onCheckedChange = { savedOffline = it })
            Text(
                text = stringResource(R.string.backup_offline_confirmation),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = onContinue,
            enabled = savedOffline,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.continue_to_backup_quiz))
        }
    }
}

@Composable
private fun BackupChallengeScreen(
    state: WalletSetupState.BackupChallenge,
    onVerify: (Map<Int, String>) -> Unit,
    onCancel: () -> Unit,
) {
    val answers = remember(state.wordNumbers) { mutableStateMapOf<Int, String>() }
    ScreenColumn {
        SensitiveHeader(stringResource(R.string.backup_quiz_title), onCancel)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.backup_quiz_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(18.dp))
        state.wordNumbers.forEach { number ->
            OutlinedTextField(
                value = answers[number].orEmpty(),
                onValueChange = { answers[number] = it.trim().lowercase() },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                label = { Text(stringResource(R.string.backup_word_number, number)) },
                singleLine = true,
                isError = state.error != null,
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                    ),
            )
        }
        state.error?.let {
            Text(
                text = stringResource(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = { onVerify(answers.toMap()) },
            enabled = state.wordNumbers.all { answers[it].orEmpty().isNotBlank() },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.verify_backup))
        }
    }
}

@Composable
private fun RestoreWalletScreen(
    state: WalletSetupState.RestoreInput,
    onRestore: (CharArray) -> Unit,
    onCancel: () -> Unit,
) {
    var phrase by remember { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    ScreenColumn {
        SensitiveHeader(stringResource(R.string.restore_wallet_title), onCancel)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.restore_wallet_warning),
            style = MaterialTheme.typography.bodyLarge,
            color = StartExAmber,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = phrase,
            onValueChange = { phrase = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.recovery_phrase)) },
            supportingText = {
                Text(
                    text =
                        state.error?.let { stringResource(it) }
                            ?: stringResource(R.string.restore_phrase_help),
                )
            },
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription =
                            stringResource(
                                if (visible) R.string.hide_phrase else R.string.show_phrase,
                            ),
                    )
                }
            },
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                ),
            minLines = 4,
            isError = state.error != null,
        )
        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = {
                val secret = phrase.trim().toCharArray()
                phrase = ""
                onRestore(secret)
            },
            enabled = phrase.isNotBlank(),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.validate_phrase))
        }
    }
}

@Composable
private fun WalletReviewScreen(
    state: WalletSetupState.ReviewWallet,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    var confirmed by rememberSaveable { mutableStateOf(false) }
    ScreenColumn {
        SensitiveHeader(stringResource(R.string.wallet_review_title), onCancel)
        Spacer(modifier = Modifier.height(14.dp))
        SectionCard {
            SectionHeading(
                stringResource(if (state.restored) R.string.restored_wallet else R.string.new_wallet),
                stringResource(R.string.network_mainnet),
            )
            Spacer(modifier = Modifier.height(14.dp))
            AddressText(state.publicAddress, abbreviated = false)
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.derivation_path_value),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
            Text(
                text = stringResource(R.string.wallet_dedicated_confirmation),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            text = stringResource(R.string.wallet_save_auth_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(
            onClick = onSave,
            enabled = confirmed,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null)
            Text(stringResource(R.string.encrypt_and_save_wallet), Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun WalletWorkingScreen(
    saving: Boolean,
    onCancel: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = stringResource(if (saving) R.string.wallet_saving else R.string.wallet_preparing),
                style = MaterialTheme.typography.titleLarge,
            )
            if (!saving) {
                TextButtonWithMinimumTarget(
                    text = stringResource(R.string.cancel),
                    onClick = onCancel,
                )
            }
        }
    }
}

@Composable
private fun ReceiveScreen(
    state: PersistedAppState,
    onBack: () -> Unit,
    onRefreshBalance: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val address = state.walletAddress.orEmpty()
    val shareAddressTitle = stringResource(R.string.share_address)
    val qr = remember(address) { createQrBitmap(address) }
    ScreenColumn {
        SensitiveHeader(stringResource(R.string.receive_sol), onBack)
        Spacer(modifier = Modifier.height(14.dp))
        Surface(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            color = Color.White,
            shape = RoundedCornerShape(12.dp),
        ) {
            if (qr == null) {
                Icon(
                    imageVector = Icons.Outlined.QrCode2,
                    contentDescription = stringResource(R.string.qr_unavailable),
                    modifier =
                        Modifier
                            .size(240.dp)
                            .padding(32.dp),
                    tint = Color.Black,
                )
            } else {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = stringResource(R.string.wallet_address_qr),
                    modifier =
                        Modifier
                            .size(240.dp)
                            .padding(12.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        SectionCard {
            SectionHeading(stringResource(R.string.your_solana_address))
            Spacer(modifier = Modifier.height(10.dp))
            AddressText(address, abbreviated = false)
        }
        Spacer(modifier = Modifier.height(12.dp))
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionHeading(stringResource(R.string.wallet_balance))
                    Text(
                        text =
                            state.walletBalanceLamports?.let(::formatLamports)
                                ?: stringResource(R.string.balance_unavailable),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    state.walletBalanceEur?.let { value ->
                        Text(
                            text =
                                stringResource(
                                    R.string.eur_balance_value,
                                    value.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = onRefreshBalance,
                    enabled = !state.walletBalanceLoading,
                ) {
                    if (state.walletBalanceLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.refresh_balance),
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
            Text(
                text = stringResource(R.string.fiat_estimate_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.network_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = StartExAmber,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(address)) },
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                Text(stringResource(R.string.copy_address), Modifier.padding(start = 8.dp))
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, address)
                            },
                            shareAddressTitle,
                        ),
                    )
                },
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null)
                Text(stringResource(R.string.share), Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun SendScreen(
    appState: PersistedAppState,
    transferState: WalletTransferState,
    addresses: List<TrustedAddressEntity>,
    onPrepare: (Long, String) -> Unit,
    onSubmit: (String) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    when (transferState) {
        WalletTransferState.Editing -> {
            SendInputScreen(appState, addresses, onPrepare, onBack)
        }

        WalletTransferState.Preparing -> {
            TransferWorkingScreen(
                title = stringResource(R.string.transfer_preparing),
                onBack = onBack,
            )
        }

        is WalletTransferState.Review -> {
            SendReviewScreen(
                review = transferState,
                onSubmit = onSubmit,
                onReset = onReset,
                onBack = onBack,
            )
        }

        WalletTransferState.Submitting -> {
            TransferWorkingScreen(
                title = stringResource(R.string.transfer_submitting),
                onBack = null,
            )
        }

        is WalletTransferState.Submitted -> {
            val (title, detail, tone) =
                when (transferState.status) {
                    ManualTransferStatus.SUBMITTED -> {
                        Triple(
                            R.string.transfer_submitted_title,
                            R.string.transfer_submitted_body,
                            TransferResultTone.Tracking,
                        )
                    }

                    ManualTransferStatus.PROCESSED -> {
                        Triple(
                            R.string.transfer_processed_title,
                            R.string.transfer_processed_body,
                            TransferResultTone.Tracking,
                        )
                    }

                    ManualTransferStatus.CONFIRMED -> {
                        Triple(
                            R.string.transfer_confirmed_title,
                            R.string.transfer_confirmed_body,
                            TransferResultTone.Tracking,
                        )
                    }

                    ManualTransferStatus.FINALIZED -> {
                        Triple(
                            R.string.transfer_finalized_title,
                            R.string.transfer_finalized_body,
                            TransferResultTone.Success,
                        )
                    }
                }
            TransferResultScreen(
                signature = transferState.signature,
                titleMessage = title,
                detailMessage = detail,
                tone = tone,
                onDone = onBack,
            )
        }

        is WalletTransferState.Uncertain -> {
            TransferResultScreen(
                signature = transferState.localSignature,
                titleMessage = R.string.transfer_uncertain_title,
                detailMessage = transferState.message,
                tone = TransferResultTone.Warning,
                onDone = onBack,
            )
        }

        is WalletTransferState.Rejected -> {
            TransferResultScreen(
                signature = transferState.signature,
                titleMessage = R.string.transfer_rejected_title,
                detailMessage = transferState.message,
                tone = TransferResultTone.Error,
                onDone = onBack,
            )
        }

        is WalletTransferState.Failed -> {
            TransferFailureScreen(
                message = stringResource(transferState.message),
                onRetry = onReset,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun SendInputScreen(
    appState: PersistedAppState,
    addresses: List<TrustedAddressEntity>,
    onPrepare: (Long, String) -> Unit,
    onBack: () -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var amount by remember { mutableStateOf("") }
    val selected = addresses.firstOrNull { it.id == selectedId }
    val amountIsValid = amount.toSolAmountOrNull() != null
    val monitoringAllowsTransfer =
        appState.monitorState == com.finnvek.startex.ui.MonitorState.Stopped ||
            appState.monitorState == com.finnvek.startex.ui.MonitorState.Paused
    val noOpenPositions = appState.openPositions.isEmpty()
    ScreenColumn {
        SensitiveHeader(stringResource(R.string.send_sol), onBack)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.send_trusted_only),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(14.dp))
        if (addresses.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = stringResource(R.string.no_trusted_addresses),
                body = stringResource(R.string.no_trusted_addresses_send_body),
            )
        } else {
            SectionCard {
                SectionHeading(stringResource(R.string.choose_trusted_address))
                addresses.forEach { address ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedId == address.id,
                            onClick = { selectedId = address.id },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(address.label, style = MaterialTheme.typography.bodyLarge)
                            AddressText(address.address)
                        }
                        if (address.isLocked) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = stringResource(R.string.address_locked),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = StartExOutline)
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter { character -> character.isDigit() || character == '.' } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.amount_sol)) },
                supportingText = {
                    Text(
                        if (amount.isBlank() || amountIsValid) {
                            stringResource(R.string.amount_sol_help)
                        } else {
                            stringResource(R.string.amount_invalid)
                        },
                    )
                },
                isError = amount.isNotBlank() && !amountIsValid,
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (!monitoringAllowsTransfer || !noOpenPositions) {
                Text(
                    text =
                        stringResource(
                            if (!noOpenPositions) {
                                R.string.transfer_open_positions_blocked
                            } else {
                                R.string.transfer_pause_required
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StartExAmber,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            Button(
                onClick = { selected?.let { onPrepare(it.id, amount) } },
                enabled = selected != null && amountIsValid && monitoringAllowsTransfer && noOpenPositions,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.review_transaction))
            }
        }
    }
}

@Composable
private fun SendReviewScreen(
    review: WalletTransferState.Review,
    onSubmit: (String) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    var finalCharacters by rememberSaveable { mutableStateOf("") }
    val matches =
        !review.requiresAddressVerification ||
            finalCharacters == review.destinationAddress.takeLast(4)
    val totalDeduction = Math.addExact(review.amountLamports, review.estimatedFeeLamports)
    ScreenColumn {
        SensitiveHeader(stringResource(R.string.review_transaction), onBack)
        Spacer(modifier = Modifier.height(12.dp))
        SectionCard {
            SectionHeading(review.destinationLabel, stringResource(R.string.network_mainnet))
            Spacer(modifier = Modifier.height(10.dp))
            AddressText(review.destinationAddress, abbreviated = false)
            Spacer(modifier = Modifier.height(14.dp))
            MetricRow(stringResource(R.string.transfer_amount), formatLamports(review.amountLamports))
            Spacer(modifier = Modifier.height(8.dp))
            MetricRow(stringResource(R.string.estimated_network_fee), formatLamports(review.estimatedFeeLamports))
            Spacer(modifier = Modifier.height(8.dp))
            MetricRow(stringResource(R.string.total_deduction), formatLamports(totalDeduction))
            Spacer(modifier = Modifier.height(8.dp))
            MetricRow(stringResource(R.string.minimum_reserve), formatLamports(review.reserveLamports))
            Spacer(modifier = Modifier.height(8.dp))
            MetricRow(
                stringResource(R.string.last_valid_block_height),
                review.lastValidBlockHeight.toString(),
            )
        }
        if (review.requiresAddressVerification) {
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedTextField(
                value = finalCharacters,
                onValueChange = { finalCharacters = it.take(4) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.confirm_last_four)) },
                supportingText = {
                    Text(
                        if (finalCharacters.isEmpty() || matches) {
                            stringResource(R.string.confirm_last_four_help)
                        } else {
                            stringResource(R.string.confirm_last_four_mismatch)
                        },
                    )
                },
                isError = finalCharacters.isNotEmpty() && !matches,
                singleLine = true,
            )
        } else {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.trusted_address_previously_verified),
                style = MaterialTheme.typography.bodyMedium,
                color = StartExGreen,
            )
        }
        Text(
            text = stringResource(R.string.transfer_auth_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = StartExAmber,
            modifier = Modifier.padding(vertical = 14.dp),
        )
        Button(
            onClick = { onSubmit(finalCharacters) },
            enabled = matches,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
        ) {
            Icon(Icons.Outlined.Send, contentDescription = null)
            Text(stringResource(R.string.authenticate_sign_send), Modifier.padding(start = 8.dp))
        }
        OutlinedButton(
            onClick = onReset,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.edit_transfer))
        }
    }
}

@Composable
private fun TransferWorkingScreen(
    title: String,
    onBack: (() -> Unit)?,
) {
    ScreenColumn {
        if (onBack == null) {
            ScreenHeader(stringResource(R.string.send_sol))
        } else {
            SensitiveHeader(stringResource(R.string.send_sol), onBack)
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
    }
}

@Composable
private fun TransferResultScreen(
    signature: String,
    @androidx.annotation.StringRes titleMessage: Int,
    @androidx.annotation.StringRes detailMessage: Int,
    tone: TransferResultTone,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    ScreenColumn {
        SensitiveHeader(stringResource(R.string.transfer_status), onDone)
        Spacer(modifier = Modifier.height(14.dp))
        SectionCard {
            Icon(
                imageVector =
                    when (tone) {
                        TransferResultTone.Tracking -> Icons.Outlined.Refresh

                        TransferResultTone.Success -> Icons.Outlined.Check

                        TransferResultTone.Warning,
                        TransferResultTone.Error,
                        -> Icons.Outlined.ErrorOutline
                    },
                contentDescription = null,
                tint =
                    when (tone) {
                        TransferResultTone.Tracking -> StartExBlue
                        TransferResultTone.Success -> StartExGreen
                        TransferResultTone.Warning -> StartExAmber
                        TransferResultTone.Error -> StartExRed
                    },
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            SectionHeading(
                title = stringResource(titleMessage),
                subtitle = stringResource(detailMessage),
            )
            Spacer(modifier = Modifier.height(14.dp))
            AddressText(signature, abbreviated = false)
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(signature)) },
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Text(stringResource(R.string.copy_signature), Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://solscan.io/tx/$signature")),
                        )
                    },
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                    Text(stringResource(R.string.open_explorer), Modifier.padding(start = 8.dp))
                }
            }
        }
        Button(
            onClick = onDone,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
                    .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.done))
        }
    }
}

private enum class TransferResultTone {
    Tracking,
    Success,
    Warning,
    Error,
}

@Composable
private fun TransferFailureScreen(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    ScreenColumn {
        SensitiveHeader(stringResource(R.string.transfer_failed_title), onBack)
        Spacer(modifier = Modifier.height(16.dp))
        SectionCard {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = StartExRed,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = stringResource(R.string.transfer_failed_no_submission),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        OutlinedButton(
            onClick = onRetry,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .heightIn(min = 48.dp),
        ) {
            Icon(Icons.Outlined.Replay, contentDescription = null)
            Text(stringResource(R.string.edit_and_retry), Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun TrustedAddressesScreen(
    addresses: List<TrustedAddressEntity>,
    onAdd: (String, String, Boolean) -> Unit,
    onDelete: (Long, String) -> Unit,
    onUnlock: (Long, String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scanner =
        remember(context) {
            val options =
                GmsBarcodeScannerOptions
                    .Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .enableAutoZoom()
                    .build()
            GmsBarcodeScanning.getClient(context, options)
        }
    var label by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var locked by rememberSaveable { mutableStateOf(true) }
    var scanFailed by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<TrustedAddressConfirmation?>(null) }
    ScreenColumn {
        SensitiveHeader(stringResource(R.string.trusted_addresses), onBack)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.trusted_addresses_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(14.dp))
        if (addresses.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Add,
                title = stringResource(R.string.no_trusted_addresses),
                body = stringResource(R.string.no_trusted_addresses_body),
            )
        } else {
            SectionCard {
                addresses.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = StartExOutline)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.label, style = MaterialTheme.typography.bodyLarge)
                            AddressText(item.address)
                        }
                        if (item.isLocked) {
                            IconButton(onClick = { confirmation = TrustedAddressConfirmation.Unlock(item) }) {
                                Icon(
                                    imageVector = Icons.Outlined.LockOpen,
                                    contentDescription = stringResource(R.string.unlock_trusted_address),
                                    tint = StartExGreen,
                                )
                            }
                        }
                        IconButton(onClick = { confirmation = TrustedAddressConfirmation.Delete(item) }) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteForever,
                                contentDescription = stringResource(R.string.delete_trusted_address),
                                tint = StartExRed,
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        SectionCard {
            SectionHeading(stringResource(R.string.add_trusted_address))
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.address_label)) },
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.solana_address)) },
                minLines = 2,
            )
            OutlinedButton(
                onClick = {
                    scanFailed = false
                    scanner
                        .startScan()
                        .addOnSuccessListener { barcode ->
                            val scanned = barcode.rawValue?.toTrustedAddressValue()
                            if (scanned == null) {
                                scanFailed = true
                            } else {
                                address = scanned
                            }
                        }.addOnFailureListener { scanFailed = true }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                Text(stringResource(R.string.scan_address_qr), Modifier.padding(start = 8.dp))
            }
            if (scanFailed) {
                Text(
                    text = stringResource(R.string.address_scan_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = StartExAmber,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = locked, onCheckedChange = { locked = it })
                Text(stringResource(R.string.lock_trusted_address), Modifier.weight(1f))
            }
            Text(
                text = stringResource(R.string.trusted_auth_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    onAdd(label, address, locked)
                    label = ""
                    address = ""
                },
                enabled = label.isNotBlank() && address.isNotBlank(),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null)
                Text(stringResource(R.string.authenticate_and_save), Modifier.padding(start = 8.dp))
            }
        }
    }
    confirmation?.let { action ->
        TrustedAddressConfirmationDialog(
            action = action,
            onDismiss = { confirmation = null },
            onConfirm = { finalCharacters ->
                confirmation = null
                when (action) {
                    is TrustedAddressConfirmation.Delete -> onDelete(action.address.id, finalCharacters)
                    is TrustedAddressConfirmation.Unlock -> onUnlock(action.address.id, finalCharacters)
                }
            },
        )
    }
}

private sealed interface TrustedAddressConfirmation {
    val address: TrustedAddressEntity

    data class Delete(
        override val address: TrustedAddressEntity,
    ) : TrustedAddressConfirmation

    data class Unlock(
        override val address: TrustedAddressEntity,
    ) : TrustedAddressConfirmation
}

@Composable
private fun TrustedAddressConfirmationDialog(
    action: TrustedAddressConfirmation,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var finalCharacters by remember(action) { mutableStateOf("") }
    var deletionAcknowledged by remember(action) { mutableStateOf(false) }
    val needsCharacters = action is TrustedAddressConfirmation.Unlock || action.address.isLocked
    val charactersMatch = !needsCharacters || finalCharacters == action.address.address.takeLast(4)
    val canConfirm =
        charactersMatch && (
            action !is TrustedAddressConfirmation.Delete ||
                action.address.isLocked || deletionAcknowledged
        )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when (action) {
                        is TrustedAddressConfirmation.Delete -> {
                            if (action.address.isLocked) {
                                R.string.unlock_and_delete_address
                            } else {
                                R.string.delete_trusted_address
                            }
                        }

                        is TrustedAddressConfirmation.Unlock -> {
                            R.string.unlock_trusted_address
                        }
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text =
                        stringResource(
                            when (action) {
                                is TrustedAddressConfirmation.Delete -> R.string.delete_trusted_address_body
                                is TrustedAddressConfirmation.Unlock -> R.string.unlock_trusted_address_body
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                AddressText(action.address.address, abbreviated = false)
                if (needsCharacters) {
                    OutlinedTextField(
                        value = finalCharacters,
                        onValueChange = { finalCharacters = it.take(4) },
                        label = { Text(stringResource(R.string.confirm_last_four)) },
                        isError = finalCharacters.isNotEmpty() && !charactersMatch,
                        singleLine = true,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = deletionAcknowledged,
                            onCheckedChange = { deletionAcknowledged = it },
                        )
                        Text(
                            text = stringResource(R.string.confirm_address_delete),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.trusted_auth_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = StartExAmber,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(finalCharacters) },
                enabled = canConfirm,
            ) {
                Text(stringResource(R.string.authenticate_and_continue), color = StartExRed)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun RevealedMnemonicScreen(
    phrase: CharArray,
    onDone: () -> Unit,
) {
    val words = remember(phrase) { phrase.concatToString().split(Regex("\\s+")).filter(String::isNotBlank) }
    ScreenColumn {
        ScreenHeader(title = stringResource(R.string.recovery_phrase))
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.revealed_phrase_warning),
            color = StartExAmber,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(14.dp))
        SectionCard {
            words.forEachIndexed { index, word ->
                Text(
                    text = "${index + 1}.  $word",
                    modifier = Modifier.padding(vertical = 5.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = onDone,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.done_clear_phrase))
        }
    }
}

@Composable
private fun SensitiveHeader(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun TextButtonWithMinimumTarget(
    text: String,
    onClick: () -> Unit,
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(text)
    }
}

private fun createQrBitmap(value: String): Bitmap? =
    runCatching {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
        val pixels = IntArray(QR_SIZE * QR_SIZE)
        for (y in 0 until QR_SIZE) {
            for (x in 0 until QR_SIZE) {
                pixels[y * QR_SIZE + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888)
    }.getOrNull()

private fun String.toSolAmountOrNull(): BigDecimal? =
    runCatching {
        trim().takeIf(String::isNotEmpty)?.let(::BigDecimal)?.takeIf {
            it > BigDecimal.ZERO && it.scale() <= SOL_DECIMAL_PLACES
        }
    }.getOrNull()

private fun String.toTrustedAddressValue(): String? {
    val value =
        trim()
            .removePrefix("solana:")
            .substringBefore('?')
            .trim()
    return value.takeIf(String::isNotEmpty)
}

private fun formatLamports(lamports: Long): String {
    val sol =
        BigDecimal
            .valueOf(lamports)
            .divide(BigDecimal.valueOf(LAMPORTS_PER_SOL), SOL_DECIMAL_PLACES, RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()
    return "$sol SOL"
}

private const val QR_SIZE = 512
private const val SOL_DECIMAL_PLACES = 9
private const val LAMPORTS_PER_SOL = 1_000_000_000L
