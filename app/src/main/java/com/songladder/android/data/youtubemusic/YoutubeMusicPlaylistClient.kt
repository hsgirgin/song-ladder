package com.songladder.android.data.youtubemusic

import com.songladder.android.domain.model.AmbiguousPlaylistTrack
import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.MusicTrackCandidate
import com.songladder.android.domain.model.PlaylistImportPreview
import com.songladder.android.domain.repository.PlaylistSourceClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume

class YoutubeMusicPlaylistClient(
    private val httpClient: OkHttpClient
) : PlaylistSourceClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val browserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    private val maxBrowsePages = 20

    override suspend fun previewPlaylist(url: String): Result<PlaylistImportPreview> {
        return runCatching {
            withTimeout(15_000) {
                val playlistId = extractPlaylistId(url)
                    ?: error("Paste a public YouTube Music playlist link.")
                val normalizedUrl = "https://music.youtube.com/playlist?list=$playlistId"
                val request = Request.Builder()
                    .url(normalizedUrl)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("User-Agent", browserUserAgent)
                    .build()

                val html = executeRequest(request)
                val playlistTitle = extractPlaylistTitle(html) ?: "YouTube Music Playlist"
                val canUseBrowseApi = extractInnertubeApiKey(html) != null && extractInnertubeContextJson(html) != null
                if (canUseBrowseApi) {
                    runCatching {
                        previewPlaylistViaBrowseApi(
                            playlistId = playlistId,
                            playlistTitle = playlistTitle,
                            pageHtml = html,
                            normalizedUrl = normalizedUrl
                        )
                    }.getOrElse {
                        parsePlaylistHtml(normalizedUrl, html)
                    }
                } else {
                    parsePlaylistHtml(normalizedUrl, html)
                }
            }
        }.recoverCatching { throwable ->
            when (throwable) {
                is kotlinx.coroutines.TimeoutCancellationException -> {
                    error("YouTube Music preview timed out. Try again in a moment.")
                }
                is IOException -> {
                    error("Could not reach YouTube Music. Check your connection and try again.")
                }
                else -> throw throwable
            }
        }
    }

    internal fun parsePlaylistHtml(url: String, html: String): PlaylistImportPreview {
        val playlistId = extractPlaylistId(url)
            ?: error("Paste a public YouTube Music playlist link.")
        if (html.isUnsupportedBrowserPage()) {
            error("YouTube Music rejected this request as an unsupported browser. Try again in a moment.")
        }
        val playlistTitle = extractPlaylistTitle(html)
            ?: "YouTube Music Playlist"
        val initialDataJson = extractInitialDataJson(html)
            ?: error("Could not read this playlist. It may be private, unavailable, or unsupported.")
        val root = json.parseToJsonElement(initialDataJson)
        val page = parsePlaylistPage(root, playlistId)
        return buildPreview(
            playlistTitle = playlistTitle,
            parsedRows = page.rows,
            unsupportedCount = page.unsupportedCount
        )
    }

    internal fun parseBrowseResponse(
        playlistId: String,
        playlistTitle: String,
        body: String
    ): PlaylistImportPreview {
        val root = json.parseToJsonElement(body)
        val page = parsePlaylistPage(root, playlistId)
        return buildPreview(
            playlistTitle = playlistTitle,
            parsedRows = page.rows,
            unsupportedCount = page.unsupportedCount
        )
    }

    private suspend fun previewPlaylistViaBrowseApi(
        playlistId: String,
        playlistTitle: String,
        pageHtml: String,
        normalizedUrl: String
    ): PlaylistImportPreview {
        val apiKey = extractInnertubeApiKey(pageHtml)
            ?: error("Could not read this playlist. It may be private, unavailable, or unsupported.")
        val contextJson = extractInnertubeContextJson(pageHtml)
            ?: error("Could not read this playlist. It may be private, unavailable, or unsupported.")
        val mergedRows = LinkedHashMap<String, ParsedPlaylistRow>()
        var unsupportedCount = 0
        val seenContinuations = mutableSetOf<String>()
        var requestBody: String? = """
            {
              "context": $contextJson,
              "browseId": "VL$playlistId"
            }
        """.trimIndent()
        var pageCount = 0

        while (requestBody != null && pageCount < maxBrowsePages) {
            val request = buildBrowseRequest(
                apiKey = apiKey,
                normalizedUrl = normalizedUrl,
                requestBody = requestBody
            )
            val responseBody = executeRequest(request)
            val page = parsePlaylistPage(
                root = json.parseToJsonElement(responseBody),
                playlistId = playlistId
            )

            page.rows.forEach { (id, row) -> mergedRows.putIfAbsent(id, row) }
            unsupportedCount += page.unsupportedCount
            pageCount += 1

            val nextContinuation = page.continuations.firstOrNull { seenContinuations.add(it) }
            requestBody = nextContinuation?.let { continuation ->
                """
                    {
                      "context": $contextJson,
                      "continuation": "$continuation"
                    }
                """.trimIndent()
            }
        }

        return buildPreview(
            playlistTitle = playlistTitle,
            parsedRows = mergedRows,
            unsupportedCount = unsupportedCount
        )
    }

    private fun buildBrowseRequest(
        apiKey: String,
        normalizedUrl: String,
        requestBody: String
    ): Request {
        return Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false&key=$apiKey")
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Content-Type", "application/json")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", normalizedUrl)
            .header("User-Agent", browserUserAgent)
            .post(requestBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    private suspend fun executeRequest(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            if (!continuation.isCancelled) {
                                continuation.resumeWith(
                                    Result.failure(
                                        IllegalStateException(
                                            "YouTube Music import failed (${response.code}). Make sure the playlist is public."
                                        )
                                    )
                                )
                            }
                            return
                        }

                        val body = response.body?.string().orEmpty()
                        if (!continuation.isCancelled) {
                            continuation.resume(body)
                        }
                    }
                }
            })
        }

    private fun extractPlaylistId(url: String): String? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return null
        val host = parsed.host.lowercase()
        val isYoutubeHost = host.endsWith("youtube.com") || host.endsWith("youtu.be")
        if (!isYoutubeHost) return null
        return parsed.queryParameter("list")?.takeIf { it.isNotBlank() }
    }

    private fun extractPlaylistTitle(html: String): String? {
        val ogTitle = Regex("""<meta\s+property=["']og:title["']\s+content=["']([^"']+)["']""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.decodeHtml()
            ?.trim()
        if (!ogTitle.isNullOrBlank()) return ogTitle.removeSuffix(" - YouTube Music").trim()

        return Regex("""<title>([^<]+)</title>""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.decodeHtml()
            ?.removeSuffix(" - YouTube Music")
            ?.trim()
    }

    private fun extractInnertubeApiKey(html: String): String? {
        return Regex(""""INNERTUBE_API_KEY":"([^"]+)"""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun extractInnertubeContextJson(html: String): String? {
        val marker = "\"INNERTUBE_CONTEXT\":"
        val start = html.indexOf(marker)
        if (start == -1) return null
        val jsonStart = html.indexOf('{', start)
        if (jsonStart == -1) return null
        return extractBalancedJsonObject(html, jsonStart)
    }

    private fun extractInitialDataJson(html: String): String? {
        extractJsonObjectAfterMarker(html, "ytInitialData = ")?.let { return it }
        extractJsonObjectAfterMarker(html, "ytInitialData=")?.let { return it }
        extractJsonObjectAfterMarker(html, "window['ytInitialData'] = ")?.let { return it }
        extractJsonObjectAfterMarker(html, "window[\"ytInitialData\"] = ")?.let { return it }
        extractJsonObjectAfterMarker(html, "window['ytInitialData']=")?.let { return it }
        extractJsonObjectAfterMarker(html, "window[\"ytInitialData\"]=")?.let { return it }
        extractBrowseDataJsonFromInitialDataArray(html)?.let { return it }
        return null
    }

    private fun extractJsonObjectAfterMarker(html: String, marker: String): String? {
        val start = html.indexOf(marker)
        if (start == -1) return null
        val jsonStart = html.indexOf('{', start)
        if (jsonStart == -1) return null
        return extractBalancedJsonObject(html, jsonStart)
    }

    private fun extractBrowseDataJsonFromInitialDataArray(html: String): String? {
        val pattern = Regex(
            """initialData\.push\(\{path:\s*['"]\\/browse['"][\s\S]*?data:\s*'((?:\\.|[^'\\])*)'""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val encodedData = pattern.find(html)?.groupValues?.getOrNull(1) ?: return null
        return decodeJavaScriptString(encodedData)
    }

    private fun extractBalancedJsonObject(source: String, jsonStart: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false

        for (index in jsonStart until source.length) {
            val char = source[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth += 1
                    '}' -> {
                        depth -= 1
                        if (depth == 0) {
                            return source.substring(jsonStart, index + 1)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun parsePlaylistPage(
        root: JsonElement,
        playlistId: String
    ): ParsedPlaylistPage {
        val parsedRows = LinkedHashMap<String, ParsedPlaylistRow>()
        val unsupportedCounter = UnsupportedCounter()

        collectPlaylistRows(root, playlistId, parsedRows, unsupportedCounter)

        return ParsedPlaylistPage(
            rows = parsedRows,
            unsupportedCount = unsupportedCounter.count,
            continuations = extractContinuationTokens(root)
        )
    }

    private fun buildPreview(
        playlistTitle: String,
        parsedRows: Map<String, ParsedPlaylistRow>,
        unsupportedCount: Int
    ): PlaylistImportPreview {
        if (parsedRows.isEmpty()) {
            error("Could not find any tracks in this playlist. It may be private, unavailable, or unsupported.")
        }

        val importable = mutableListOf<MusicTrackCandidate>()
        val ambiguous = mutableListOf<AmbiguousPlaylistTrack>()

        parsedRows.values.forEach { row ->
            if (row.title.isBlank() || row.artist.isBlank()) {
                ambiguous += AmbiguousPlaylistTrack(
                    rawTitle = row.title,
                    rawArtist = row.artist,
                    reason = "Missing title or artist metadata."
                )
            } else {
                importable += MusicTrackCandidate(
                    externalId = row.externalId,
                    title = row.title,
                    artist = row.artist,
                    album = row.album,
                    artworkUrl = row.artworkUrl,
                    sourceType = MusicSourceType.YOUTUBE_MUSIC
                )
            }
        }

        return PlaylistImportPreview(
            playlistTitle = playlistTitle,
            importableTracks = importable,
            ambiguousTracks = ambiguous,
            unsupportedCount = unsupportedCount
        )
    }

    private fun collectPlaylistRows(
        element: JsonElement,
        playlistId: String,
        sink: MutableMap<String, ParsedPlaylistRow>,
        unsupportedCounter: UnsupportedCounter
    ) {
        when (element) {
            is JsonObject -> {
                if (looksLikeTrackRow(element)) {
                    val row = parsePlaylistRow(element, playlistId)
                    when {
                        row == null -> unsupportedCounter.count += 1
                        else -> sink.putIfAbsent(row.externalId, row)
                    }
                }
                element.values.forEach { child -> collectPlaylistRows(child, playlistId, sink, unsupportedCounter) }
            }

            is JsonArray -> element.forEach { child -> collectPlaylistRows(child, playlistId, sink, unsupportedCounter) }
            else -> Unit
        }
    }

    private fun looksLikeTrackRow(element: JsonObject): Boolean {
        return element.containsKey("videoId") ||
            element.containsKey("playlistSetVideoId") ||
            element.containsKey("musicResponsiveListItemRenderer") ||
            element.containsKey("playlistPanelVideoRenderer")
    }

    private fun parsePlaylistRow(element: JsonObject, playlistId: String): ParsedPlaylistRow? {
        val videoId = element.stringValue("videoId") ?: element.stringValue("playlistSetVideoId")
        val texts = extractTextValues(element)
            .map { it.cleanTextToken() }
            .filter { it.isNotBlank() && !it.isNoiseToken() }
            .distinct()

        if (videoId == null && texts.isEmpty()) return null

        val title = texts.firstOrNull().orEmpty()
        val metadata = texts.drop(1)
        val artist = metadata.firstOrNull { !it.looksLikeDuration() }.orEmpty()
        val album = metadata.dropWhile { it != artist }.drop(1).firstOrNull { !it.looksLikeDuration() }.orEmpty()
        val artworkUrl = extractLargestThumbnailUrl(element)
        val externalId = videoId ?: fallbackExternalId(playlistId, title, artist, album)

        return ParsedPlaylistRow(
            externalId = externalId,
            title = title,
            artist = artist,
            album = album,
            artworkUrl = artworkUrl
        )
    }

    private fun extractTextValues(element: JsonElement): List<String> {
        val values = mutableListOf<String>()
        collectTextValues(element, values)
        return values
    }

    private fun collectTextValues(element: JsonElement, sink: MutableList<String>) {
        when (element) {
            is JsonObject -> {
                (element["text"] as? JsonPrimitive)?.contentOrNull?.let(sink::add)
                element.values.forEach { child -> collectTextValues(child, sink) }
            }

            is JsonArray -> element.forEach { child -> collectTextValues(child, sink) }
            else -> Unit
        }
    }

    private fun extractLargestThumbnailUrl(element: JsonElement): String? {
        val urls = mutableListOf<String>()
        collectThumbnailUrls(element, urls)
        return urls.lastOrNull()
    }

    private fun collectThumbnailUrls(element: JsonElement, sink: MutableList<String>) {
        when (element) {
            is JsonObject -> {
                element["url"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.startsWith("http") }
                    ?.let(sink::add)
                element.values.forEach { child -> collectThumbnailUrls(child, sink) }
            }

            is JsonArray -> element.forEach { child -> collectThumbnailUrls(child, sink) }
            else -> Unit
        }
    }

    private fun extractContinuationTokens(element: JsonElement): List<String> {
        val tokens = linkedSetOf<String>()
        collectContinuationTokens(element, tokens)
        return tokens.toList()
    }

    private fun collectContinuationTokens(element: JsonElement, sink: MutableSet<String>) {
        when (element) {
            is JsonObject -> {
                element["continuationItemRenderer"]
                    ?.jsonObject
                    ?.get("continuationEndpoint")
                    ?.jsonObject
                    ?.get("continuationCommand")
                    ?.jsonObject
                    ?.get("token")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let(sink::add)
                element["nextContinuationData"]
                    ?.jsonObject
                    ?.get("continuation")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let(sink::add)
                element.values.forEach { child -> collectContinuationTokens(child, sink) }
            }

            is JsonArray -> element.forEach { child -> collectContinuationTokens(child, sink) }
            else -> Unit
        }
    }

    private fun fallbackExternalId(playlistId: String, title: String, artist: String, album: String): String {
        val base = listOf(playlistId, title.lowercase(), artist.lowercase(), album.lowercase()).joinToString("::")
        return "ytm-${base.hashCode()}"
    }
}

private data class ParsedPlaylistRow(
    val externalId: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?
)

private data class ParsedPlaylistPage(
    val rows: LinkedHashMap<String, ParsedPlaylistRow>,
    val unsupportedCount: Int,
    val continuations: List<String>
)

private data class UnsupportedCounter(var count: Int = 0)

private fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun String.cleanTextToken(): String {
    return URLDecoder.decode(this, StandardCharsets.UTF_8.name())
        .decodeHtml()
        .replace("\\u0026", "&")
        .replace("\\u2019", "'")
        .trim()
}

private fun String.decodeHtml(): String {
    return replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}

private fun String.isUnsupportedBrowserPage(): Boolean {
    val normalized = lowercase()
    return normalized.contains("unsupported browser") ||
        normalized.contains("not optimized for your browser") ||
        normalized.contains("nicht für deinen browser optimiert") ||
        normalized.contains("dein browser wird nicht mehr unterstützt")
}

private fun decodeJavaScriptString(value: String): String {
    val output = StringBuilder(value.length)
    var index = 0

    while (index < value.length) {
        val char = value[index]
        if (char != '\\' || index == value.lastIndex) {
            output.append(char)
            index += 1
            continue
        }

        when (val escape = value[index + 1]) {
            '\\' -> {
                output.append('\\')
                index += 2
            }
            '\'' -> {
                output.append('\'')
                index += 2
            }
            '"' -> {
                output.append('"')
                index += 2
            }
            '/' -> {
                output.append('/')
                index += 2
            }
            'b' -> {
                output.append('\b')
                index += 2
            }
            'f' -> {
                output.append('\u000C')
                index += 2
            }
            'n' -> {
                output.append('\n')
                index += 2
            }
            'r' -> {
                output.append('\r')
                index += 2
            }
            't' -> {
                output.append('\t')
                index += 2
            }
            'u' -> {
                val endIndex = (index + 6).coerceAtMost(value.length)
                val hex = value.substring(index + 2, endIndex)
                if (hex.length == 4) {
                    output.append(hex.toInt(16).toChar())
                    index += 6
                } else {
                    output.append(char)
                    index += 1
                }
            }
            'x' -> {
                val endIndex = (index + 4).coerceAtMost(value.length)
                val hex = value.substring(index + 2, endIndex)
                if (hex.length == 2) {
                    output.append(hex.toInt(16).toChar())
                    index += 4
                } else {
                    output.append(char)
                    index += 1
                }
            }
            else -> {
                output.append(escape)
                index += 2
            }
        }
    }

    return output.toString()
}

private fun String.isNoiseToken(): Boolean {
    return this == "•" ||
        this == "·" ||
        this.equals("Explicit", ignoreCase = true) ||
        this.equals("Song", ignoreCase = true) ||
        this.endsWith(" views", ignoreCase = true)
}

private fun String.looksLikeDuration(): Boolean = Regex("""^\d{1,2}:\d{2}$""").matches(this)
