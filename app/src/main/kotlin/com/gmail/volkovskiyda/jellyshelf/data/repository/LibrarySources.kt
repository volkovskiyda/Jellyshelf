package com.gmail.volkovskiyda.jellyshelf.data.repository

import com.gmail.volkovskiyda.jellyshelf.data.remote.ApiSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.DemoBackend
import com.gmail.volkovskiyda.jellyshelf.data.remote.IndexSource
import com.gmail.volkovskiyda.jellyshelf.data.remote.YtDlpMetadataSource

/**
 * Everywhere library data comes from, as one dependency.
 *
 * [DefaultLibraryRepository] merges five origins — the Jellyfin server, the bot server's metadata
 * API, the hosted metadata index, the in-app yt-dlp extractor, and the stand-in that serves demo
 * installs — and taking them individually made its constructor a list of interchangeable-looking
 * sources. Grouped, the repository reads as what it is: a database, the places its contents come
 * from, settings, and dispatchers.
 *
 * A plain holder on purpose. It owns no logic and hides nothing: which source answers a given call
 * is a decision the repository makes, and moving that in here would only make it harder to see.
 */
class LibrarySources(
    val jellyfin: JellyfinDataSource,
    val api: ApiSource,
    val index: IndexSource,
    val ytDlp: YtDlpMetadataSource,
    val demo: DemoBackend,
)
