package com.slukhayka.audiobooks.data.collective

/**
 * #532 — the once-per-install flag of the cold-start seed, behind a seam so
 * the decision is testable without Android prefs.
 */
interface ColdStartSeedFlag {
    fun isDone(): Boolean
    fun markDone()
}

class InMemoryColdStartSeedFlag(private var done: Boolean = false) : ColdStartSeedFlag {
    override fun isDone(): Boolean = done
    override fun markDone() {
        done = true
    }
}

/**
 * #532 — imports the bundled seed ONCE per install: a clean start gets a local
 * «Огляд» with no Firestore and no Source request, and every later launch is a
 * no-op. Nothing here can hang the UI: a finished run marks the flag, an empty
 * seed marks it too, and a throwing import leaves it UNSET so the next launch
 * retries honestly instead of showing an endless spinner.
 */
class ColdStartSeed(
    private val importer: CatalogSeedImporter,
    private val seed: List<CollectiveCardPublication>,
    private val flag: ColdStartSeedFlag
) {

    /** @return true when this run actually seeded the local catalogue. */
    suspend fun runOnce(): Boolean {
        android.util.Log.w("ColdStartSeed", "runOnce entry: done=${flag.isDone()} seed=${seed.size}")
        if (flag.isDone()) return false
        // An EMPTY seed is never a legitimate state: the asset is bundled, so
        // emptiness means the read failed. Marking the flag here would disable
        // the cold start FOREVER and silently — leave it unset and retry.
        if (seed.isEmpty()) return false
        val imported = try {
            val applied = importer.importOnce(seed)
            // #532 — the ONE line that separates "the asset did not read" from
            // "the rows did not land".
            android.util.Log.w("ColdStartSeed", "seed parsed=${seed.size} imported=$applied")
            applied
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // The catalogue was not seeded: leave the flag UNSET so the next
            // launch retries instead of pretending the cold start finished.
            return false
        }
        flag.markDone()
        return imported > 0
    }
}

/** #532 — the durable once-per-install flag. */
class PrefsColdStartSeedFlag(
    private val prefs: android.content.SharedPreferences,
    private val key: String = KEY
) : ColdStartSeedFlag {
    override fun isDone(): Boolean = prefs.getBoolean(key, false)

    override fun markDone() {
        prefs.edit().putBoolean(key, true).apply()
    }

    companion object {
        const val KEY: String = "cold_start_seed_v1_done"
    }
}
