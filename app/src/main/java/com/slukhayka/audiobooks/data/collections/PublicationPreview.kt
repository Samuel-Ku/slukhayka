package com.slukhayka.audiobooks.data.collections

/**
 * Spec-51 (#691) — what a publication will actually take off the device.
 *
 * The confirmation screen shows exactly [lines]: the listener sees the public
 * name, the title, how many books travel, and whether the description goes
 * with them. Nothing else is implied, and nothing goes out before this is
 * confirmed — there is no silent path.
 */
data class PublicationPreview(
    val title: String,
    val pseudonym: String,
    val bookCount: Int,
    val descriptionIncluded: Boolean
) {
    /** The human-readable list for the confirmation surface. */
    val lines: List<String>
        get() = buildList {
            add("Назва: $title")
            add("Псевдонім: $pseudonym")
            add("Книг у добірці: $bookCount")
            add(if (descriptionIncluded) "Опис: буде опубліковано" else "Опис: не додано")
        }
}

object PublicationPreviewFactory {

    /**
     * @return the preview, or null when this collection can never be published
     * (no usable title, or no books) — an honest refusal rather than an empty
     * confirmation the listener could still accept.
     */
    fun of(collection: ListenerCollection, pseudonym: String): PublicationPreview? {
        val cleanTitle = ListenerCollectionLimits.cleanTitle(collection.title)
        if (!ListenerCollectionLimits.isWritableTitle(cleanTitle)) return null
        if (collection.items.isEmpty()) return null
        val cleanPseudonym = pseudonym.trim().take(PublishedCollectionCodec.MAX_PSEUDONYM_LEN)
        if (cleanPseudonym.isEmpty()) return null
        return PublicationPreview(
            title = cleanTitle,
            pseudonym = cleanPseudonym,
            bookCount = collection.items.size.coerceAtMost(PublishedCollectionCodec.MAX_BOOKS),
            descriptionIncluded = collection.description.isNotBlank()
        )
    }
}
