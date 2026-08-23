package com.slukhayka.audiobooks.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.identity.ListenerIdentity
import com.slukhayka.audiobooks.data.identity.ListenerProfile
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
    onUnhideAuthor: (String) -> Unit = {}
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

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = "Профіль",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("profile_screen")
        ) {
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
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
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
                        savedNotice = "Нік збережено"
                    }
                },
                enabled = profile != null && nicknameDraft.isNotBlank(),
                shape = RoundedCornerShape(AppDimens.RadiusCard),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
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
                    modifier = Modifier.testTag("profile_saved_notice")
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
                    color = MaterialTheme.colorScheme.onSurface
                )
                hiddenAuthors.forEach { author ->
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
                        TextButton(onClick = { onUnhideAuthor(author) }) {
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
            RecoverySection(identity, scope)
        }
    }
}

@Composable
private fun RecoverySection(identity: ListenerIdentity, scope: kotlinx.coroutines.CoroutineScope) {
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Код відновлення профілю",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
    )
    Text(
        text = "Код переносить профіль на новий телефон: встановіть застосунок, відкрийте Профіль і введіть код. Показ коду вимагає підтвердження біометрією або PIN-кодом пристрою.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(8.dp))

    val context = LocalContext.current
    var recoveryCode by remember { mutableStateOf<String?>(null) }
    var biometricError by remember { mutableStateOf<String?>(null) }
    var restoreDraft by remember { mutableStateOf("") }
    var restoreNotice by remember { mutableStateOf<String?>(null) }

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
            biometricError = "Біометричне підтвердження недоступне на цьому пристрої."
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
                            biometricError =
                                "Для цього профілю ще немає коду — він з'явиться після першого підключення до мережі."
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
            .setTitle("Підтвердіть, що це ви")
            .setSubtitle("Щоб показати код відновлення профілю")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    OutlinedButton(
        onClick = { revealCode() },
        shape = RoundedCornerShape(AppDimens.RadiusCard),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("profile_recovery_reveal")
    ) {
        Text(if (recoveryCode == null) "Показати код" else "Приховати код")
    }

    if (biometricError != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = biometricError.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("profile_recovery_error")
        )
    }

    if (recoveryCode != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = recoveryCode.orEmpty(),
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
                    clipboard.setPrimaryClip(ClipData.newPlainText("slukhayka_recovery", recoveryCode))
                },
                modifier = Modifier.testTag("profile_recovery_copy")
            ) {
                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Копіювати код")
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Відновити з коду",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = restoreDraft,
        onValueChange = {
            restoreDraft = it.trim()
            restoreNotice = null
        },
        label = { Text("Код із попереднього телефона") },
        singleLine = true,
        isError = restoreNotice?.startsWith("Не") == true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_restore_field")
    )
    Spacer(modifier = Modifier.height(12.dp))
    Button(
        onClick = {
            scope.launch {
                val restored = identity.restoreFromCode(restoreDraft)
                restoreNotice = if (restored != null) {
                    "Профіль відновлено: ${restored.nickname}"
                } else {
                    "Не вдалося відновити — перевірте код і з'єднання."
                }
            }
        },
        enabled = restoreDraft.isNotBlank(),
        shape = RoundedCornerShape(AppDimens.RadiusCard),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("profile_restore_button")
    ) {
        Text("Відновити профіль")
    }
    if (restoreNotice != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = restoreNotice.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("profile_restore_notice")
        )
    }
}
