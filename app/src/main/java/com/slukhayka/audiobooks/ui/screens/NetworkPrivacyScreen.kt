package com.slukhayka.audiobooks.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.R
import com.slukhayka.audiobooks.data.privacy.PrivacyPrefs
import com.slukhayka.audiobooks.data.privacy.RouteMode
import com.slukhayka.audiobooks.ui.MainViewModel
import com.slukhayka.audiobooks.ui.components.accessibilityPane

/**
 * spec-38 T2 (#254) — the «Приватність мережі» destination: the route the
 * transport rides (direct / custom proxy / Tor / relay prototype, spec-38
 * T6), reached from the Медіатека ⋮
 * overflow menu like the storage destination beside it (ADR-0018: a rare
 * settings surface is a pushed screen, not a tab). Saving persists through
 * the store and re-installs the resolved route into the process-wide door;
 * an address that does not parse is rejected with its reason — nothing is
 * half-saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkPrivacyScreen(
    viewModel: MainViewModel,
    onBackClick: () -> Unit
) {
    val savedPrefs by viewModel.privacyPrefs.collectAsState()
    val error by viewModel.privacyError.collectAsState()

    // Local draft state: edits stay local until «Застосувати» validates them.
    var mode by remember { mutableStateOf(savedPrefs.routeMode) }
    var address by remember { mutableStateOf(savedPrefs.proxyAddress) }
    var dohEnabled by remember { mutableStateOf(savedPrefs.dohEnabled) }
    var savedNotice by remember { mutableStateOf<String?>(null) }
    var saveResultRevision by remember { mutableIntStateOf(0) }
    val screenTitle = stringResource(R.string.privacy_title)
    val savedMessage = stringResource(R.string.privacy_saved)

    Scaffold(
        modifier = Modifier.accessibilityPane(screenTitle),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = screenTitle,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.semantics { heading() }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
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
                .testTag("network_privacy_screen")
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Куди йде трафік джерел книг. За замовчуванням — прямо, як звичайний браузер.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Column(Modifier.selectableGroup()) {
                RouteOption(
                    tag = "privacy_route_direct",
                    title = stringResource(R.string.privacy_route_direct_title),
                    subtitle = stringResource(R.string.privacy_route_direct_description),
                    selected = mode == RouteMode.DIRECT,
                    onSelect = {
                        mode = RouteMode.DIRECT
                        savedNotice = null
                    }
                )
                RouteOption(
                    tag = "privacy_route_proxy",
                    title = stringResource(R.string.privacy_route_proxy_title),
                    subtitle = stringResource(R.string.privacy_route_proxy_description),
                    selected = mode == RouteMode.CUSTOM_PROXY,
                    onSelect = {
                        mode = RouteMode.CUSTOM_PROXY
                        savedNotice = null
                    }
                )
                RouteOption(
                    tag = "privacy_route_tor",
                    title = stringResource(R.string.privacy_route_tor_title),
                    subtitle = stringResource(R.string.privacy_route_tor_description),
                    selected = mode == RouteMode.MAX_PRIVACY,
                    onSelect = {
                        mode = RouteMode.MAX_PRIVACY
                        savedNotice = null
                    }
                )
                // spec-38 T6 (#258): the relay prototype rides the same route
                // machinery — never a default, only this conscious choice.
                RouteOption(
                    tag = "privacy_route_relay",
                    title = stringResource(R.string.privacy_route_relay_title),
                    subtitle = stringResource(R.string.privacy_route_relay_description),
                    selected = mode == RouteMode.RELAY,
                    onSelect = {
                        mode = RouteMode.RELAY
                        savedNotice = null
                    }
                )
            }

            if (mode == RouteMode.CUSTOM_PROXY || mode == RouteMode.RELAY) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        savedNotice = null
                    },
                    label = { Text(if (mode == RouteMode.RELAY) "Адреса реле" else "Адреса проксі") },
                    placeholder = {
                        Text(
                            if (mode == RouteMode.RELAY) "https://slukhayka-relay.example.workers.dev"
                            else "192.168.1.10:8080"
                        )
                    },
                    supportingText = {
                        Text(
                            if (mode == RouteMode.RELAY) "Формат: повне https://-посилання на воркер-реле"
                            else "Формат: host:порт або socks5://host:порт"
                        )
                    },
                    isError = error != null,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(
                            if (mode == RouteMode.RELAY) "privacy_relay_address_field"
                            else "privacy_proxy_address_field"
                        )
                )
            }

            if (error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                key(error, saveResultRevision) {
                    Text(
                        text = stringResource(R.string.privacy_error, error.orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .testTag("privacy_error_text")
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }

            // spec-38 T4 (#256): the DNS half of the door — independent of the
            // route above, on by default, transparent system fallback.
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))
            SettingsSwitchRow(
                title = stringResource(R.string.privacy_doh_title),
                description = stringResource(R.string.privacy_doh_description),
                checked = dohEnabled,
                onCheckedChange = {
                    dohEnabled = it
                    savedNotice = null
                },
                testTag = "privacy_doh_row"
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    viewModel.savePrivacyPrefs(PrivacyPrefs(mode, address.trim(), dohEnabled))
                    saveResultRevision += 1
                    savedNotice = if (viewModel.privacyError.value == null) {
                        savedMessage
                    } else {
                        null
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("privacy_apply_button")
            ) {
                Text(stringResource(R.string.action_apply))
            }
            savedNotice?.let { notice ->
                Spacer(modifier = Modifier.height(8.dp))
                key(notice, saveResultRevision) {
                    Text(
                        text = notice,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .testTag("privacy_saved_notice")
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Якщо обраний маршрут недоступний, запити завершуються помилкою — застосунок ніколи не повертається до прямого з'єднання мовчки.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RouteOption(
    tag: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    SettingsRadioOption(
        title = title,
        description = subtitle,
        selected = selected,
        onSelect = onSelect,
        testTag = tag
    )
}
