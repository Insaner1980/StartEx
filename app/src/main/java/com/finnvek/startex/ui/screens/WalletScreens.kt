package com.finnvek.startex.ui.screens

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.finnvek.startex.R
import com.finnvek.startex.data.local.TrustedAddressEntity
import com.finnvek.startex.formatUserNumber
import com.finnvek.startex.parseSolAmount
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
import com.finnvek.startex.wallet.MAX_MNEMONIC_INPUT_CHAR_COUNT
import com.finnvek.startex.wallet.ManualTransferStatus
import com.finnvek.startex.wallet.SolanaAddressValidator
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun WalletFlowScreen(
    state: WalletSetupState,
    onMnemonicSave: () -> Unit,
    onVerifyBackup: (Map<Int, String>) -> Unit,
    onRestore: (CharArray) -> Unit,
    onSave: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(
        enabled = state !is WalletSetupState.Saving,
        onBack = onCancel,
    )
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
            val words =
                remember(state.phrase) {
                    state.phrase.toDisplayWords()
                }
            MnemonicBackupScreen(
                words = words,
                publicAddress = state.publicAddress,
                onContinue = onMnemonicSave,
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
@Suppress("LongParameterList")
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
    qrBitmapFactory: (String) -> Bitmap? = ::createQrBitmap,
) {
    BackHandler {
        if (transferState != WalletTransferState.Submitting) onDismiss()
    }
    when (overlay) {
        WalletOverlay.None -> {
            Unit
        }

        WalletOverlay.Receive -> {
            ReceiveScreen(state, onDismiss, onRefreshBalance, qrBitmapFactory)
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
            val words =
                remember(overlay.phrase) {
                    overlay.phrase.toDisplayWords()
                }
            RevealedMnemonicScreen(
                words = words,
                onDone = onDismiss,
            )
        }
    }
}

@Composable
private fun MnemonicBackupScreen(
    words: List<String>,
    publicAddress: String,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    var savedOffline by rememberSaveable { mutableStateOf(false) }
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
                                text =
                                    stringResource(
                                        R.string.backup_word_list_item,
                                        indexedWord.index + 1,
                                        indexedWord.value,
                                    ),
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
        AddressText(publicAddress, abbreviated = false)
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = savedOffline,
                        role = Role.Checkbox,
                        onValueChange = { savedOffline = it },
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = savedOffline, onCheckedChange = null)
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
    val focusManager = LocalFocusManager.current
    val firstFieldFocusRequester = remember(state.wordNumbers) { FocusRequester() }
    val answersComplete = state.wordNumbers.all { answers[it].orEmpty().isNotBlank() }
    val submitAnswers = {
        if (answersComplete) {
            focusManager.clearFocus()
            onVerify(answers.toMap())
        }
    }
    LaunchedEffect(state.error) {
        if (state.error != null) firstFieldFocusRequester.requestFocus()
    }
    ScreenColumn {
        SensitiveHeader(stringResource(R.string.backup_quiz_title), onCancel)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.backup_quiz_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(18.dp))
        state.wordNumbers.forEachIndexed { index, number ->
            OutlinedTextField(
                value = answers[number].orEmpty(),
                onValueChange = { answers[number] = it.trim().lowercase() },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .then(
                            if (index == 0) Modifier.focusRequester(firstFieldFocusRequester) else Modifier,
                        ),
                label = { Text(stringResource(R.string.backup_word_number, number)) },
                singleLine = true,
                isError = state.error != null,
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = if (index == state.wordNumbers.lastIndex) ImeAction.Done else ImeAction.Next,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Next) },
                        onDone = { submitAnswers() },
                    ),
                visualTransformation = PasswordVisualTransformation(),
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
            onClick = submitAnswers,
            enabled = answersComplete,
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
    var visible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val phraseFocusRequester = remember { FocusRequester() }
    val submitPhrase = {
        if (phrase.isNotBlank()) {
            val secret = phrase.trim().toCharArray()
            phrase = ""
            visible = false
            focusManager.clearFocus()
            onRestore(secret)
        }
    }
    LaunchedEffect(state.error) {
        if (state.error != null) phraseFocusRequester.requestFocus()
    }
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
            onValueChange = { phrase = it.take(MAX_MNEMONIC_INPUT_CHAR_COUNT + 1) },
            modifier = Modifier.fillMaxWidth().focusRequester(phraseFocusRequester),
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
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { submitPhrase() }),
            minLines = 4,
            isError = state.error != null,
        )
        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = submitPhrase,
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
    onSave: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var dedicatedWalletConfirmed by rememberSaveable { mutableStateOf(false) }
    var restoredBackupConfirmed by rememberSaveable { mutableStateOf(false) }
    ScreenColumn {
        SensitiveHeader(stringResource(R.string.wallet_review_title), onCancel)
        Spacer(modifier = Modifier.height(14.dp))
        SectionCard {
            SectionHeading(
                stringResource(if (state.restored) R.string.restored_wallet else R.string.new_wallet),
                subtitle = stringResource(R.string.network_mainnet),
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
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = dedicatedWalletConfirmed,
                        role = Role.Checkbox,
                        onValueChange = { dedicatedWalletConfirmed = it },
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = dedicatedWalletConfirmed,
                onCheckedChange = null,
            )
            Text(
                text = stringResource(R.string.wallet_dedicated_confirmation),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (state.restored) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = restoredBackupConfirmed,
                            role = Role.Checkbox,
                            onValueChange = { restoredBackupConfirmed = it },
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = restoredBackupConfirmed,
                    onCheckedChange = null,
                )
                Text(
                    text = stringResource(R.string.restore_backup_confirmation),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Text(
            text = stringResource(R.string.wallet_save_auth_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(
            onClick = { onSave(restoredBackupConfirmed) },
            enabled = dedicatedWalletConfirmed && (!state.restored || restoredBackupConfirmed),
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
    qrBitmapFactory: (String) -> Bitmap?,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val address = remember(state.walletAddress) { receiveAddressOrNull(state.walletAddress) }
    var addressCopied by rememberSaveable(address) { mutableStateOf(false) }
    val shareAddressTitle = stringResource(R.string.share_address)
    val qr = remember(address, qrBitmapFactory) { address?.let(qrBitmapFactory) }
    ScreenColumn {
        SensitiveHeader(stringResource(R.string.receive_sol), onBack)
        Spacer(modifier = Modifier.height(14.dp))
        Surface(
            modifier =
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = 240.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f),
            color = Color.White,
            shape = RoundedCornerShape(12.dp),
        ) {
            if (qr == null) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.Black,
                    )
                    Text(
                        text = stringResource(R.string.qr_unavailable),
                        color = Color.Black,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            } else {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = stringResource(R.string.wallet_address_qr),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        SectionCard {
            SectionHeading(
                stringResource(R.string.your_solana_address),
                subtitle = stringResource(R.string.network_mainnet),
            )
            Spacer(modifier = Modifier.height(10.dp))
            if (address == null) {
                Text(
                    text = stringResource(R.string.receive_address_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = StartExAmber,
                )
            } else {
                AddressText(address, abbreviated = false)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SectionCard {
            // CPD-OFF
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionHeading(stringResource(R.string.wallet_balance))
                    Text(
                        text =
                            state.walletBalanceLamports?.let { formatLamports(it) }
                                ?: stringResource(R.string.balance_unavailable),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    state.walletBalanceEur?.let { value ->
                        Text(
                            text =
                                stringResource(
                                    R.string.eur_balance_value,
                                    formatUserNumber(
                                        value = value.setScale(2, RoundingMode.HALF_UP),
                                        maximumFractionDigits = 2,
                                        locale = LocalConfiguration.current.locales[0],
                                    ),
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val refreshBalanceDescription =
                    stringResource(
                        if (state.walletBalanceLoading) R.string.balance_loading else R.string.refresh_balance,
                    )
                IconButton(
                    onClick = onRefreshBalance,
                    enabled = address != null && !state.walletBalanceLoading,
                    modifier = Modifier.semantics { contentDescription = refreshBalanceDescription },
                ) {
                    if (state.walletBalanceLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
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
            // CPD-ON
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
                onClick = {
                    address?.let { shareableAddress ->
                        clipboardScope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, shareableAddress)))
                            addressCopied = true
                        }
                    }
                },
                enabled = address != null,
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
                    address?.let { shareableAddress ->
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, shareableAddress)
                                },
                                shareAddressTitle,
                            ),
                        )
                    }
                },
                enabled = address != null,
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null)
                Text(stringResource(R.string.share), Modifier.padding(start = 8.dp))
            }
        }
        if (addressCopied) {
            Text(
                text = stringResource(R.string.address_copied),
                style = MaterialTheme.typography.bodySmall,
                color = StartExGreen,
                modifier =
                    Modifier
                        .padding(top = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
            )
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
    val focusManager = LocalFocusManager.current
    val canPrepare = selected != null && amountIsValid && monitoringAllowsTransfer && noOpenPositions
    val prepareTransfer = prepareTransfer@{
        val selectedAddress = selected ?: return@prepareTransfer
        if (!amountIsValid || !monitoringAllowsTransfer || !noOpenPositions) return@prepareTransfer
        focusManager.clearFocus()
        onPrepare(selectedAddress.id, amount)
    }
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
                                .heightIn(min = 56.dp)
                                .selectable(
                                    selected = selectedId == address.id,
                                    role = Role.RadioButton,
                                    onClick = { selectedId = address.id },
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedId == address.id,
                            onClick = null,
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
                onValueChange = {
                    amount = it.filter { character -> character.isDigit() || character == '.' || character == ',' }
                },
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
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.ContentOrLtr),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { prepareTransfer() }),
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
                onClick = prepareTransfer,
                enabled = canPrepare,
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
    val focusManager = LocalFocusManager.current
    val submitTransfer = {
        if (matches) {
            focusManager.clearFocus()
            onSubmit(finalCharacters)
        }
    }
    ScreenColumn {
        SensitiveHeader(stringResource(R.string.review_transaction), onBack)
        Spacer(modifier = Modifier.height(12.dp))
        SectionCard {
            SectionHeading(
                review.destinationLabel,
                subtitle = stringResource(R.string.network_mainnet),
            )
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
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { submitTransfer() }),
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
            onClick = submitTransfer,
            enabled = matches,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
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
                modifier =
                    Modifier
                        .padding(top = 18.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
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
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
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
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                subtitle = stringResource(detailMessage),
            )
            Spacer(modifier = Modifier.height(14.dp))
            AddressText(signature, abbreviated = false)
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        clipboardScope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, signature)))
                        }
                    },
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
                            Intent(Intent.ACTION_VIEW, "https://solscan.io/tx/$signature".toUri()),
                        )
                    },
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
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
        SectionCard(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
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
    var scanError by remember { mutableStateOf<Int?>(null) }
    var addressError by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<TrustedAddressConfirmation?>(null) }
    val focusManager = LocalFocusManager.current
    val addressFocusRequester = remember { FocusRequester() }
    val addressValidator = remember { SolanaAddressValidator() }
    LaunchedEffect(scanError, addressError) {
        if (scanError != null || addressError) addressFocusRequester.requestFocus()
    }
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
            SectionCard(modifier = Modifier.focusRestorer()) {
                addresses.forEachIndexed { index, item ->
                    key(item.id) {
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
                                AddressText(item.address, abbreviated = false)
                                Text(
                                    text =
                                        stringResource(
                                            if (item.isLocked) {
                                                R.string.trusted_address_locked_state
                                            } else {
                                                R.string.trusted_address_unlocked_state
                                            },
                                        ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text =
                                        stringResource(
                                            if (item.firstTransferVerifiedAtMillis == null) {
                                                R.string.trusted_address_first_transfer_pending
                                            } else {
                                                R.string.trusted_address_first_transfer_verified
                                            },
                                        ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (item.isLocked) {
                                IconButton(
                                    onClick = { confirmation = TrustedAddressConfirmation.Unlock(item) },
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.LockOpen,
                                        contentDescription = stringResource(R.string.unlock_trusted_address),
                                        tint = StartExGreen,
                                    )
                                }
                            }
                            IconButton(
                                onClick = { confirmation = TrustedAddressConfirmation.Delete(item) },
                            ) {
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions =
                    KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it.trim()
                    addressError = false
                    scanError = null
                },
                modifier = Modifier.fillMaxWidth().focusRequester(addressFocusRequester),
                label = { Text(stringResource(R.string.solana_address)) },
                minLines = 2,
                isError = addressError,
                supportingText =
                    if (addressError) {
                        { Text(stringResource(R.string.trusted_address_invalid)) }
                    } else {
                        null
                    },
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            )
            OutlinedButton(
                onClick = {
                    scanError = null
                    scanner
                        .startScan()
                        .addOnSuccessListener { barcode ->
                            val scanned = barcode.rawValue?.toTrustedAddressValue()
                            if (scanned == null) {
                                scanError = R.string.address_scan_failed
                            } else {
                                address = scanned
                                addressError = false
                            }
                        }.addOnCanceledListener { scanError = null }
                        .addOnFailureListener { error ->
                            scanError =
                                if (error.isCodeScannerCancellation()) {
                                    null
                                } else {
                                    R.string.address_scanner_unavailable
                                }
                        }
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
            scanError?.let { message ->
                Text(
                    text = stringResource(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = StartExAmber,
                    modifier =
                        Modifier
                            .padding(top = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = locked,
                            role = Role.Checkbox,
                            onValueChange = { locked = it },
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = locked, onCheckedChange = null)
                Text(stringResource(R.string.lock_trusted_address), Modifier.weight(1f))
            }
            Text(
                text = stringResource(R.string.trusted_auth_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.trusted_address_save_requirements),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    val normalizedAddress = addressValidator.normalize(address)
                    if (normalizedAddress == null) {
                        addressError = true
                    } else {
                        onAdd(label, normalizedAddress, locked)
                        label = ""
                        address = ""
                        locked = true
                    }
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
    val focusManager = LocalFocusManager.current
    val confirmationFocusRequester = remember(action) { FocusRequester() }
    val confirm = {
        if (canConfirm) {
            focusManager.clearFocus()
            onConfirm(finalCharacters)
        }
    }
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
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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
                    LaunchedEffect(action) {
                        confirmationFocusRequester.requestFocus()
                    }
                    OutlinedTextField(
                        value = finalCharacters,
                        onValueChange = { finalCharacters = it.take(4) },
                        modifier = Modifier.focusRequester(confirmationFocusRequester),
                        label = { Text(stringResource(R.string.confirm_last_four)) },
                        isError = finalCharacters.isNotEmpty() && !charactersMatch,
                        supportingText = {
                            Text(
                                stringResource(
                                    if (finalCharacters.isEmpty() || charactersMatch) {
                                        R.string.confirm_last_four_help
                                    } else {
                                        R.string.confirm_last_four_mismatch
                                    },
                                ),
                            )
                        },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        keyboardOptions =
                            KeyboardOptions(
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done,
                            ),
                        keyboardActions = KeyboardActions(onDone = { confirm() }),
                    )
                } else {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = deletionAcknowledged,
                                    role = Role.Checkbox,
                                    onValueChange = { deletionAcknowledged = it },
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = deletionAcknowledged,
                            onCheckedChange = null,
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
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.TextButton(
                    onClick = confirm,
                    enabled = canConfirm,
                    colors = ButtonDefaults.textButtonColors(contentColor = StartExRed),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.authenticate_and_continue))
                }
                androidx.compose.material3.TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun RevealedMnemonicScreen(
    words: List<String>,
    onDone: () -> Unit,
) {
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
                    text = stringResource(R.string.backup_word_list_item, index + 1, word),
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
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier =
                Modifier
                    .padding(start = 6.dp)
                    .semantics { heading() },
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

internal fun receiveAddressOrNull(address: String?): String? = address?.takeIf { SolanaAddressValidator().normalize(it) == it }

private fun String.toSolAmountOrNull(): BigDecimal? = parseSolAmount(this)

internal fun String.toTrustedAddressValue(): String? {
    val value =
        trim()
            .removePrefix("solana:")
            .substringBefore('?')
            .trim()
    return SolanaAddressValidator().normalize(value)
}

private fun Exception.isCodeScannerCancellation(): Boolean = this is MlKitException && isCodeScannerCancellation(errorCode)

internal fun isCodeScannerCancellation(errorCode: Int): Boolean = errorCode == MlKitException.CODE_SCANNER_CANCELLED

private fun CharArray.toDisplayWords(): List<String> =
    buildList {
        var start = -1
        this@toDisplayWords.forEachIndexed { index, character ->
            if (character.isWhitespace()) {
                if (start >= 0) {
                    add(this@toDisplayWords.concatToString(start, index))
                    start = -1
                }
            } else if (start < 0) {
                start = index
            }
        }
        if (start >= 0) add(this@toDisplayWords.concatToString(start, this@toDisplayWords.size))
    }

@Composable
private fun formatLamports(lamports: Long): String {
    val sol =
        BigDecimal
            .valueOf(lamports)
            .divide(BigDecimal.valueOf(LAMPORTS_PER_SOL), SOL_DECIMAL_PLACES, RoundingMode.DOWN)
            .stripTrailingZeros()
    val value =
        formatUserNumber(
            value = sol,
            maximumFractionDigits = SOL_DECIMAL_PLACES,
            locale = LocalConfiguration.current.locales[0],
        )
    return stringResource(R.string.sol_balance_value, value)
}

private const val QR_SIZE = 512
private const val SOL_DECIMAL_PLACES = 9
private const val LAMPORTS_PER_SOL = 1_000_000_000L
