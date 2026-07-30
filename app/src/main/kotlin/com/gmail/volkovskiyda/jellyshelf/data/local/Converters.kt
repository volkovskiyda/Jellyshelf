package com.gmail.volkovskiyda.jellyshelf.data.local

import androidx.room.TypeConverter
import com.gmail.volkovskiyda.jellyshelf.domain.model.Chapter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter
    fun fromStringList(value: List<String>): String =
        Json.encodeToString(ListSerializer(String.serializer()), value)

    @TypeConverter
    fun toStringList(value: String): List<String> = if (value.isBlank()) {
        emptyList()
    } else {
        Json.decodeFromString(ListSerializer(String.serializer()), value)
    }

    @TypeConverter
    fun fromChapterList(value: List<Chapter>): String =
        Json.encodeToString(ListSerializer(Chapter.serializer()), value)

    @TypeConverter
    fun toChapterList(value: String): List<Chapter> = if (value.isBlank()) {
        emptyList()
    } else {
        Json.decodeFromString(ListSerializer(Chapter.serializer()), value)
    }
}
