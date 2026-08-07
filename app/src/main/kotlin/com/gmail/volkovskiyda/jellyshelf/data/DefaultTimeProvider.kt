package com.gmail.volkovskiyda.jellyshelf.data

import com.gmail.volkovskiyda.jellyshelf.domain.TimeProvider

/** The real clock, wired in the Koin module. */
class DefaultTimeProvider : TimeProvider {
    override fun now(): Long = System.currentTimeMillis()
}
