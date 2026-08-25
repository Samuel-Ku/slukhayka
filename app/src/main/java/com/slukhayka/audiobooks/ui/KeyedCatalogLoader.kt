package com.slukhayka.audiobooks.ui

import com.slukhayka.audiobooks.data.catalog.CatalogFetchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** One coherent lifecycle for a pushed catalogue route. */
internal sealed interface CatalogDestinationLoadState<out T> {
    data object Closed : CatalogDestinationLoadState<Nothing>
    data object Loading : CatalogDestinationLoadState<Nothing>
    data class Ready<T>(val items: List<T>) : CatalogDestinationLoadState<T>
    data object Failed : CatalogDestinationLoadState<Nothing>
}

/**
 * Owns the repeated open/load/close contract used by catalogue destinations.
 *
 * Cancelling the previous job handles cooperative sources. The generation
 * check is the second boundary: even a transport that ignores cancellation
 * cannot publish A after the user has opened B or closed the route.
 */
internal class KeyedCatalogLoader<K, T>(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val load: suspend (K) -> CatalogFetchResult<List<T>>
) {
    private val generation = AtomicLong(0)
    private var loadJob: Job? = null
    private val mutableState =
        MutableStateFlow<CatalogDestinationLoadState<T>>(CatalogDestinationLoadState.Closed)

    val state: StateFlow<CatalogDestinationLoadState<T>> = mutableState

    val items: StateFlow<List<T>> = state
        .map { current ->
            when (current) {
                is CatalogDestinationLoadState.Ready -> current.items
                CatalogDestinationLoadState.Closed,
                CatalogDestinationLoadState.Failed,
                CatalogDestinationLoadState.Loading -> emptyList()
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val isLoading: StateFlow<Boolean> = state
        .map { it == CatalogDestinationLoadState.Loading }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val failed: StateFlow<Boolean> = state
        .map { it == CatalogDestinationLoadState.Failed }
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun open(key: K) {
        val requestGeneration = generation.incrementAndGet()
        loadJob?.cancel()
        mutableState.value = CatalogDestinationLoadState.Loading
        loadJob = scope.launch(dispatcher) {
            val result: CatalogFetchResult<List<T>> = try {
                load(key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                CatalogFetchResult.Failure
            }
            if (generation.get() != requestGeneration) return@launch
            mutableState.value = when (result) {
                is CatalogFetchResult.Success -> CatalogDestinationLoadState.Ready(result.value)
                CatalogFetchResult.Failure -> CatalogDestinationLoadState.Failed
            }
        }
    }

    fun close() {
        generation.incrementAndGet()
        loadJob?.cancel()
        loadJob = null
        mutableState.value = CatalogDestinationLoadState.Closed
    }
}
