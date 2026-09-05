package com.slukhayka.audiobooks.data.privacy

import com.slukhayka.audiobooks.data.privacy.WebViewSessionPrivacy.SessionRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Result of applying the process-wide WebKit route before any page exists. */
sealed interface WebViewRouteApplyOutcome {
    data object Ready : WebViewRouteApplyOutcome
    data class Failed(val cause: Throwable? = null) : WebViewRouteApplyOutcome
}

/**
 * Keeps WebView creation behind WebKit's completion callback. A missing
 * callback is a bounded, fail-closed result instead of an endless setup
 * spinner or a first request racing out through the previous/default route.
 *
 * Lambdas keep the timing contract JVM-testable without importing WebKit into
 * this module seam.
 */
internal suspend fun awaitWebViewRouteApplied(
    route: SessionRoute,
    timeoutMs: Long,
    clearOverride: (onComplete: () -> Unit) -> Unit,
    setOverride: (proxyRule: String, onComplete: () -> Unit) -> Unit
): WebViewRouteApplyOutcome = try {
    val completed = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val onComplete = {
                if (continuation.isActive) continuation.resume(Unit)
            }
            when (route) {
                SessionRoute.SystemDefault -> clearOverride(onComplete)
                is SessionRoute.Routed -> setOverride(route.proxyRule, onComplete)
            }
        }
        true
    } ?: false
    if (completed) WebViewRouteApplyOutcome.Ready else WebViewRouteApplyOutcome.Failed()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    WebViewRouteApplyOutcome.Failed(failure)
}
