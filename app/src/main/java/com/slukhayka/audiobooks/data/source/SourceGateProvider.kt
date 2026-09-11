package com.slukhayka.audiobooks.data.source

/**
 * ADR-0039 / spec #681 T3 (#684) — the ONE process-scoped gate.
 *
 * `App` installs it once at startup beside `TransportPrivacy.install`; every
 * [HttpFetcher] resolves it lazily at call time, so construction order never
 * matters and only the text door of Source hosts is guarded. Tests that never
 * install it keep the raw transport; tests that want to exercise composition
 * install a fake and reset it afterwards.
 */
object SourceGateProvider {
    @Volatile
    private var gate: SourceRequestGate? = null

    fun install(installed: SourceRequestGate?) {
        gate = installed
    }

    val current: SourceRequestGate? get() = gate
}
