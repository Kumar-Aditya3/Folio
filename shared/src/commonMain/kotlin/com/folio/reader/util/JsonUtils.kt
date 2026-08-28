package com.folio.reader.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object JsonUtils {
    val Default = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val Compact = Json {
        prettyPrint = false
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun <T> toJson(value: T, serializer: KSerializer<T>): String =
        Default.encodeToString(serializer, value)

    inline fun <reified T> toJson(value: T): String =
        Default.encodeToString(value)

    fun <T> fromJson(json: String, serializer: KSerializer<T>): T =
        Default.decodeFromString(serializer, json)

    inline fun <reified T> fromJson(json: String): T =
        Default.decodeFromString(json)
}
