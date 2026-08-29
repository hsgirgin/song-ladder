package com.songladder.android.ui.rankings

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.songladder.android.domain.model.Album
import com.songladder.android.domain.model.AlbumDetail
import com.songladder.android.domain.model.AlbumMatchStatus
import com.songladder.android.domain.model.AlbumReleaseTrack
import com.songladder.android.domain.model.AlbumReleaseCandidate
import com.songladder.android.domain.model.AlbumTrackRow
import com.songladder.android.domain.model.RankedAlbum
import com.songladder.android.domain.model.RankingPresentation
import com.songladder.android.domain.model.RankingSettings
import com.songladder.android.domain.model.Song
import com.songladder.android.domain.model.Suggestion
import com.songladder.android.domain.model.formatScoreTenths
import com.songladder.android.ui.components.SongRatingControl
import com.songladder.android.ui.theme.SongLadderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RankingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listRow_displaysScoreAndExpandedStatisticsAtCompactWidth() {
        composeRule.setContent {
            SongLadderTheme {
                RankingsListRow(
                    rankedSong = RankedSong(
                        rank = 1,
                        song = rankingsSong(
                            scoreTenths = 80,
                            wins = 12,
                            losses = 3,
                            skips = 5
                        )
                    ),
                    previewState = RankingsPreviewState.Unavailable,
                    expanded = true,
                    isSaving = false,
                    onToggleStats = {},
                    onTogglePreview = {},
                    onShowDetails = {},
                    onSaveScore = {},
                    modifier = Modifier.width(280.dp)
                )
            }
        }

        // "8.0" appears twice while expanded: once in the row's ScoreBadge and once
        // in the inline SongRatingControl's pre-filled value readout.
        composeRule.onAllNodesWithText("8.0").assertCountEquals(2)
        composeRule.onNodeWithText("12W 3L · 5 skips").assertIsDisplayed()
        composeRule.onNodeWithText("Preview unavailable").assertIsDisplayed()
    }

    @Test
    fun gridMode_opensScoreEditorFromProminentScoreTarget() {
        val song = rankingsSong(id = "song-1", scoreTenths = 80)
        var savedScore: Pair<String, Int>? = null
        var previewTaps = 0

        composeRule.setContent {
            SongLadderTheme {
                RankingsScreenContent(
                    uiState = RankingsUiState(
                        allSongs = listOf(song),
                        rankedSongs = listOf(RankedSong(1, song)),
                        selectedTab = RankingsTab.SONGS,
                        presentation = RankingPresentation.GRID
                    ),
                    onTabSelected = {},
                    onSearchActiveChanged = {},
                    onSearchQueryChanged = {},
                    onPresentationChanged = {},
                    onToggleUnrated = {},
                    onToggleIncompleteAlbums = {},
                    onToggleStats = {},
                    onTogglePreview = { previewTaps += 1 },
                    onShowDetails = {},
                    onHideDetails = {},
                    onSaveScore = { songId, scoreTenths -> savedScore = songId to scoreTenths },
                    onDismissTip = {},
                    onDeleteSong = {},
                    onUndoDelete = {},
                    onAcceptSuggestion = { _, _ -> },
                    onDismissSuggestion = {},
                    onToggleSuggestionSelection = {},
                    onClearSuggestionSelection = {},
                    onAcceptSelectedSuggestions = {},
                    onShowAlbumDetails = {},
                    onHideAlbumDetails = {},
                    onToggleAlbumTrackExcluded = { _, _, _ -> },
                    onAddAlbumMissingTracks = { _, _ -> },
                    onChooseAlbumRelease = { _, _ -> },
                    onRefreshAlbumMetadata = {},
                    onRefreshAllAlbums = {},
                    onOpenSettings = {},
                    onAddSongs = {}
                )
            }
        }

        composeRule.onAllNodesWithText("Save 8.0").assertCountEquals(0)
        composeRule.onNodeWithText("8.0").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Edit score").assertIsDisplayed()
        composeRule.onNodeWithText("Save 8.0").performClick()

        composeRule.runOnIdle {
            assertEquals("song-1" to 80, savedScore)
            assertEquals(0, previewTaps)
        }
    }

    @Test
    fun gridMode_unratedSongShowsNeutralScoreBadge() {
        val song = rankingsSong(id = "song-1", scoreTenths = null)

        composeRule.setContent {
            SongLadderTheme {
                RankingsScreenContent(
                    uiState = RankingsUiState(
                        allSongs = listOf(song),
                        rankedSongs = listOf(RankedSong(0, song)),
                        selectedTab = RankingsTab.SONGS,
                        presentation = RankingPresentation.GRID
                    ),
                    onTabSelected = {},
                    onSearchActiveChanged = {},
                    onSearchQueryChanged = {},
                    onPresentationChanged = {},
                    onToggleUnrated = {},
                    onToggleIncompleteAlbums = {},
                    onToggleStats = {},
                    onTogglePreview = {},
                    onShowDetails = {},
                    onHideDetails = {},
                    onSaveScore = { _, _ -> },
                    onDismissTip = {},
                    onDeleteSong = {},
                    onUndoDelete = {},
                    onAcceptSuggestion = { _, _ -> },
                    onDismissSuggestion = {},
                    onToggleSuggestionSelection = {},
                    onClearSuggestionSelection = {},
                    onAcceptSelectedSuggestions = {},
                    onShowAlbumDetails = {},
                    onHideAlbumDetails = {},
                    onToggleAlbumTrackExcluded = { _, _, _ -> },
                    onAddAlbumMissingTracks = { _, _ -> },
                    onChooseAlbumRelease = { _, _ -> },
                    onRefreshAlbumMetadata = {},
                    onRefreshAllAlbums = {},
                    onOpenSettings = {},
                    onAddSongs = {}
                )
            }
        }

        composeRule.onNodeWithText("?").assertIsDisplayed()
    }

    @Test
    fun gridMode_opensScoreEditorFromNeutralScoreBadgeForAnUnratedSong() {
        val song = rankingsSong(id = "song-1", scoreTenths = null)
        var savedScore: Pair<String, Int>? = null

        composeRule.setContent {
            SongLadderTheme {
                RankingsScreenContent(
                    uiState = RankingsUiState(
                        allSongs = listOf(song),
                        rankedSongs = listOf(RankedSong(0, song)),
                        selectedTab = RankingsTab.SONGS,
                        presentation = RankingPresentation.GRID
                    ),
                    onTabSelected = {},
                    onSearchActiveChanged = {},
                    onSearchQueryChanged = {},
                    onPresentationChanged = {},
                    onToggleUnrated = {},
                    onToggleIncompleteAlbums = {},
                    onToggleStats = {},
                    onTogglePreview = {},
                    onShowDetails = {},
                    onHideDetails = {},
                    onSaveScore = { songId, scoreTenths -> savedScore = songId to scoreTenths },
                    onDismissTip = {},
                    onDeleteSong = {},
                    onUndoDelete = {},
                    onAcceptSuggestion = { _, _ -> },
                    onDismissSuggestion = {},
                    onToggleSuggestionSelection = {},
                    onClearSuggestionSelection = {},
                    onAcceptSelectedSuggestions = {},
                    onShowAlbumDetails = {},
                    onHideAlbumDetails = {},
                    onToggleAlbumTrackExcluded = { _, _, _ -> },
                    onAddAlbumMissingTracks = { _, _ -> },
                    onChooseAlbumRelease = { _, _ -> },
                    onRefreshAlbumMetadata = {},
                    onRefreshAllAlbums = {},
                    onOpenSettings = {},
                    onAddSongs = {}
                )
            }
        }

        // Tapping the neutral "?" badge directly on an unrated song's card must open
        // the score editor - the same one-tap path a rated song's badge already opens
        // to edit its score - rather than requiring some other route to rank it.
        composeRule.onNodeWithText("?").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Edit score").assertIsDisplayed()
        composeRule.onNodeWithText("Save 5.5").performClick()

        composeRule.runOnIdle {
            assertEquals("song-1" to 55, savedScore)
        }
    }

    @Test
    fun gridMode_suggestionRowAcceptsWithItsSuggestedScore() {
        val song = rankingsSong(id = "song-1", scoreTenths = null)
        var accepted: Pair<String, Int>? = null

        composeRule.setContent {
            SongLadderTheme {
                RankingsScreenContent(
                    uiState = RankingsUiState(
                        allSongs = listOf(song),
                        unratedSongs = listOf(song),
                        suggestionRows = listOf(
                            SuggestionRow(
                                suggestion = Suggestion(
                                    subjectId = "song-1",
                                    suggestedScoreTenths = 70,
                                    comparisonCount = 5,
                                    scoreGapTenths = null,
                                    lastEventSequenceId = 5L
                                ),
                                song = song
                            )
                        ),
                        selectedTab = RankingsTab.SONGS,
                        presentation = RankingPresentation.GRID
                    ),
                    onTabSelected = {},
                    onSearchActiveChanged = {},
                    onSearchQueryChanged = {},
                    onPresentationChanged = {},
                    onToggleUnrated = {},
                    onToggleIncompleteAlbums = {},
                    onToggleStats = {},
                    onTogglePreview = {},
                    onShowDetails = {},
                    onHideDetails = {},
                    onSaveScore = { _, _ -> },
                    onDismissTip = {},
                    onDeleteSong = {},
                    onUndoDelete = {},
                    onAcceptSuggestion = { subjectId, scoreTenths -> accepted = subjectId to scoreTenths },
                    onDismissSuggestion = {},
                    onToggleSuggestionSelection = {},
                    onClearSuggestionSelection = {},
                    onAcceptSelectedSuggestions = {},
                    onShowAlbumDetails = {},
                    onHideAlbumDetails = {},
                    onToggleAlbumTrackExcluded = { _, _, _ -> },
                    onAddAlbumMissingTracks = { _, _ -> },
                    onChooseAlbumRelease = { _, _ -> },
                    onRefreshAlbumMetadata = {},
                    onRefreshAllAlbums = {},
                    onOpenSettings = {},
                    onAddSongs = {}
                )
            }
        }

        composeRule.onNodeWithText("Suggested scores").assertIsDisplayed()
        composeRule.onNodeWithText("Accept").performClick()

        composeRule.runOnIdle {
            assertEquals("song-1" to 70, accepted)
        }
    }

    @Test
    fun gridMode_suggestionRowShowsTheOldScoreAlongsideTheNewOneWhenRated() {
        val song = rankingsSong(id = "song-1", scoreTenths = 60)
        val otherSong = rankingsSong(id = "song-2", scoreTenths = null)

        composeRule.setContent {
            SongLadderTheme {
                RankingsScreenContent(
                    uiState = RankingsUiState(
                        allSongs = listOf(song, otherSong),
                        unratedSongs = listOf(otherSong),
                        suggestionRows = listOf(
                            SuggestionRow(
                                suggestion = Suggestion(
                                    subjectId = "song-1",
                                    suggestedScoreTenths = 85,
                                    comparisonCount = 5,
                                    scoreGapTenths = 25,
                                    lastEventSequenceId = 5L
                                ),
                                song = song
                            )
                        ),
                        selectedTab = RankingsTab.SONGS,
                        presentation = RankingPresentation.GRID
                    ),
                    onTabSelected = {},
                    onSearchActiveChanged = {},
                    onSearchQueryChanged = {},
                    onPresentationChanged = {},
                    onToggleUnrated = {},
                    onToggleIncompleteAlbums = {},
                    onToggleStats = {},
                    onTogglePreview = {},
                    onShowDetails = {},
                    onHideDetails = {},
                    onSaveScore = { _, _ -> },
                    onDismissTip = {},
                    onDeleteSong = {},
                    onUndoDelete = {},
                    onAcceptSuggestion = { _, _ -> },
                    onDismissSuggestion = {},
                    onToggleSuggestionSelection = {},
                    onClearSuggestionSelection = {},
                    onAcceptSelectedSuggestions = {},
                    onShowAlbumDetails = {},
                    onHideAlbumDetails = {},
                    onToggleAlbumTrackExcluded = { _, _, _ -> },
                    onAddAlbumMissingTracks = { _, _ -> },
                    onChooseAlbumRelease = { _, _ -> },
                    onRefreshAlbumMetadata = {},
                    onRefreshAllAlbums = {},
                    onOpenSettings = {},
                    onAddSongs = {}
                )
            }
        }

        composeRule.onNodeWithText("6.0").assertIsDisplayed()
        composeRule.onNodeWithText("8.5").assertIsDisplayed()
    }

    @Test
    fun screenContent_exposesTabsSearchGridModeAndSettingsAction() {
        var openedSettings = false
        var dismissedTip = false

        composeRule.setContent {
            SongLadderTheme {
                RankingsScreenContent(
                    uiState = RankingsUiState(
                        rankedSongs = listOf(RankedSong(1, rankingsSong(title = "A song"))),
                        selectedTab = RankingsTab.SONGS,
                        presentation = RankingPresentation.GRID
                    ),
                    onTabSelected = {},
                    onSearchActiveChanged = {},
                    onSearchQueryChanged = {},
                    onPresentationChanged = {},
                    onToggleUnrated = {},
                    onToggleIncompleteAlbums = {},
                    onToggleStats = {},
                    onTogglePreview = {},
                    onShowDetails = {},
                    onHideDetails = {},
                    onSaveScore = { _, _ -> },
                    onDismissTip = { dismissedTip = true },
                    onDeleteSong = {},
                    onUndoDelete = {},
                    onAcceptSuggestion = { _, _ -> },
                    onDismissSuggestion = {},
                    onToggleSuggestionSelection = {},
                    onClearSuggestionSelection = {},
                    onAcceptSelectedSuggestions = {},
                    onShowAlbumDetails = {},
                    onHideAlbumDetails = {},
                    onToggleAlbumTrackExcluded = { _, _, _ -> },
                    onAddAlbumMissingTracks = { _, _ -> },
                    onChooseAlbumRelease = { _, _ -> },
                    onRefreshAlbumMetadata = {},
                    onRefreshAllAlbums = {},
                    onOpenSettings = { openedSettings = true },
                    onAddSongs = {}
                )
            }
        }

        composeRule.onNodeWithText("Songs").assertIsDisplayed()
        composeRule.onNodeWithText("Albums").assertIsDisplayed()
        composeRule.onNodeWithText("Artists").assertIsDisplayed()
        composeRule.onNodeWithText("Tap to preview · Hold for details").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dismiss tip").performClick()
        composeRule.onNodeWithContentDescription("Settings").performClick()

        composeRule.runOnIdle {
            assertEquals(true, openedSettings)
            assertEquals(true, dismissedTip)
        }
    }

    @Test
    fun screenContent_hidesDismissedTipInSongsGrid() {
        composeRule.setContent {
            SongLadderTheme {
                RankingsScreenContent(
                    uiState = RankingsUiState(
                        rankedSongs = listOf(RankedSong(1, rankingsSong(title = "A song"))),
                        selectedTab = RankingsTab.SONGS,
                        settings = RankingSettings(showTips = false),
                        presentation = RankingPresentation.GRID
                    ),
                    onTabSelected = {},
                    onSearchActiveChanged = {},
                    onSearchQueryChanged = {},
                    onPresentationChanged = {},
                    onToggleUnrated = {},
                    onToggleIncompleteAlbums = {},
                    onToggleStats = {},
                    onTogglePreview = {},
                    onShowDetails = {},
                    onHideDetails = {},
                    onSaveScore = { _, _ -> },
                    onDismissTip = {},
                    onDeleteSong = {},
                    onUndoDelete = {},
                    onAcceptSuggestion = { _, _ -> },
                    onDismissSuggestion = {},
                    onToggleSuggestionSelection = {},
                    onClearSuggestionSelection = {},
                    onAcceptSelectedSuggestions = {},
                    onShowAlbumDetails = {},
                    onHideAlbumDetails = {},
                    onToggleAlbumTrackExcluded = { _, _, _ -> },
                    onAddAlbumMissingTracks = { _, _ -> },
                    onChooseAlbumRelease = { _, _ -> },
                    onRefreshAlbumMetadata = {},
                    onRefreshAllAlbums = {},
                    onOpenSettings = {},
                    onAddSongs = {}
                )
            }
        }

        composeRule.onNodeWithText("Tap to preview · Hold for details").assertDoesNotExist()
    }

    @Test
    fun screenContent_hidesSongOnlyActionsOutsideSongsTab() {
        composeRule.setContent {
            SongLadderTheme {
                RankingsScreenContent(
                    uiState = RankingsUiState(
                        rankedSongs = listOf(RankedSong(1, rankingsSong(title = "A song"))),
                        selectedTab = RankingsTab.ARTISTS,
                        searchActive = true,
                        searchQuery = "needle",
                        settings = RankingSettings(showTips = false),
                        presentation = RankingPresentation.GRID
                    ),
                    onTabSelected = {},
                    onSearchActiveChanged = {},
                    onSearchQueryChanged = {},
                    onPresentationChanged = {},
                    onToggleUnrated = {},
                    onToggleIncompleteAlbums = {},
                    onToggleStats = {},
                    onTogglePreview = {},
                    onShowDetails = {},
                    onHideDetails = {},
                    onSaveScore = { _, _ -> },
                    onDismissTip = {},
                    onDeleteSong = {},
                    onUndoDelete = {},
                    onAcceptSuggestion = { _, _ -> },
                    onDismissSuggestion = {},
                    onToggleSuggestionSelection = {},
                    onClearSuggestionSelection = {},
                    onAcceptSelectedSuggestions = {},
                    onShowAlbumDetails = {},
                    onHideAlbumDetails = {},
                    onToggleAlbumTrackExcluded = { _, _, _ -> },
                    onAddAlbumMissingTracks = { _, _ -> },
                    onChooseAlbumRelease = { _, _ -> },
                    onRefreshAlbumMetadata = {},
                    onRefreshAllAlbums = {},
                    onOpenSettings = {},
                    onAddSongs = {}
                )
            }
        }

        composeRule.onNodeWithText("Coming soon").assertIsDisplayed()
        composeRule.onNodeWithText("Search songs").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Search rankings").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Show list").assertDoesNotExist()
    }

    @Test
    fun ratingControlSavesCurrentScore() {
        var saved = false

        composeRule.setContent {
            SongLadderTheme {
                SongRatingControl(
                    scoreTenths = 85,
                    onScoreChange = {},
                    onSave = { saved = true },
                    onCancel = {}
                )
            }
        }

        composeRule.onNodeWithText("8.5").assertIsDisplayed()
        composeRule.onNodeWithText("Save 8.5").performClick()

        composeRule.runOnIdle {
            assertEquals(true, saved)
        }
    }

    @Test
    fun albumsTab_showsRankedAlbumsWithoutComingSoonBanner() {
        composeRule.setContent {
            SongLadderTheme {
                RankingsScreenContent(
                    uiState = RankingsUiState(
                        selectedTab = RankingsTab.ALBUMS,
                        presentation = RankingPresentation.GRID,
                        rankedAlbums = listOf(rankedAlbum(id = "album-1", title = "Blonde", scoreTenths = 80))
                    ),
                    onTabSelected = {},
                    onSearchActiveChanged = {},
                    onSearchQueryChanged = {},
                    onPresentationChanged = {},
                    onToggleUnrated = {},
                    onToggleIncompleteAlbums = {},
                    onToggleStats = {},
                    onTogglePreview = {},
                    onShowDetails = {},
                    onHideDetails = {},
                    onSaveScore = { _, _ -> },
                    onDismissTip = {},
                    onDeleteSong = {},
                    onUndoDelete = {},
                    onAcceptSuggestion = { _, _ -> },
                    onDismissSuggestion = {},
                    onToggleSuggestionSelection = {},
                    onClearSuggestionSelection = {},
                    onAcceptSelectedSuggestions = {},
                    onShowAlbumDetails = {},
                    onHideAlbumDetails = {},
                    onToggleAlbumTrackExcluded = { _, _, _ -> },
                    onAddAlbumMissingTracks = { _, _ -> },
                    onChooseAlbumRelease = { _, _ -> },
                    onRefreshAlbumMetadata = {},
                    onRefreshAllAlbums = {},
                    onOpenSettings = {},
                    onAddSongs = {}
                )
            }
        }

        composeRule.onNodeWithText("Blonde").assertIsDisplayed()
        composeRule.onNodeWithText("#1").assertIsDisplayed()
        composeRule.onNodeWithText("Coming soon").assertDoesNotExist()
    }

    @Test
    fun albumsTab_refreshAllButtonInvokesCallbackAndOnlyShowsOnTheAlbumsTab() {
        var refreshed = false
        composeRule.setContent {
            SongLadderTheme {
                RankingsScreenContent(
                    uiState = RankingsUiState(
                        selectedTab = RankingsTab.ALBUMS,
                        presentation = RankingPresentation.GRID,
                        rankedAlbums = listOf(rankedAlbum(id = "album-1", title = "Blonde", scoreTenths = 80))
                    ),
                    onTabSelected = {},
                    onSearchActiveChanged = {},
                    onSearchQueryChanged = {},
                    onPresentationChanged = {},
                    onToggleUnrated = {},
                    onToggleIncompleteAlbums = {},
                    onToggleStats = {},
                    onTogglePreview = {},
                    onShowDetails = {},
                    onHideDetails = {},
                    onSaveScore = { _, _ -> },
                    onDismissTip = {},
                    onDeleteSong = {},
                    onUndoDelete = {},
                    onAcceptSuggestion = { _, _ -> },
                    onDismissSuggestion = {},
                    onToggleSuggestionSelection = {},
                    onClearSuggestionSelection = {},
                    onAcceptSelectedSuggestions = {},
                    onShowAlbumDetails = {},
                    onHideAlbumDetails = {},
                    onToggleAlbumTrackExcluded = { _, _, _ -> },
                    onAddAlbumMissingTracks = { _, _ -> },
                    onChooseAlbumRelease = { _, _ -> },
                    onRefreshAlbumMetadata = {},
                    onRefreshAllAlbums = { refreshed = true },
                    onOpenSettings = {},
                    onAddSongs = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Refresh all album metadata").assertIsDisplayed().performClick()
        assertEquals(true, refreshed)
    }

    @Test
    fun songsTab_hasNoRefreshAllAlbumsButton() {
        composeRule.setContent {
            SongLadderTheme {
                RankingsScreenContent(
                    uiState = RankingsUiState(selectedTab = RankingsTab.SONGS),
                    onTabSelected = {},
                    onSearchActiveChanged = {},
                    onSearchQueryChanged = {},
                    onPresentationChanged = {},
                    onToggleUnrated = {},
                    onToggleIncompleteAlbums = {},
                    onToggleStats = {},
                    onTogglePreview = {},
                    onShowDetails = {},
                    onHideDetails = {},
                    onSaveScore = { _, _ -> },
                    onDismissTip = {},
                    onDeleteSong = {},
                    onUndoDelete = {},
                    onAcceptSuggestion = { _, _ -> },
                    onDismissSuggestion = {},
                    onToggleSuggestionSelection = {},
                    onClearSuggestionSelection = {},
                    onAcceptSelectedSuggestions = {},
                    onShowAlbumDetails = {},
                    onHideAlbumDetails = {},
                    onToggleAlbumTrackExcluded = { _, _, _ -> },
                    onAddAlbumMissingTracks = { _, _ -> },
                    onChooseAlbumRelease = { _, _ -> },
                    onRefreshAlbumMetadata = {},
                    onRefreshAllAlbums = {},
                    onOpenSettings = {},
                    onAddSongs = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Refresh all album metadata").assertDoesNotExist()
    }

    @Test
    fun albumsGrid_incompleteAlbumShowsUnrankedBadgeAndCollapsesBehindAHeaderWhenRankedAlbumsExist() {
        composeRule.setContent {
            SongLadderTheme {
                AlbumsGridCard(
                    rankedAlbum = rankedAlbum(id = "album-1", title = "In Progress", scoreTenths = null)
                )
            }
        }

        composeRule.onNodeWithText("Unranked").assertIsDisplayed()
    }

    @Test
    fun albumsGrid_needsReviewAlbumShowsCheckReleaseLabel() {
        composeRule.setContent {
            SongLadderTheme {
                AlbumsGridCard(
                    rankedAlbum = rankedAlbum(
                        id = "album-1",
                        title = "Ambiguous",
                        scoreTenths = null,
                        matchStatus = AlbumMatchStatus.NEEDS_REVIEW
                    )
                )
            }
        }

        composeRule.onNodeWithText("Check release").assertIsDisplayed()
    }

    @Test
    fun albumsContent_incompleteHeaderCollapsesAndExpandsIncompleteAlbums() {
        composeRule.setContent {
            SongLadderTheme {
                var expanded by remember { mutableStateOf(false) }
                RankingsAlbumsContent(
                    uiState = RankingsUiState(
                        presentation = RankingPresentation.GRID,
                        rankedAlbums = listOf(rankedAlbum(id = "complete", title = "Complete", scoreTenths = 80)),
                        incompleteAlbums = listOf(rankedAlbum(id = "incomplete", title = "In Progress", scoreTenths = null)),
                        incompleteAlbumsExpanded = expanded
                    ),
                    onToggleIncompleteAlbums = { expanded = !expanded },
                    onShowAlbumDetails = {}
                )
            }
        }

        composeRule.onNodeWithText("In Progress").assertDoesNotExist()
        composeRule.onNodeWithText("1 incomplete album").performClick()
        composeRule.onNodeWithText("In Progress").assertIsDisplayed()
    }

    @Test
    fun albumsContent_emptySearchResultsShowsNoMatchingAlbumsInsteadOfNoAlbumsYet() {
        composeRule.setContent {
            SongLadderTheme {
                RankingsAlbumsContent(
                    uiState = RankingsUiState(
                        presentation = RankingPresentation.GRID,
                        searchQuery = "xyz123",
                        rankedAlbums = emptyList(),
                        incompleteAlbums = emptyList()
                    ),
                    onToggleIncompleteAlbums = {},
                    onShowAlbumDetails = {}
                )
            }
        }

        composeRule.onNodeWithText("No matching albums").assertIsDisplayed()
        composeRule.onNodeWithText("No albums yet").assertDoesNotExist()
    }

    @Test
    fun albumDetailDialog_showsArtistRankAndScoreForARankedAlbum() {
        composeRule.setContent {
            SongLadderTheme {
                AlbumDetailDialog(
                    detail = albumDetail(id = "album-1", title = "Blonde", artist = "Frank Ocean", scoreTenths = 80),
                    rank = 1,
                    matchCandidates = null,
                    onDismiss = {},
                    onToggleTrackExcluded = { _, _ -> },
                    onAddMissingTracks = {},
                    onChooseRelease = {},
                    onRefreshMetadata = {},
                    onRateTrack = {}
                )
            }
        }

        composeRule.onNodeWithText("Frank Ocean").assertIsDisplayed()
        composeRule.onNodeWithText("#1").assertIsDisplayed()
        composeRule.onNodeWithText("8.0").assertIsDisplayed()
    }

    @Test
    fun albumDetailDialog_showsUnrankedBadgeWhenTheAlbumHasNoScoreYet() {
        composeRule.setContent {
            SongLadderTheme {
                AlbumDetailDialog(
                    detail = albumDetail(id = "album-1", scoreTenths = null),
                    rank = null,
                    matchCandidates = null,
                    onDismiss = {},
                    onToggleTrackExcluded = { _, _ -> },
                    onAddMissingTracks = {},
                    onChooseRelease = {},
                    onRefreshMetadata = {},
                    onRateTrack = {}
                )
            }
        }

        composeRule.onNodeWithText("Unranked").assertIsDisplayed()
    }

    @Test
    fun albumDetailDialog_exclusionCheckboxTogglesTheTappedTrack() {
        var toggled: Pair<String, Boolean>? = null
        composeRule.setContent {
            SongLadderTheme {
                AlbumDetailDialog(
                    detail = albumDetail(
                        id = "album-1",
                        scoreTenths = null,
                        tracks = listOf(albumTrackRow(songId = "song-1", title = "Nikes", excluded = false))
                    ),
                    rank = null,
                    matchCandidates = null,
                    onDismiss = {},
                    onToggleTrackExcluded = { songId, excluded -> toggled = songId to excluded },
                    onAddMissingTracks = {},
                    onChooseRelease = {},
                    onRefreshMetadata = {},
                    onRateTrack = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Included in average").assertIsDisplayed()
        composeRule.onNodeWithText("Nikes").assertIsDisplayed()
        composeRule.onNode(isToggleable()).performClick()

        composeRule.runOnIdle {
            assertEquals("song-1" to true, toggled)
        }
    }

    @Test
    fun albumDetailDialog_tappingScoreBadgeOnATrackInvokesOnRateTrackWithThatSong() {
        var rated: Song? = null
        val song = rankingsSong(id = "song-1", title = "Nikes", scoreTenths = null)
        composeRule.setContent {
            SongLadderTheme {
                AlbumDetailDialog(
                    detail = albumDetail(
                        id = "album-1",
                        scoreTenths = null,
                        tracks = listOf(AlbumTrackRow(song = song, excludedFromAverage = false))
                    ),
                    rank = null,
                    matchCandidates = null,
                    onDismiss = {},
                    onToggleTrackExcluded = { _, _ -> },
                    onAddMissingTracks = {},
                    onChooseRelease = {},
                    onRefreshMetadata = {},
                    onRateTrack = { rated = it }
                )
            }
        }

        composeRule.onNodeWithText("?").performClick()

        composeRule.runOnIdle {
            assertEquals(song, rated)
        }
    }

    @Test
    fun albumDetailDialog_tappingScoreBadgeOnAnAlreadyScoredTrackInvokesOnRateTrackWithThatSong() {
        var rated: Song? = null
        val song = rankingsSong(id = "song-1", title = "Nikes", scoreTenths = 85)
        composeRule.setContent {
            SongLadderTheme {
                AlbumDetailDialog(
                    detail = albumDetail(
                        id = "album-1",
                        scoreTenths = null,
                        tracks = listOf(AlbumTrackRow(song = song, excludedFromAverage = false))
                    ),
                    rank = null,
                    matchCandidates = null,
                    onDismiss = {},
                    onToggleTrackExcluded = { _, _ -> },
                    onAddMissingTracks = {},
                    onChooseRelease = {},
                    onRefreshMetadata = {},
                    onRateTrack = { rated = it }
                )
            }
        }

        composeRule.onNodeWithText(formatScoreTenths(85)).performClick()

        composeRule.runOnIdle {
            assertEquals(song, rated)
        }
    }

    @Test
    fun albumDetailDialog_missingTrackAddButtonInvokesCallbackWithThatTrackId() {
        var added: List<String>? = null
        composeRule.setContent {
            SongLadderTheme {
                AlbumDetailDialog(
                    detail = albumDetail(id = "album-1", scoreTenths = null).copy(
                        missingTracks = listOf(
                            AlbumReleaseTrack(trackId = "track-1", title = "Ivy")
                        )
                    ),
                    rank = null,
                    matchCandidates = null,
                    onDismiss = {},
                    onToggleTrackExcluded = { _, _ -> },
                    onAddMissingTracks = { ids -> added = ids },
                    onChooseRelease = {},
                    onRefreshMetadata = {},
                    onRateTrack = {}
                )
            }
        }

        composeRule.onNodeWithText("Ivy").assertIsDisplayed()
        composeRule.onNodeWithText("Add").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("track-1"), added)
        }
    }

    @Test
    fun albumDetailDialog_showsCandidatePickerOnlyWhenNeedsReview() {
        val candidate = AlbumReleaseCandidate(collectionId = "collection-1", collectionName = "Blonde", artistName = "Frank Ocean", trackCount = 17)

        composeRule.setContent {
            SongLadderTheme {
                AlbumDetailDialog(
                    detail = albumDetail(id = "album-1", scoreTenths = null, matchStatus = AlbumMatchStatus.NEEDS_REVIEW),
                    rank = null,
                    matchCandidates = AlbumMatchCandidatesState(albumId = "album-1", isLoading = false, candidates = listOf(candidate)),
                    onDismiss = {},
                    onToggleTrackExcluded = { _, _ -> },
                    onAddMissingTracks = {},
                    onChooseRelease = {},
                    onRefreshMetadata = {},
                    onRateTrack = {}
                )
            }
        }

        composeRule.onNodeWithText("Choose the correct release").assertIsDisplayed()
        composeRule.onNodeWithText("Use this release").assertIsDisplayed()
    }

    @Test
    fun albumDetailDialog_candidatePickerFlagsTheReleaseWhoseTrackCountMatchesYourLibrary() {
        val matchingCandidate = AlbumReleaseCandidate(
            collectionId = "match",
            collectionName = "Blonde",
            artistName = "Frank Ocean",
            trackCount = 2
        )
        val otherCandidate = AlbumReleaseCandidate(
            collectionId = "other",
            collectionName = "Blonde (Deluxe)",
            artistName = "Frank Ocean",
            trackCount = 20
        )

        composeRule.setContent {
            SongLadderTheme {
                AlbumDetailDialog(
                    detail = albumDetail(
                        id = "album-1",
                        scoreTenths = null,
                        matchStatus = AlbumMatchStatus.NEEDS_REVIEW,
                        tracks = listOf(
                            albumTrackRow(songId = "song-1", title = "Nikes", excluded = false),
                            albumTrackRow(songId = "song-2", title = "Ivy", excluded = false)
                        )
                    ),
                    rank = null,
                    matchCandidates = AlbumMatchCandidatesState(
                        albumId = "album-1",
                        isLoading = false,
                        candidates = listOf(matchingCandidate, otherCandidate)
                    ),
                    onDismiss = {},
                    onToggleTrackExcluded = { _, _ -> },
                    onAddMissingTracks = {},
                    onChooseRelease = {},
                    onRefreshMetadata = {},
                    onRateTrack = {}
                )
            }
        }

        composeRule.onNodeWithText("You have 2 tracks for this album").assertIsDisplayed()
        composeRule.onNodeWithText("2 tracks · matches your library").assertExists()
        composeRule.onNodeWithText("20 tracks").assertExists()
    }

    @Test
    fun albumDetailDialog_hidesCandidatePickerWhenAlreadyMatched() {
        composeRule.setContent {
            SongLadderTheme {
                AlbumDetailDialog(
                    detail = albumDetail(id = "album-1", scoreTenths = 80, matchStatus = AlbumMatchStatus.AUTO_MATCHED),
                    rank = 1,
                    matchCandidates = null,
                    onDismiss = {},
                    onToggleTrackExcluded = { _, _ -> },
                    onAddMissingTracks = {},
                    onChooseRelease = {},
                    onRefreshMetadata = {},
                    onRateTrack = {}
                )
            }
        }

        composeRule.onNodeWithText("Choose the correct release").assertDoesNotExist()
    }

    @Test
    fun albumDetailDialog_refreshMetadataButtonInvokesCallback() {
        var refreshed = false
        composeRule.setContent {
            SongLadderTheme {
                AlbumDetailDialog(
                    detail = albumDetail(id = "album-1", scoreTenths = null),
                    rank = null,
                    matchCandidates = null,
                    onDismiss = {},
                    onToggleTrackExcluded = { _, _ -> },
                    onAddMissingTracks = {},
                    onChooseRelease = {},
                    onRefreshMetadata = { refreshed = true },
                    onRateTrack = {}
                )
            }
        }

        composeRule.onNodeWithText("Refresh metadata").performClick()

        composeRule.runOnIdle {
            assertEquals(true, refreshed)
        }
    }

    @Test
    fun albumMatchReviewSection_chooseOpensDetailForThatAlbum() {
        var chosenAlbumId: String? = null
        composeRule.setContent {
            SongLadderTheme {
                AlbumMatchReviewSection(
                    albums = listOf(
                        rankedAlbum(id = "album-1", title = "Ambiguous", scoreTenths = null, matchStatus = AlbumMatchStatus.NEEDS_REVIEW)
                    ),
                    onChoose = { chosenAlbumId = it }
                )
            }
        }

        composeRule.onNodeWithText("Albums to review").assertIsDisplayed()
        composeRule.onNodeWithText("Ambiguous").assertIsDisplayed()
        composeRule.onNodeWithText("Choose").performClick()

        composeRule.runOnIdle {
            assertEquals("album-1", chosenAlbumId)
        }
    }

    @Test
    fun albumMatchReviewSection_rendersNothingWhenNoAlbumsNeedReview() {
        composeRule.setContent {
            SongLadderTheme {
                AlbumMatchReviewSection(albums = emptyList(), onChoose = {})
            }
        }

        composeRule.onNodeWithText("Albums to review").assertDoesNotExist()
    }
}

private fun rankedAlbum(
    id: String,
    title: String = "Album $id",
    artist: String = "Artist $id",
    scoreTenths: Int?,
    matchStatus: AlbumMatchStatus = AlbumMatchStatus.AUTO_MATCHED,
    includedRatedTrackCount: Int = 0,
    totalOwnedTrackCount: Int = 0
): RankedAlbum {
    return RankedAlbum(
        rank = if (scoreTenths != null) 1 else null,
        album = Album(
            id = id,
            title = title,
            artist = artist,
            matchStatus = matchStatus
        ),
        scoreTenths = scoreTenths,
        includedRatedTrackCount = includedRatedTrackCount,
        totalOwnedTrackCount = totalOwnedTrackCount
    )
}

private fun albumDetail(
    id: String,
    title: String = "Album $id",
    artist: String = "Artist $id",
    scoreTenths: Int?,
    matchStatus: AlbumMatchStatus = AlbumMatchStatus.AUTO_MATCHED,
    tracks: List<AlbumTrackRow> = emptyList()
): AlbumDetail {
    return AlbumDetail(
        album = Album(id = id, title = title, artist = artist, matchStatus = matchStatus),
        tracks = tracks,
        missingTracks = emptyList(),
        scoreTenths = scoreTenths,
        includedRatedTrackCount = 0
    )
}

private fun albumTrackRow(songId: String, title: String, excluded: Boolean): AlbumTrackRow {
    return AlbumTrackRow(
        song = rankingsSong(id = songId, title = title),
        excludedFromAverage = excluded
    )
}

private fun rankingsSong(
    id: String = "song-1",
    title: String = "A song",
    artist: String = "An artist",
    createdAt: Long = 1L,
    scoreTenths: Int? = 80,
    wins: Int = 0,
    losses: Int = 0,
    skips: Int = 0
): Song {
    return Song(
        id = id,
        title = title,
        artist = artist,
        createdAt = createdAt,
        scoreTenths = scoreTenths,
        wins = wins,
        losses = losses,
        skips = skips
    )
}
