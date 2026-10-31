package com.slukhayka.audiobooks.startup

import android.content.Context
import com.slukhayka.audiobooks.data.collective.CatalogSeedCodec
import com.slukhayka.audiobooks.data.collective.CollectiveCardPublication
import com.slukhayka.audiobooks.data.collective.PrefsColdStartSeedFlag
import com.slukhayka.audiobooks.data.collections.CollectionAssets
import com.slukhayka.audiobooks.data.collections.CollectionList
import com.slukhayka.audiobooks.data.diagnostics.CrashConsent
import com.slukhayka.audiobooks.data.diagnostics.SharedPreferencesCrashConsentStore
import com.slukhayka.audiobooks.data.privacy.PrivacyPrefs
import com.slukhayka.audiobooks.data.privacy.SharedPreferencesPrivacySettingsStore
import com.slukhayka.audiobooks.data.universe.UniverseAssets
import com.slukhayka.audiobooks.data.universe.UniverseList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #1723 — the cold-start values that used to be read on the main thread while
 * the first frame was being prepared: consent, privacy route, showcase
 * consent, the curated collection/universe JSON, and the bundled seed.
 *
 * They are DATA, not layout: nothing decides what the first frame looks like.
 * [StartupReads.load] therefore runs the whole batch on [io] ONCE, and the
 * composition root publishes each value to its owner. The two values that do
 * decide the first frame — the app locale and the theme — deliberately stay
 * outside this batch (see `MainActivity.attachBaseContext` and
 * `AppearancePreferencesStore`).
 *
 * [source] is a seam, not ceremony: the regression witness
 * (`StartupReadsTest`) records the thread and the order of every read through
 * it, which is what proves the production batch never touches main. The
 * production source lives in [AndroidStartupSource] and does only the same
 * synchronous reads the old startup path did — just on IO now.
 */
class StartupReads(
    private val source: StartupSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Reads the batch and returns what the composition root must publish. */
    suspend fun load(): StartupValues = withContext(io) {
        val privacyPrefs = source.privacyPrefs()
        val crashConsent = source.crashConsent()
        val showcasePublished = source.showcasePublished()
        val collectionLists = source.collectionLists()
        val universeLists = source.universeLists()
        // The seed asset is parsed ONLY on an install that has not imported it
        // yet. The flag is read in the same IO pass, so a later launch pays one
        // boolean instead of a JSON parse (#532's once-per-install promise).
        val seedDone = source.coldStartSeedDone()
        StartupValues(
            privacyPrefs = privacyPrefs,
            crashConsent = crashConsent,
            showcasePublished = showcasePublished,
            collectionLists = collectionLists,
            universeLists = universeLists,
            coldStartSeedDone = seedDone,
            coldStartSeed = if (seedDone) emptyList() else source.coldStartSeed(),
        )
    }
}

/**
 * The values produced by [StartupReads.load]. [coldStartSeed] is empty when the
 * once-per-install flag is done, and also when the bundled asset could not be
 * read — exactly the empty-seed retry state `ColdStartSeed.runOnce` already
 * treats as "not seeded yet".
 */
data class StartupValues(
    val privacyPrefs: PrivacyPrefs,
    val crashConsent: CrashConsent,
    val showcasePublished: Boolean,
    val collectionLists: List<CollectionList>,
    val universeLists: List<UniverseList>,
    val coldStartSeedDone: Boolean,
    val coldStartSeed: List<CollectiveCardPublication>,
)

/**
 * The synchronous reads behind [StartupReads]. Kept separate so the witness
 * can record thread/order without Android storage, and so the production
 * implementation is one obvious list of what startup still reads.
 */
interface StartupSource {
    fun privacyPrefs(): PrivacyPrefs
    fun crashConsent(): CrashConsent
    fun showcasePublished(): Boolean
    fun collectionLists(): List<CollectionList>
    fun universeLists(): List<UniverseList>
    fun coldStartSeedDone(): Boolean
    fun coldStartSeed(): List<CollectiveCardPublication>
}

/**
 * Production source over the app's own stores. Every call here is a
 * synchronous read by design; [StartupReads] is what puts them on IO.
 */
internal class AndroidStartupSource(context: Context) : StartupSource {

    private val appContext = context.applicationContext

    override fun privacyPrefs(): PrivacyPrefs =
        SharedPreferencesPrivacySettingsStore(appContext).load()

    override fun crashConsent(): CrashConsent =
        SharedPreferencesCrashConsentStore(appContext).load()

    override fun showcasePublished(): Boolean =
        com.slukhayka.audiobooks.data.achievements.ShowcaseConsentStore
            .readPublished(appContext)

    override fun collectionLists(): List<CollectionList> =
        CollectionAssets.load(appContext)

    override fun universeLists(): List<UniverseList> =
        UniverseAssets.load(appContext)

    override fun coldStartSeedDone(): Boolean =
        PrefsColdStartSeedFlag(appContext).isDone()

    override fun coldStartSeed(): List<CollectiveCardPublication> = runCatching {
        appContext.assets.open("catalog_seed.json").bufferedReader().use { reader ->
            CatalogSeedCodec.parse(reader.readText())
        }
    }.getOrDefault(emptyList())
}
