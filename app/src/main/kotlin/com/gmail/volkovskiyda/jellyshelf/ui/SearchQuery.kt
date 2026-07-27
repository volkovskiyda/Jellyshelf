package com.gmail.volkovskiyda.jellyshelf.ui

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce

/**
 * How long typing has to pause before a query is searched. Long enough that a burst of keystrokes
 * triggers one Room query + ranking pass instead of one per character, short enough to still feel
 * like live search.
 */
private val SEARCH_DEBOUNCE = 200.milliseconds

/**
 * The query flow debounced for search: non-blank queries wait out [SEARCH_DEBOUNCE], a blank one
 * passes straight through. The blank exception matters twice — the initial empty query must not
 * delay the first list by a fifth of a second on launch, and clearing the search box should snap
 * back to the full list immediately.
 *
 * Only the search pipeline debounces; the query [kotlinx.coroutines.flow.StateFlow] the text field
 * reads stays raw, so typing itself is never delayed.
 */
@OptIn(FlowPreview::class)
fun Flow<String>.debounceSearchQuery(): Flow<String> =
    debounce { query -> if (query.isBlank()) Duration.ZERO else SEARCH_DEBOUNCE }
