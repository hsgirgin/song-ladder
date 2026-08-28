package com.songladder.android.domain.engine

import com.songladder.android.domain.model.AlbumMatchOutcome
import com.songladder.android.domain.model.AlbumMatchStatus
import com.songladder.android.domain.model.AlbumReleaseCandidate
import com.songladder.android.domain.model.AlbumReleaseLookup
import com.songladder.android.domain.model.AlbumReleaseTrack
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Pure, network-free matching logic for Phase 3 albums: given a local album grouping
 * (the user's own title/artist and the titles of the tracks they own) and a metadata
 * provider's search results, decides whether a candidate release is confident enough
 * to auto-match, too close to call (needs the user to pick), or not a real match at
 * all. Kept independent of [com.songladder.android.data.itunes.ItunesAlbumMetadataProvider]
 * (which does the actual network calls) the same way [EloMatchupEngine] and
 * [SuggestionEngine] are kept independent of Room/Retrofit-style transport concerns.
 */
class AlbumMatchingEngine {
    fun classifyMatch(
        localTitle: String,
        localArtist: String,
        ownedTrackTitles: List<String>,
        candidates: List<AlbumReleaseCandidate>
    ): AlbumMatchOutcome {
        val scored = candidates
            .mapNotNull { candidate -> scoreCandidate(localTitle, localArtist, ownedTrackTitles, candidate)?.let { candidate to it } }
            .sortedByDescending { it.second }

        val top = scored.firstOrNull()
            ?: return AlbumMatchOutcome(status = AlbumMatchStatus.NO_MATCH)
        val (topCandidate, topScore) = top

        if (topScore < MIN_CONFIDENCE) {
            return AlbumMatchOutcome(status = AlbumMatchStatus.NO_MATCH, confidence = topScore)
        }

        val runnerUpScore = scored.getOrNull(1)?.second
        val tooClose = runnerUpScore != null && topScore - runnerUpScore < AMBIGUITY_MARGIN
        val status = if (tooClose || topScore < AUTO_MATCH_CONFIDENCE) {
            AlbumMatchStatus.NEEDS_REVIEW
        } else {
            AlbumMatchStatus.AUTO_MATCHED
        }
        return AlbumMatchOutcome(status = status, bestCandidate = topCandidate, confidence = topScore)
    }

    fun missingTracks(ownedTrackTitles: List<String>, release: AlbumReleaseLookup): List<AlbumReleaseTrack> {
        val owned = ownedTrackTitles.map { normalize(it) }.toSet()
        return release.tracks.filter { track -> normalize(track.title) !in owned }
    }

    /**
     * Orders [candidates] by the same scoring [classifyMatch] uses to pick a best
     * match, best first - for the manual release picker, which otherwise shows
     * candidates in whatever order the metadata provider happened to fetch them in
     * (term search results first, then an artist's full discography appended), with
     * no relation to how well any of them actually fit this album. Unlike
     * [classifyMatch], nothing is dropped: a candidate [scoreCandidate] filters out
     * (artist overlap too low to score) still needs to be pickable by hand, so it
     * sorts to the bottom rather than disappearing.
     */
    fun rankCandidates(
        localTitle: String,
        localArtist: String,
        ownedTrackTitles: List<String>,
        candidates: List<AlbumReleaseCandidate>
    ): List<AlbumReleaseCandidate> =
        candidates
            .map { candidate -> candidate to (scoreCandidate(localTitle, localArtist, ownedTrackTitles, candidate) ?: -1.0) }
            .sortedByDescending { it.second }
            .map { it.first }

    private fun scoreCandidate(
        localTitle: String,
        localArtist: String,
        ownedTrackTitles: List<String>,
        candidate: AlbumReleaseCandidate
    ): Double? {
        val artistSimilarity = tokenOverlap(localArtist, candidate.artistName)
        if (artistSimilarity < MIN_ARTIST_OVERLAP) return null

        val titleSimilarity = tokenOverlap(localTitle, candidate.collectionName)
        val trackCountProximity = trackCountProximity(ownedTrackTitles.size, candidate.trackCount)

        return (titleSimilarity * TITLE_WEIGHT) +
            (artistSimilarity * ARTIST_WEIGHT) +
            (trackCountProximity * TRACK_COUNT_WEIGHT)
    }

    private fun trackCountProximity(ownedCount: Int, candidateTrackCount: Int?): Double {
        if (candidateTrackCount == null || candidateTrackCount <= 0 || ownedCount <= 0) return NEUTRAL_TRACK_COUNT_PROXIMITY
        val diff = abs(candidateTrackCount - ownedCount).toDouble()
        val denominator = max(candidateTrackCount, ownedCount).toDouble()
        return (1.0 - diff / denominator).coerceIn(0.0, 1.0)
    }

    private fun tokenOverlap(a: String, b: String): Double {
        if (normalize(a) == normalize(b) && normalize(a).isNotBlank()) return 1.0
        val tokensA = tokens(a)
        val tokensB = tokens(b)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val common = tokensA.count { it in tokensB }
        return common.toDouble() / max(tokensA.size, tokensB.size)
    }

    private fun tokens(value: String): Set<String> =
        normalize(value).split(" ").filterTo(mutableSetOf()) { it.length > 1 }

    private fun normalize(value: String): String {
        val ascii = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return ascii
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private companion object {
        const val MIN_ARTIST_OVERLAP = 0.4
        const val MIN_CONFIDENCE = 0.35
        const val AUTO_MATCH_CONFIDENCE = 0.75
        const val AMBIGUITY_MARGIN = 0.08
        const val NEUTRAL_TRACK_COUNT_PROXIMITY = 0.5
        const val TITLE_WEIGHT = 0.5
        const val ARTIST_WEIGHT = 0.3
        const val TRACK_COUNT_WEIGHT = 0.2
    }
}
