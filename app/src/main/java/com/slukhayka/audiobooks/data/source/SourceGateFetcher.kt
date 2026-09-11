package com.slukhayka.audiobooks.data.source

/**
 * ADR-0040 — the politeness seam an adapter requests THROUGH: one endpoint
 * in, the adapter's declared profile ([SourceAdapter.requestProfile]) read
 * for it, the shared gate deciding (fresh cache -> token -> single-flight ->
 * throat). The adapter is the only classifier — it declares its own profile;
 * features never classify requests and never know the numbers (ADR-0039
 * «Фічі більше не знають про ритм»).
 */
interface GatedSourceFetcher {
    /** One gated HTML request; failures degrade to "" (adapter convention). */
    suspend fun getText(url: String, endpoint: SourceEndpoint): String
}

/**
 * The concrete seam: [gate] decides, [transport] executes, [profileFor]
 * reads the adapter's declaration. A Deferred budget or an Unavailable
 * fetch maps to the honest empty body — never a fabricated value, and the
 * caller-facing shape is exactly the plain transport's.
 */
class SourceGateFetcher(
    private val transport: HttpFetcher,
    private val gate: SourceRequestGate,
    private val profileFor: (SourceEndpoint) -> SourceRequestProfile
) : GatedSourceFetcher {

    override suspend fun getText(url: String, endpoint: SourceEndpoint): String {
        val profile = profileFor(endpoint)
        return when (val outcome = gate.run(url, profile.requestClass, profile.cacheTtlMillis) {
            transport.getText(url)
        }) {
            is GateOutcome.Fresh -> outcome.value
            is GateOutcome.Fetched -> outcome.value
            is GateOutcome.Deferred, GateOutcome.Unavailable -> ""
        }
    }
}