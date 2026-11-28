package com.slukhayka.audiobooks.ui.screens

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.slukhayka.audiobooks.data.downloads.DownloadNotificationService

/**
 * The system-side channel ids behind the read-only rows of the settings
 * notification section. They are the SAME ids the services create, so the
 * quiet link opens the channel the listener actually sees in Android.
 *
 * Playback belongs to Media3's `DefaultMediaNotificationProvider` and is not
 * ours to name: `default_channel_id` is that provider's public constant
 * (`DEFAULT_CHANNEL_ID`). It is repeated here instead of referenced because
 * the provider class is `@UnstableApi`; [openSystemNotificationSettings] falls
 * back to the app-level notification screen if the channel does not exist yet.
 */
internal const val PLAYBACK_NOTIFICATION_CHANNEL_ID = "default_channel_id"
internal const val DOWNLOADS_NOTIFICATION_CHANNEL_ID = DownloadNotificationService.CHANNEL_ID

/**
 * Opens the system screen for [channelId], or the app's notification settings
 * when the channel is absent (it is created lazily on first use) or the OS
 * cannot address channels.
 *
 * ADR-0052 §2: the app's switch stops posting; only Android can silence the
 * channel itself, so this link is the honest escape hatch. Nothing here
 * changes channel importance — Android does not allow that after creation.
 */
internal fun openSystemNotificationSettings(context: Context, channelId: String?) {
    val intent = systemNotificationSettingsIntent(context, channelId)
    // The caller may hold an application context (preview/test): a missing
    // Settings activity must not take the app down.
    runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * API 24-25 have no per-channel screen and no app-notification screen; the
 * closest useful door is the app's system details page. Both constants used
 * there exist since API 9, so the minSdk=24 build does not inline a newer API.
 */
internal fun systemNotificationSettingsIntent(
    context: Context,
    channelId: String?,
    sdkInt: Int = Build.VERSION.SDK_INT,
): Intent {
    if (sdkInt < Build.VERSION_CODES.O) {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
    }
    if (channelId != null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelExists = runCatching {
            manager?.getNotificationChannel(channelId) != null
        }.getOrDefault(false)
        if (channelExists) {
            return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        }
    }
    return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
}

/**
 * ADR-0052 — the section must say honestly when Android blocks every
 * notification, instead of showing a live-looking switch that does nothing.
 * Re-read on ON_RESUME because the listener comes back from the system screen
 * to a still-composed Settings screen.
 */
@Composable
internal fun rememberSystemNotificationsEnabled(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember(context) {
        mutableStateOf(readSystemNotificationsEnabled(context))
    }
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = readSystemNotificationsEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return enabled
}

private fun readSystemNotificationsEnabled(context: Context): Boolean =
    runCatching {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }.getOrDefault(true)
