package com.slukhayka.audiobooks.data.metadata

/** One bounded public metadata assertion, never listener or playback state. */
sealed interface FacetAssertion {
    val entityId: String
    val sourceId: String
    val observedAt: Long
    val updatedAt: Long
    val kind: FacetEntityKind

    val documentId: String
        get() = FacetAssertionId.of(kind, entityId, sourceId)

    data class Work(
        val workId: String,
        override val sourceId: String,
        val author: FacetPerson? = null,
        val genres: List<FacetGenre> = emptyList(),
        val seriesMemberships: List<FacetSeriesMembership> = emptyList(),
        override val observedAt: Long,
        override val updatedAt: Long
    ) : FacetAssertion {
        override val entityId: String get() = workId
        override val kind: FacetEntityKind get() = FacetEntityKind.WORK
    }

    data class Edition(
        val editionId: String,
        val workId: String,
        override val sourceId: String,
        val narrator: FacetPerson? = null,
        val language: String? = null,
        val durationRef: String? = null,
        val durationBucket: FacetDurationBucket? = null,
        val chapterCount: Int? = null,
        val completeness: FacetCompleteness? = null,
        val availability: FacetAvailability? = null,
        override val observedAt: Long,
        override val updatedAt: Long
    ) : FacetAssertion {
        override val entityId: String get() = editionId
        override val kind: FacetEntityKind get() = FacetEntityKind.EDITION
    }
}

enum class FacetEntityKind(val wireName: String) {
    WORK("work"),
    EDITION("edition");

    companion object {
        fun fromWireName(value: String): FacetEntityKind? = entries.firstOrNull { it.wireName == value }
    }
}

data class FacetPerson(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList()
)

/** One canonical Genre id paired with the exact label observed at the Source. */
data class FacetGenre(
    val id: String,
    val rawText: String
)

data class FacetSeriesMembership(
    val seriesId: String,
    val position: Int? = null
)

enum class FacetDurationBucket(val wireName: String) {
    UNDER_FIVE_HOURS("under_5h"),
    FIVE_TO_TEN_HOURS("5h_to_10h"),
    TEN_TO_TWENTY_HOURS("10h_to_20h"),
    TWENTY_HOURS_OR_MORE("20h_plus");

    companion object {
        fun fromWireName(value: String): FacetDurationBucket? = entries.firstOrNull { it.wireName == value }
    }
}

enum class FacetCompleteness(val wireName: String) {
    FULL("full"),
    ABRIDGED("abridged");

    companion object {
        fun fromWireName(value: String): FacetCompleteness? = entries.firstOrNull { it.wireName == value }
    }
}

data class FacetAvailability(
    val available: Boolean,
    val observedAt: Long,
    val ttlSeconds: Long
) {
    fun isFreshAt(nowMillis: Long): Boolean =
        nowMillis >= observedAt && nowMillis - observedAt < ttlSeconds * 1_000L
}

object FacetAssertionLimits {
    const val SCHEMA_VERSION = 1L
    // Keeps the reversible Firestore document id below its 1,500-byte limit
    // even when every character occupies four UTF-8 bytes.
    const val MAX_ID_LENGTH = 250
    const val MAX_SOURCE_ID_LENGTH = 50
    const val MAX_PERSON_NAME_LENGTH = 200
    const val MAX_ALIASES = 8
    const val MAX_GENRES = 4
    const val MAX_GENRE_RAW_TEXT_LENGTH = 200
    const val MAX_SERIES_MEMBERSHIPS = 4
    const val MAX_SERIES_POSITION = 10_000
    const val MAX_CHAPTER_COUNT = 500
    const val MAX_LANGUAGE_LENGTH = 20
    const val MAX_AVAILABILITY_TTL_SECONDS = 30L * 24 * 60 * 60
}

/** Stable idempotency key for the same Source assertion about one entity. */
object FacetAssertionId {
    const val DELIMITER = '~'

    fun of(kind: FacetEntityKind, entityId: String, sourceId: String): String =
        "${kind.wireName}$DELIMITER$entityId$DELIMITER$sourceId"
}

data class FacetAssertionKey(
    val kind: FacetEntityKind,
    val entityId: String,
    val sourceId: String
) {
    val documentId: String get() = FacetAssertionId.of(kind, entityId, sourceId)
}

data class FacetCursor(
    val updatedAt: Long,
    val documentId: String
)

