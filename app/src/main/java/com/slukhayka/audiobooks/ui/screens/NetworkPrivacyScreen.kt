package com.slukhayka.audiobooks.ui.screens

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slukhayka.audiobooks.data.privacy.PrivacyPrefs
import com.slukhayka.audiobooks.data.privacy.RouteMode
import com.slukhayka.audiobooks.ui.MainViewModel

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

    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = "Приватність мережі",
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
            RouteOption(
                tag = "privacy_route_direct",
                title = "Пряме з'єднання",
                subtitle = "Без проксі. Запити виглядають як звичайний браузер на вашому телефоні.",
                selected = mode == RouteMode.DIRECT,
                onSelect = { mode = RouteMode.DIRECT }
            )
            RouteOption(
                tag = "privacy_route_proxy",
                title = "Власний проксі",
                subtitle = "HTTP або SOCKS5 адреса — джерела бачать адресу проксі, а не вашу.",
                selected = mode == RouteMode.CUSTOM_PROXY,
                onSelect = { mode = RouteMode.CUSTOM_PROXY }
            )
            RouteOption(
                tag = "privacy_route_tor",
                title = "Максимальна приватність",
                subtitle = "Через Tor локально (Orbot, 127.0.0.1:9050). Потрібен запущений Orbot.",
                selected = mode == RouteMode.MAX_PRIVACY,
                onSelect = { mode = RouteMode.MAX_PRIVACY }
            )
            // spec-38 T6 (#258): the relay prototype rides the same route
            // machinery — never a default, only this conscious choice.
            RouteOption(
                tag = "privacy_route_relay",
                title = "Реле (прототип Workers)",
                subtitle = "Ваше саморозгорнуте реле: джерела бачать адресу реле, а не вашу. Довіра — оператору реле.",
                selected = mode == RouteMode.RELAY,
                onSelect = { mode = RouteMode.RELAY }
            )

            if (mode == RouteMode.CUSTOM_PROXY || mode == RouteMode.RELAY) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
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
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("privacy_error_text")
                )
            }

            // spec-38 T4 (#256): the DNS half of the door — independent of the
            // route above, on by default, transparent system fallback.
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .testTag("privacy_doh_row"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Шифрування DNS (DoH)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Імена доменів ідуть зашифрованими до публічного DNS, тож провайдер не бачить, які сайти відкриває застосунок. Якщо DoH недоступний — автоматично працює системний резолвер.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = dohEnabled,
                    onCheckedChange = { dohEnabled = it },
                    modifier = Modifier.testTag("privacy_doh_switch")
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    viewModel.savePrivacyPrefs(PrivacyPrefs(mode, address.trim(), dohEnabled))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("privacy_apply_button")
            ) {
                Text("Застосувати")
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
