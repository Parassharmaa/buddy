package dev.buddy.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
internal val modelJson = Json {
    // Provider tool calls require the default function type to be encoded explicitly.
    encodeDefaults = true
    explicitNulls = false
    maxNestingDepth = 32
    exceptionsWithDebugInfo = false
}