data class FacetPage(
    val assertions: List<FacetAssertion>,
    /** High-water mark of the last raw document, including a terminal page. */
    val nextCursor: FacetCursor?
)

object FacetPageLimits {
    const val MAX_PAGE_SIZE = 100

    fun bounded(requested: Int): Int = when {
        requested <= 0 -> 0
        else -> requested.coerceAtMost(MAX_PAGE_SIZE)
    }
}

/** Pure strict codec for the compact `book_facets` Firestore shape. */
object FacetAssertionCodec {
    private val baseKeys = setOf(
        "schemaVersion", "assertionId", "entityKind", "entityId", "sourceId", "observedAt", "updatedAt"
    )
    private val workKeys = baseKeys + setOf("author", "genres", "seriesMemberships")
    private val editionKeys = baseKeys + setOf(
        "workId", "narrator", "language", "durationRef", "durationBucket", "chapterCount", "completeness",
        "availabilityAvailable", "availabilityObservedAt", "availabilityTtlSeconds"
    )

    fun toMap(assertion: FacetAssertion): Map<String, Any>? {
        if (!isValidBase(assertion)) return null
        val base = mutableMapOf<String, Any>(
            "schemaVersion" to FacetAssertionLimits.SCHEMA_VERSION,
            "assertionId" to assertion.documentId,
            "entityKind" to assertion.kind.wireName,
            "entityId" to assertion.entityId,
            "sourceId" to assertion.sourceId,
            "observedAt" to assertion.observedAt,
            "updatedAt" to assertion.updatedAt
        )
        when (assertion) {
            is FacetAssertion.Work -> {
                if (!isValidWork(assertion)) return null
                assertion.author?.let { base["author"] = personToMap(it) }
                if (assertion.genres.isNotEmpty()) base["genres"] = assertion.genres.map(::genreToMap)
                if (assertion.seriesMemberships.isNotEmpty()) {
                    base["seriesMemberships"] = assertion.seriesMemberships.map(::seriesToMap)
                }
            }

            is FacetAssertion.Edition -> {
                if (!isValidEdition(assertion)) return null
                base["workId"] = assertion.workId
                assertion.narrator?.let { base["narrator"] = personToMap(it) }
                assertion.language?.let { base["language"] = it }
                assertion.durationRef?.let { base["durationRef"] = it }
                assertion.durationBucket?.let { base["durationBucket"] = it.wireName }
                assertion.chapterCount?.let { base["chapterCount"] = it.toLong() }
                assertion.completeness?.let { base["completeness"] = it.wireName }
                assertion.availability?.let {
                    base["availabilityAvailable"] = it.available
                    base["availabilityObservedAt"] = it.observedAt
                    base["availabilityTtlSeconds"] = it.ttlSeconds
                }
            }
        }
        return base
    }

    fun fromMap(documentId: String, map: Map<String, Any>): FacetAssertion? {
        if (integralLong(map["schemaVersion"]) != FacetAssertionLimits.SCHEMA_VERSION) return null
        if (map["assertionId"] != documentId) return null
        val kind = (map["entityKind"] as? String)?.let(FacetEntityKind::fromWireName) ?: return null
        val entityId = map["entityId"] as? String ?: return null
        val sourceId = map["sourceId"] as? String ?: return null
        val observedAt = integralLong(map["observedAt"]) ?: return null
        val updatedAt = integralLong(map["updatedAt"]) ?: return null
        if (FacetAssertionId.of(kind, entityId, sourceId) != documentId) return null
        return when (kind) {
            FacetEntityKind.WORK -> decodeWork(map, entityId, sourceId, observedAt, updatedAt)
            FacetEntityKind.EDITION -> decodeEdition(map, entityId, sourceId, observedAt, updatedAt)
        }?.takeIf(::isValidBase)
    }

    private fun decodeWork(
        map: Map<String, Any>, workId: String, sourceId: String, observedAt: Long, updatedAt: Long
    ): FacetAssertion.Work? {
        if (!workKeys.containsAll(map.keys) || !map.keys.containsAll(baseKeys)) return null
        val author = if (map.containsKey("author")) {
            personFromMap(map["author"] as? Map<*, *> ?: return null) ?: return null
        } else null
        val genres = if (map.containsKey("genres")) {
            objectList(map["genres"], FacetAssertionLimits.MAX_GENRES, ::genreFromMap) ?: return null
        } else emptyList()
        val series = if (map.containsKey("seriesMemberships")) {
            objectList(
                map["seriesMemberships"], FacetAssertionLimits.MAX_SERIES_MEMBERSHIPS, ::seriesFromMap
            ) ?: return null
        } else emptyList()
        return FacetAssertion.Work(workId, sourceId, author, genres, series, observedAt, updatedAt)
            .takeIf(::isValidWork)
    }

