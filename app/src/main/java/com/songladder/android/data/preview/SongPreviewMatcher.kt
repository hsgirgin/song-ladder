package com.songladder.android.data.preview

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal object SongPreviewMatcher {
    fun cacheKey(title: String, artist: String, album: String = "", externalId: String = ""): String =
        listOf(title, artist, album, externalId).joinToString("|") { normalize(it) }

    fun score(
        requestedTitle: String,
        requestedArtist: String,
        requestedAlbum: String,
        candidateTitle: String,
        candidateArtist: String,
        candidateAlbum: String
    ): Int? {
        val artistScore = artistScore(requestedArtist, candidateArtist) ?: return null
        val titleScore = titleScore(requestedTitle, candidateTitle) ?: return null
        val albumScore = if (
            requestedAlbum.isNotBlank() &&
            titleTokens(requestedAlbum).isNotEmpty() &&
            fuzzyContainsAll(titleTokens(candidateAlbum), titleTokens(requestedAlbum))
        ) {
            10
        } else {
            0
        }

        return titleScore + artistScore + albumScore
    }

    fun artistMatches(requestedArtist: String, candidateArtist: String): Boolean =
        artistScore(requestedArtist, candidateArtist) != null

    private fun artistScore(requestedArtist: String, candidateArtist: String): Int? {
        val requested = normalize(requestedArtist)
        val candidate = normalize(candidateArtist)
        if (requested.isBlank() || candidate.isBlank()) return null
        if (requested == candidate) return 30

        val requestedTokens = artistTokens(requested)
        val candidateTokens = artistTokens(candidate)
        if (requestedTokens.isEmpty() || candidateTokens.isEmpty()) return null

        return if (candidateTokens.containsAll(requestedTokens)) 20 else null
    }

    private fun titleScore(requestedTitle: String, candidateTitle: String): Int? {
        val requested = normalize(requestedTitle)
        val candidate = normalize(candidateTitle)
        if (requested.isBlank() || candidate.isBlank()) return null
        if (requested == candidate) return 100

        val requestedCanonical = normalize(stripDecorations(requestedTitle))
        val candidateCanonical = normalize(stripDecorations(candidateTitle))
        if (requestedCanonical.isNotBlank() && requestedCanonical == candidateCanonical) return 95

        val requestedTokens = titleTokens(requestedCanonical.ifBlank { requested })
        val candidateTokens = titleTokens(candidateCanonical.ifBlank { candidate })
        if (requestedTokens.isEmpty() || candidateTokens.isEmpty()) return null

        if (fuzzyContainsAll(candidateTokens, requestedTokens)) return 80

        val commonTokens = requestedTokens.count { requestedToken ->
            candidateTokens.any { candidateToken -> areSimilarTokens(requestedToken, candidateToken) }
        }
        val similarity = commonTokens.toDouble() / requestedTokens.size
        return if (similarity >= 0.66) 65 else null
    }

    private fun stripDecorations(value: String): String = value
        .replace(Regex("""\s*[\(\[].*?[\)\]]"""), " ")
        .replace(Regex("""\s+-\s+.*$"""), " ")
        .trim()

    private fun normalize(value: String): String {
        val ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return ascii
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun titleTokens(value: String): Set<String> =
        normalize(value).split(" ").filterTo(mutableSetOf()) { it.length > 1 }

    private fun artistTokens(value: String): Set<String> =
        normalize(value).split(" ").filterTo(mutableSetOf()) { it.length > 1 && it !in artistStopWords }

    private fun fuzzyContainsAll(candidateTokens: Set<String>, requestedTokens: Set<String>): Boolean =
        requestedTokens.all { requested ->
            candidateTokens.any { candidate -> areSimilarTokens(requested, candidate) }
        }

    private fun areSimilarTokens(left: String, right: String): Boolean {
        if (left == right) return true
        if (left.length < 5 || right.length < 5) return false

        val distance = levenshteinDistance(left, right)
        val maxLength = max(left.length, right.length)
        return distance <= 2 || distance.toDouble() / maxLength <= 0.25
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)

        for (i in left.indices) {
            current[0] = i + 1
            for (j in right.indices) {
                val substitutionCost = if (left[i] == right[j]) 0 else 1
                current[j + 1] = min(
                    min(current[j] + 1, previous[j + 1] + 1),
                    previous[j] + substitutionCost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }

        return previous[right.length]
    }

    private val artistStopWords = setOf("and", "the", "feat", "featuring", "ft", "with")
}
