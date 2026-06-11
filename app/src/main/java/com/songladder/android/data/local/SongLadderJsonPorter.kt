package com.songladder.android.data.local

import com.songladder.android.domain.model.ExportPayload
import kotlinx.serialization.json.Json

class SongLadderJsonPorter {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun encode(payload: ExportPayload): String = json.encodeToString(payload)

    fun decode(raw: String): ExportPayload = json.decodeFromString(raw)
}