    private fun decodeEdition(
        map: Map<String, Any>, editionId: String, sourceId: String, observedAt: Long, updatedAt: Long
    ): FacetAssertion.Edition? {
        if (!editionKeys.containsAll(map.keys) || !map.keys.containsAll(baseKeys + "workId")) return null
        val workId = map["workId"] as? String ?: return null
        val narrator = if (map.containsKey("narrator")) {
            personFromMap(map["narrator"] as? Map<*, *> ?: return null) ?: return null
        } else null
        val language = optionalString(map, "language") ?: if (map.containsKey("language")) return null else null
        val durationRef = optionalString(map, "durationRef") ?: if (map.containsKey("durationRef")) return null else null
        val durationBucket = (map["durationBucket"] as? String)?.let(FacetDurationBucket::fromWireName)
            ?: if (map.containsKey("durationBucket")) return null else null
        val chapterCount = if (map.containsKey("chapterCount")) {
            integralLong(map["chapterCount"])?.toIntExact() ?: return null
        } else null
        val completeness = (map["completeness"] as? String)?.let(FacetCompleteness::fromWireName)
            ?: if (map.containsKey("completeness")) return null else null
        val availabilityFields = listOf(
            "availabilityAvailable", "availabilityObservedAt", "availabilityTtlSeconds"
        )
        val availabilityCount = availabilityFields.count(map::containsKey)
        if (availabilityCount != 0 && availabilityCount != availabilityFields.size) return null
        val availability = if (availabilityCount == 0) null else FacetAvailability(
            available = map["availabilityAvailable"] as? Boolean ?: return null,
            observedAt = integralLong(map["availabilityObservedAt"]) ?: return null,
            ttlSeconds = integralLong(map["availabilityTtlSeconds"]) ?: return null
        )
        return FacetAssertion.Edition(
            editionId, workId, sourceId, narrator, language, durationRef, durationBucket,
            chapterCount, completeness, availability, observedAt, updatedAt
        ).takeIf(::isValidEdition)
    }

    private fun isValidBase(assertion: FacetAssertion): Boolean =
        boundedId(assertion.entityId, FacetAssertionLimits.MAX_ID_LENGTH) &&
            boundedId(assertion.sourceId, FacetAssertionLimits.MAX_SOURCE_ID_LENGTH) &&
            assertion.observedAt >= 0 && assertion.updatedAt >= 0

    private fun isValidWork(assertion: FacetAssertion.Work): Boolean =
        (assertion.author != null || assertion.genres.isNotEmpty() || assertion.seriesMemberships.isNotEmpty()) &&
            assertion.author?.let(::isValidPerson) != false &&
            assertion.genres.size <= FacetAssertionLimits.MAX_GENRES &&
            assertion.genres.distinctBy { it.id }.size == assertion.genres.size &&
            assertion.genres.all(::isValidGenre) &&
            assertion.seriesMemberships.size <= FacetAssertionLimits.MAX_SERIES_MEMBERSHIPS &&
            assertion.seriesMemberships.distinctBy { it.seriesId }.size == assertion.seriesMemberships.size &&
            assertion.seriesMemberships.all {
                boundedId(it.seriesId, FacetAssertionLimits.MAX_ID_LENGTH) &&
                    (it.position == null || it.position in 1..FacetAssertionLimits.MAX_SERIES_POSITION)
            }

