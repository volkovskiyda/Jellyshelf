package com.gmail.volkovskiyda.jellyshelf.util

/**
 * Escapes SQL LIKE wildcards so user-typed `%` and `_` match literally instead of matching
 * everything. Queries using the result must declare `ESCAPE '\'`.
 */
fun escapeLikePattern(input: String): String = input
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")
