package com.songladder.android.data.spotify

import com.songladder.android.domain.model.AmbiguousPlaylistTrack
import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.PlaylistImportPreview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull

/**
 * Parses a playlist JSON file the user exported themselves with an external tool, so a
 * playlist can be imported without going through Spotify's Web API (which requires a
 * Premium account to register the app). Tolerates two shapes: a raw dump of Spotify's own
 * "playlist tracks" API response (`{"items":[{"track":{...}}]}` / `{"tracks":{"items":[...]}}`),
 * and a simple flat array of track objects (`[{"title":...,"artist":...}, ...]`).
 */
class SpotifyPlaylistJsonImporter {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): Result<PlaylistImportPreview> = runCatching {
        val root = runCatching { json.parseToJsonElement(raw) }
            .getOrElse { error("This doesn't look like a valid JSON file.") }

        val importable = mutableListOf<MusicTrackCandidate>()
        val ambiguous = mutableListOf<AmbiguousPlaylistTrack>()
        extractTrackObjects(root).forEach { collectTrack(it, importable, ambiguous) }

        if (importable.isEmpty() && ambiguous.isEmpty()) {
            error("Could not find any tracks in this file.")
        }

        PlaylistImportPreview(
            playlistTitle = extractPlaylistTitle(root) ?: "Imported Spotify Playlist",
            importableTracks = importable,
            ambiguousTracks = ambiguous
        )
    }

    private fun extractPlaylistTitle(root: JsonElement): String? {
        val obj = root as? JsonObject ?: return null
        return (obj["playlistTitle"] ?: obj["name"] ?: obj["title"])
            ?.jsonPrimitiveOrNull()
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractTrackObjects(root: JsonElement): List<JsonObject> {
        return when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> {
                (root["items"] as? JsonArray)?.let { return it.mapNotNull { item -> item as? JsonObject } }
                (root["tracks"] as? JsonArray)?.let { return it.mapNotNull { item -> item as? JsonObject } }
                (root["tracks"] as? JsonObject)?.let { return extractTrackObjects(it) }
                (root["items"] as? JsonObject)?.let { return extractTrackObjects(it) }
                listOf(root)
            }
            else -> emptyList()
        }
    }

    private fun collectTrack(
        item: JsonObject,
        importable: MutableList<MusicTrackCandidate>,
        ambiguous: MutableList<AmbiguousPlaylistTrack>
    ) {
        val isLocal = item["is_local"]?.jsonPrimitiveOrNull()?.boolean ?: false
        val track = item["track"] as? JsonObject ?: item
        if (isLocal) return

        val id = firstString(track, "id", "spotify_id", "trackId") ?: extractIdFromUri(track)
        val title = firstString(track, "name", "title", "track_name", "Track Name").orEmpty()
        val artist = extractArtist(track)
        val album = extractAlbum(track)
        val artworkUrl = extractArtwork(track)

        if (title.isBlank() || artist.isBlank()) {
            ambiguous += AmbiguousPlaylistTrack(
                rawTitle = title,
                rawArtist = artist,
                reason = "Missing title or artist metadata."
            )
        } else {
            importable += MusicTrackCandidate(
                externalId = id ?: fallbackId(title, artist, album),
                title = title,
                artist = artist,
                album = album,
                artworkUrl = artworkUrl,
                sourceType = MusicSourceType.SPOTIFY
            )
        }
    }

    private fun extractArtist(track: JsonObject): String {
        (track["artists"] as? JsonArray)?.let { artists ->
            val names = artists.mapNotNull { entry ->
                (entry as? JsonObject)?.get("name")?.jsonPrimitiveOrNull()?.contentOrNull
                    ?: (entry as? JsonPrimitive)?.contentOrNull
            }
            if (names.isNotEmpty()) return names.joinToString(", ")
        }
        firstString(track, "artist", "Artist Name(s)")?.let { return it }
        return ""
    }

    private fun extractAlbum(track: JsonObject): String {
        (track["album"] as? JsonObject)?.get("name")?.jsonPrimitiveOrNull()?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return firstString(track, "album", "Album Name").orEmpty()
    }

    private fun extractArtwork(track: JsonObject): String? {
        ((track["album"] as? JsonObject)?.get("images") as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("url")
            ?.jsonPrimitiveOrNull()
            ?.contentOrNull
            ?.let { return it }
        return firstString(track, "artworkUrl", "artwork", "image")
    }

    private fun extractIdFromUri(track: JsonObject): String? {
        val uri = firstString(track, "uri", "track_uri", "Track URI") ?: return null
        return Regex("""spotify:track:([A-Za-z0-9]+)""").find(uri)?.groupValues?.getOrNull(1)
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj[key]?.jsonPrimitiveOrNull()?.contentOrNull
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun fallbackId(title: String, artist: String, album: String): String {
        val base = listOf(title.lowercase(), artist.lowercase(), album.lowercase()).joinToString("::")
        return "spotify-file-${base.hashCode()}"
    }

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
}
