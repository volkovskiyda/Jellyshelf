package com.gmail.volkovskiyda.jellyshelf.data

import com.gmail.volkovskiyda.jellyshelf.domain.TimeProvider

/** The real clock — the only place in `src/main` that reads `System.currentTimeMillis()`. */
class DefaultTimeProvider : TimeProvider {
    override fun now(): Long = System.currentTimeMillis()
}
