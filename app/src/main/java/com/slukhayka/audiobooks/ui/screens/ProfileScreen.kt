package com.slukhayka.audiobooks.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.identity.ListenerIdentity
import com.slukhayka.audiobooks.data.identity.ListenerProfile
import com.slukhayka.audiobooks.data.listening.ProgressSyncSettingsStore
import com.slukhayka.audiobooks.ui.components.accessibilityPane
import com.slukhayka.audiobooks.ui.components.accessibilityModalBackground
import com.slukhayka.audiobooks.ui.theme.AppDimens
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch

/**
 * Spec-40 #275 (t1) — the ⚙️ Профіль destination: the one surface where the
 * silent listener identity becomes visible. There are NO login screens by
 * design — the profile bootstraps itself on first launch; here the listener
 * sees the auto-generated nickname and can change it. Reached from the
 * Медіатека ⋮ overflow menu like the storage and privacy destinations beside
 * it (ADR-0018: a rare settings surface is a pushed screen, not a tab).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    identity: ListenerIdentity,
    onBackClick: () -> Unit,
    hiddenAuthors: List<String> = emptyList(),
    onUnhideAuthor: (String) -> Unit = {},
    // ADR-0023 (spec-43 T6): the visible Progress Sync switch — the screen
    // reads the settings store directly (ADR-0008); defaults keep existing
    // call sites and previews unchanged.
    progressSyncSettings: ProgressSyncSettingsStore? = null
) {
    // The module is read directly (ADR-0008); suspend calls ride the
    // composition scope like every other screen.
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<ListenerProfile?>(null) }
    LaunchedEffect(identity) {
        profile = identity.current() ?: identity.ensure()
    }

    var nicknameDraft by remember(profile) {
        mutableStateOf(profile?.nickname.orEmpty())
    }
    var savedNotice by remember { mutableStateOf<String?>(null) }
    var restoreDialogVisible by remember { mutableStateOf(false) }
    val loadingDescription = stringResource(R.string.profile_loading)
    val nicknameSavedMessage = stringResource(R.string.profile_nickname_saved)

    SettingsDestinationScaffold(
        destination = SettingsDestination.Profile,
        onBackClick = onBackClick,
        modalVisible = restoreDialogVisible
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("profile_screen")
        ) {
            if (profile == null) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .testTag("profile_loading")
                        .semantics {
                            contentDescription = loadingDescription
                        }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Профіль створюється сам, без реєстрації. Нік бачите лише ви.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Нік",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = nicknameDraft,
                onValueChange = {
                    nicknameDraft = it
                    savedNotice = null
                },
                label = { Text("Як вас показувати") },
                supportingText = {
                    Text("До 40 символів. Профіль існує без жодного входу.")
                },
                singleLine = true,
                isError = nicknameDraft.isBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_nickname_field")
            )

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    val current = profile ?: return@Button
                    scope.launch {
                        identity.setNickname(nicknameDraft)
                        profile = identity.current()
                        savedNotice = nicknameSavedMessage
                    }
                },
                enabled = profile != null && nicknameDraft.isNotBlank(),
                shape = RoundedCornerShape(AppDimens.RadiusCard),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("profile_nickname_save")
            ) {
                Text("Зберегти нік")
            }
            if (savedNotice != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = savedNotice.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .testTag("profile_saved_notice")
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // spec-40 #281 (t7) — the reversible local mute: the device's own
            // hidden-reviewers list. Un-muting here restores the author's
            // reviews on THIS phone only; nothing ever leaves for a server.
            if (hiddenAuthors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Приховані автори",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )
                hiddenAuthors.forEach { author ->
                    val unhideDescription = stringResource(
                        R.string.profile_unhide_author_description,
                        author
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            text = author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        TextButton(
                            onClick = { onUnhideAuthor(author) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics {
                                    contentDescription = unhideDescription
                                }
                        ) {
                            Text("Розмютити")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // spec-40 #276 (t2): «Код відновлення профілю» — the encoded
            // credential pair. Shown ONLY behind BiometricPrompt, copyable,
            // and the one way onto a phone where neither Auto Backup nor the
            // device binding reached.
            RecoverySection(
                identity = identity,
                scope = scope,
                onDialogVisibilityChange = { restoreDialogVisible = it }
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Синхронізація прогресу",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_sync_row"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                if (progressSyncSettings != null) {
                    val syncEnabled by progressSyncSettings.enabled.collectAsState()
                    SettingsSwitchRow(
                        title = "Позиція дзеркалиться між вашими пристроями",
                        description = "Нічого не надсилається без вашого профілю. " +
                            "Вимкнено — усе лишається на телефоні.",
                        checked = syncEnabled,
                        onCheckedChange = progressSyncSettings::setEnabled,
                        testTag = "profile_sync_switch",
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                } else {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Позиція дзеркалиться між вашими пристроями",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Нічого не надсилається без вашого профілю. " +
                                    "Вимкнено — усе лишається на телефоні.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoverySection(
    identity: ListenerIdentity,
    scope: kotlinx.coroutines.CoroutineScope,
    onDialogVisibilityChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var recoveryCode by remember { mutableStateOf<String?>(null) }
    var biometricError by remember { mutableStateOf<String?>(null) }
    var copyNotice by remember { mutableStateOf<String?>(null) }
    var restoreDraft by remember { mutableStateOf("") }
    var restoreNotice by remember { mutableStateOf<String?>(null) }
    var restoreFailed by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var shouldRestoreFocus by remember { mutableStateOf(false) }
    val restoreFocusRequester = remember { FocusRequester() }
    val dialogFocusRequester = remember { FocusRequester() }

    val biometricUnavailable = stringResource(R.string.profile_biometric_unavailable)
    val recoveryUnavailable = stringResource(R.string.profile_recovery_unavailable)
    val biometricTitle = stringResource(R.string.profile_biometric_title)
    val biometricSubtitle = stringResource(R.string.profile_biometric_subtitle)

    LaunchedEffect(confirmRestore) {
        if (!confirmRestore && shouldRestoreFocus) {
            restoreFocusRequester.requestFocus()
            shouldRestoreFocus = false
        }
    }

    // BiometricPrompt lives on a FragmentActivity (MainActivity is one for
    // exactly this reason); without one the section degrades honestly.
    val fragmentActivity = remember(context) {
        var current: Context = context
        while (current is ContextWrapper) {
            if (current is FragmentActivity) return@remember current
            current = current.baseContext
        }
        current as? FragmentActivity
    }

    fun revealCode() {
        val activity = fragmentActivity ?: run {
            biometricError = biometricUnavailable
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    scope.launch {
                        recoveryCode = identity.recoveryCode()
                        if (recoveryCode == null) {
                            biometricError = recoveryUnavailable
                        } else {
                            biometricError = null
                        }
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED
                    ) return
                    biometricError = errString.toString()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(biometricTitle)
            .setSubtitle(biometricSubtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .accessibilityModalBackground(confirmRestore)
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.profile_recovery_heading),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = stringResource(R.string.profile_recovery_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                if (recoveryCode == null) revealCode() else recoveryCode = null
                copyNotice = null
            },
            shape = RoundedCornerShape(AppDimens.RadiusCard),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("profile_recovery_reveal")
        ) {
            Text(
                if (recoveryCode == null) {
                    stringResource(R.string.profile_recovery_show)
                } else {
                    stringResource(R.string.profile_recovery_hide)
                }
            )
        }

        biometricError?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .testTag("profile_recovery_error")
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        recoveryCode?.let { code ->
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("profile_recovery_code")
                )
                IconButton(
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("slukhayka_recovery", code))
                        copyNotice = context.getString(R.string.profile_recovery_copied)
                    },
                    modifier = Modifier.testTag("profile_recovery_copy")
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.profile_recovery_copy)
                    )
                }
            }
            copyNotice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .testTag("profile_recovery_copy_notice")
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.profile_restore_heading),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = stringResource(R.string.profile_restore_consequence),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = restoreDraft,
            onValueChange = {
                restoreDraft = it.trim()
                restoreNotice = null
                restoreFailed = false
            },
            label = { Text(stringResource(R.string.profile_restore_field_label)) },
            singleLine = true,
            isError = restoreFailed,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("profile_restore_field")
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                shouldRestoreFocus = true
                confirmRestore = true
                onDialogVisibilityChange(true)
            },
            enabled = restoreDraft.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ),
            shape = RoundedCornerShape(AppDimens.RadiusCard),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .focusRequester(restoreFocusRequester)
                .testTag("profile_restore_button")
        ) {
            Text(stringResource(R.string.profile_restore_action))
        }
        restoreNotice?.let { notice ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = if (restoreFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .testTag("profile_restore_notice")
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                    }
            )
        }
    }

    if (confirmRestore) {
        val dialogTitle = stringResource(R.string.profile_restore_dialog_title)
        AlertDialog(
            onDismissRequest = {
                confirmRestore = false
                onDialogVisibilityChange(false)
            },
            modifier = Modifier
                .testTag("profile_restore_dialog")
                .accessibilityPane(dialogTitle),
            title = {
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    dialogFocusRequester.requestFocus()
                }
                Text(
                    dialogTitle,
                    modifier = Modifier
                        .focusRequester(dialogFocusRequester)
                        .focusable()
                        .testTag("profile_restore_dialog_heading")
                        .semantics { heading() }
                )
            },
            text = { Text(stringResource(R.string.profile_restore_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRestore = false
                        onDialogVisibilityChange(false)
                        restoreNotice = null
                        scope.launch {
                            val restored = identity.restoreFromCode(restoreDraft)
                            restoreFailed = restored == null
                            restoreNotice = if (restored != null) {
                                context.getString(R.string.profile_restore_success, restored.nickname)
                            } else {
                                context.getString(R.string.profile_restore_error)
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("profile_restore_confirm")
                ) {
                    Text(stringResource(R.string.profile_restore_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmRestore = false
                        onDialogVisibilityChange(false)
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
