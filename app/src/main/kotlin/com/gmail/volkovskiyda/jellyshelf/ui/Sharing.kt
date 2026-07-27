package com.gmail.volkovskiyda.jellyshelf.ui

import kotlinx.coroutines.flow.SharingStarted

/**
 * The sharing policy every ViewModel `stateIn` uses: keep the upstream alive for 5s after the
 * last collector leaves, so a configuration change or a quick tab switch resubscribes to the
 * running flow instead of restarting the Room query behind it.
 */
val WhileUiSubscribed: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000)