    private fun isValidEdition(assertion: FacetAssertion.Edition): Boolean =
        (
            assertion.narrator != null || assertion.language != null || assertion.durationRef != null ||
                assertion.chapterCount != null || assertion.completeness != null || assertion.availability != null
            ) &&
            boundedId(assertion.workId, FacetAssertionLimits.MAX_ID_LENGTH) &&
            assertion.narrator?.let(::isValidPerson) != false &&
            (assertion.language == null || (
                assertion.language.isNotBlank() && assertion.language.length <= FacetAssertionLimits.MAX_LANGUAGE_LENGTH &&
                    assertion.language.matches(Regex("^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$"))
                )) &&
            (assertion.durationRef == null || assertion.durationRef == assertion.editionId) &&
            ((assertion.durationRef == null) == (assertion.durationBucket == null)) &&
            (assertion.chapterCount == null || assertion.chapterCount in 1..FacetAssertionLimits.MAX_CHAPTER_COUNT) &&
            assertion.availability?.let(::isValidAvailability) != false

    private fun isValidAvailability(value: FacetAvailability): Boolean =
        value.observedAt >= 0 && value.ttlSeconds in 1..FacetAssertionLimits.MAX_AVAILABILITY_TTL_SECONDS

    private fun isValidPerson(value: FacetPerson): Boolean =
        boundedId(value.id, FacetAssertionLimits.MAX_ID_LENGTH) &&
            value.name.isNotBlank() && value.name.length <= FacetAssertionLimits.MAX_PERSON_NAME_LENGTH &&
            value.aliases.size <= FacetAssertionLimits.MAX_ALIASES &&
            value.aliases.distinct().size == value.aliases.size &&
            value.aliases.all { it.isNotBlank() && it.length <= FacetAssertionLimits.MAX_PERSON_NAME_LENGTH }

    private fun isValidGenre(value: FacetGenre): Boolean =
        boundedId(value.id, FacetAssertionLimits.MAX_ID_LENGTH) &&
            value.rawText.isNotBlank() &&
            value.rawText.length <= FacetAssertionLimits.MAX_GENRE_RAW_TEXT_LENGTH

    private fun boundedId(value: String, maxLength: Int): Boolean =
        value.isNotBlank() && value.length <= maxLength &&
            '/' !in value && FacetAssertionId.DELIMITER !in value

    private fun personToMap(value: FacetPerson): Map<String, Any> = mapOf(
        "id" to value.id, "name" to value.name, "aliases" to value.aliases
    )

    private fun personFromMap(map: Map<*, *>): FacetPerson? {
        if (map.keys != setOf("id", "name", "aliases")) return null
        val person = FacetPerson(
            id = map["id"] as? String ?: return null,
            name = map["name"] as? String ?: return null,
            aliases = stringList(map["aliases"], FacetAssertionLimits.MAX_ALIASES, allowAbsent = false) ?: return null
        )
        return person.takeIf(::isValidPerson)
    }

    private fun genreToMap(value: FacetGenre): Map<String, Any> = mapOf(
        "id" to value.id,
        "rawText" to value.rawText
    )

    private fun genreFromMap(map: Map<*, *>): FacetGenre? {
        if (map.keys != setOf("id", "rawText")) return null
        return FacetGenre(
            id = map["id"] as? String ?: return null,
            rawText = map["rawText"] as? String ?: return null
        ).takeIf(::isValidGenre)
    }

    private fun seriesToMap(value: FacetSeriesMembership): Map<String, Any> = buildMap {
        put("seriesId", value.seriesId)
        value.position?.let { put("position", it.toLong()) }
    }

    private fun seriesFromMap(map: Map<*, *>): FacetSeriesMembership? {
        if (!setOf("seriesId", "position").containsAll(map.keys) || !map.containsKey("seriesId")) return null
        return FacetSeriesMembership(
            seriesId = map["seriesId"] as? String ?: return null,
            position = if (map.containsKey("position")) {
                integralLong(map["position"])?.toIntExact() ?: return null
            } else null
        )
    }

    private fun stringList(value: Any?, maxCount: Int, allowAbsent: Boolean = true): List<String>? {
        if (value == null) return if (allowAbsent) emptyList() else null
        val list = value as? List<*> ?: return null
        if (list.size > maxCount) return null
        return list.map { it as? String ?: return null }
    }

    private fun <T> objectList(value: Any?, maxCount: Int, decode: (Map<*, *>) -> T?): List<T>? {
        if (value == null) return emptyList()
        val list = value as? List<*> ?: return null
        if (list.size > maxCount) return null
        return list.map { decode(it as? Map<*, *> ?: return null) ?: return null }
    }

    private fun optionalString(map: Map<String, Any>, key: String): String? = map[key] as? String

    private fun integralLong(value: Any?): Long? = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun Long.toIntExact(): Int? = takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}
