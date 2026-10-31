package com.slukhayka.audiobooks.data.collective

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * seed leaves it UNSET (the asset is bundled, so emptiness is a read failure),
 * and a throwing import leaves it UNSET too, so the next launch retries
 * honestly instead of showing an endless spinner.
 */
class ColdStartSeed(
    private val importer: CatalogSeedImporter,
    private val seedLoader: suspend () -> List<CollectiveCardPublication>,
    private val flag: ColdStartSeedFlag
) {

    /** Kept for tests and callers that already hold the parsed seed. */
    constructor(
        importer: CatalogSeedImporter,
        seed: List<CollectiveCardPublication>,
        flag: ColdStartSeedFlag
    ) : this(importer, { seed }, flag)

    /**
     * @return true when this run actually seeded the local catalogue.
     *
     * #1723 — the flag read, the asset load/parse ([seedLoader]) and the import
     * all run on IO. The caller is the Activity/Home lifecycle scope on the
     * main dispatcher; without this hop the bundled JSON was parsed before the
     * first frame.
     */
    suspend fun runOnce(): Boolean = withContext(Dispatchers.IO) {
        if (flag.isDone()) return@withContext false
        val seed = seedLoader()
        // An EMPTY seed is never a legitimate state: the asset is bundled, so
        // emptiness means the read failed. Marking the flag here would disable
        // the cold start FOREVER and silently — leave it unset and retry.
        if (seed.isEmpty()) return@withContext false
        val imported = try {
            importer.importOnce(seed)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            // The catalogue was not seeded: leave the flag UNSET so the next
            // launch retries instead of pretending the cold start finished.
            return@withContext false
        }
        flag.markDone()
        imported > 0
    }
}

/**
 * #532 — the durable once-per-install flag.
 *
 * #1723 — the SharedPreferences object is created lazily on the first
 * [isDone]/[markDone] call. Production constructs this in `App.onCreate` on
 * the main thread, so creating the prefs there would be exactly the disk read
 * the fix removes; those methods are now only called from [ColdStartSeed]'s IO
 * block.
 */
class PrefsColdStartSeedFlag private constructor(
    private val prefsProvider: () -> SharedPreferences,
    private val key: String,
) : ColdStartSeedFlag {

    constructor(context: Context, key: String = KEY) : this(
        { context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) },
        key,
    )

    constructor(prefs: SharedPreferences, key: String = KEY) : this({ prefs }, key)

    override fun isDone(): Boolean = prefsProvider().getBoolean(key, false)

    override fun markDone() {
        prefsProvider().edit().putBoolean(key, true).apply()
    }

    companion object {
        const val KEY: String = "cold_start_seed_v1_done"
        private const val PREFS_NAME: String = "cold_start_seed"
    }
}
