package com.slukhayka.audiobooks.data.downloads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Application-scoped bridge from a notification PendingIntent to durable
 * download work. It deliberately has no dependency on a screen or ViewModel:
 * Android may deliver a notification action while no Activity exists.
 */
internal class DownloadNotificationActionCoordinator(
    private val scope: CoroutineScope,
    private val execute: suspend (NotificationAction) -> Unit
) {
    fun dispatch(action: NotificationAction) {
        scope.launch { execute(action) }
    }
}
