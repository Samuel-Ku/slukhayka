package com.slukhayka.audiobooks.data.catalog

/** Distinguishes a successfully parsed empty page from transport/HTML failure. */
sealed interface CatalogFetchResult<out T> {
    data class Success<T>(val value: T) : CatalogFetchResult<T>
    data object Failure : CatalogFetchResult<Nothing>
}

internal fun <T> CatalogFetchResult<List<T>>.valueOrEmpty(): List<T> = when (this) {
    is CatalogFetchResult.Success -> value
    CatalogFetchResult.Failure -> emptyList()
}
