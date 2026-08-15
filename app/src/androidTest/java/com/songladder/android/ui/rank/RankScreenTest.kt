package com.songladder.android.ui.rank

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.songladder.android.domain.model.AppStats
import com.songladder.android.domain.model.Matchup
import com.songladder.android.domain.model.Song
import com.songladder.android.ui.theme.SongLadderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RankScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rankHeader_describesStatsAsOneAccessibleSummary() {
        val songs = listOf(
            Song(id = "song-1", title = "Dreams", artist = "Fleetwood Mac", createdAt = 1L),
            Song(id = "song-2", title = "Landslide", artist = "Fleetwood Mac", createdAt = 2L)
        )

        composeRule.setContent {
            SongLadderTheme {
                MinimalRankHeader(
                    RankUiState(
                        songs = songs,
                        stats = AppStats(matchCount = 1),
                        streakCount = 3
                    )
                )
            }
        }

        composeRule.onNodeWithText("2 songs · 1 match · 3-choice streak")
            .assertIsDisplayed()
    }

    @Test
    fun songChoiceCard_exposesChoiceAsButtonWithoutHidingPreviewAction() {
        var choiceCount = 0
        val song = Song(
            id = "song-1",
            title = "Dreams",
            artist = "Fleetwood Mac",
            createdAt = 1L
        )

        composeRule.setContent {
            SongLadderTheme {
                MinimalSongChoiceCard(
                    song = song,
                    artworkSize = 80.dp,
                    reaction = CardReaction.Idle,
                    previewState = SongPreviewState.Available,
                    onTogglePreview = {},
                    onChoose = { choiceCount++ }
                )
            }
        }

        composeRule.onNode(
            SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.Role,
                Role.Button
            ) and SemanticsMatcher("has labeled choose action") { node ->
                node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.OnClick) { null }
                    ?.label == "Choose Dreams by Fleetwood Mac as the winner"
            }
        ).assertHasClickAction().performClick()
        composeRule.onNodeWithContentDescription("Play preview of Dreams").assertIsDisplayed()

        composeRule.runOnIdle { assertEquals(1, choiceCount) }
    }

    @Test
    fun matchup_keepsBothChoicesReachableAtCompactHeight() {
        val left = Song(
            id = "left",
            title = "Left song",
            artist = "Left artist",
            album = "Left album",
            createdAt = 1L
        )
        val right = Song(
            id = "right",
            title = "Right song",
            artist = "Right artist",
            album = "Right album",
            createdAt = 2L
        )

        composeRule.setContent {
            SongLadderTheme {
                RankMatchupContent(
                    uiState = RankUiState(
                        songs = listOf(left, right),
                        previews = mapOf(
                            left.id to SongPreviewState.Unavailable,
                            right.id to SongPreviewState.Unavailable
                        )
                    ),
                    matchup = Matchup(left, right),
                    modifier = Modifier
                        .width(320.dp)
                        .height(320.dp),
                    onTogglePreview = {},
                    onChoose = { _, _ -> },
                    onSkip = {}
                )
            }
        }

        composeRule.onNodeWithText("Skip")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Right song")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Left artist")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Left album")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Right artist")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Right album")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun saving_disables_choices_and_skip() {
        val left = Song(id = "left", title = "Left song", artist = "Left artist", createdAt = 1L)
        val right = Song(id = "right", title = "Right song", artist = "Right artist", createdAt = 2L)

        composeRule.setContent {
            SongLadderTheme {
                RankMatchupContent(
                    uiState = RankUiState(
                        songs = listOf(left, right),
                        isSaving = true
                    ),
                    matchup = Matchup(left, right),
                    onTogglePreview = {},
                    onChoose = { _, _ -> },
                    onSkip = {}
                )
            }
        }

        composeRule.onNodeWithText("Left song").assertIsNotEnabled()
        composeRule.onNodeWithText("Right song").assertIsNotEnabled()
        composeRule.onNodeWithText("Skip").assertIsNotEnabled()
    }

    @Test
    fun emptyRankState_prioritizesSampleSongs_andOffersLibraryFallback() {
        var sampleActionCount = 0
        var libraryActionCount = 0

        composeRule.setContent {
            SongLadderTheme {
                EmptyRankState(
                    onTrySampleSongs = { sampleActionCount++ },
                    onOpenLibrary = { libraryActionCount++ }
                )
            }
        }

        composeRule.onNodeWithText("You need at least two songs to create your first matchup.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Try sample songs")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("Search or add songs")
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, sampleActionCount)
            assertEquals(1, libraryActionCount)
        }
    }

    @Test
    fun firstMatchupReady_isShownAsClearMilestone() {
        val songs = listOf(
            Song(id = "left", title = "Left song", artist = "Left artist", createdAt = 1L),
            Song(id = "right", title = "Right song", artist = "Right artist", createdAt = 2L)
        )

        composeRule.setContent {
            SongLadderTheme {
                MinimalRankHeader(
                    RankUiState(
                        songs = songs,
                        matchup = Matchup(songs[0], songs[1]),
                        isReady = true,
                        isFirstMatchupReady = true
                    )
                )
            }
        }

        composeRule.onNodeWithText("Your first matchup is ready.").assertIsDisplayed()
    }

    @Test
    fun firstMatchupError_isNotHiddenByMilestone() {
        val songs = listOf(
            Song(id = "left", title = "Left song", artist = "Left artist", createdAt = 1L),
            Song(id = "right", title = "Right song", artist = "Right artist", createdAt = 2L)
        )

        composeRule.setContent {
            SongLadderTheme {
                MinimalRankHeader(
                    RankUiState(
                        songs = songs,
                        matchup = Matchup(songs[0], songs[1]),
                        isReady = true,
                        isFirstMatchupReady = true,
                        message = RankMessage.BattleSaveFailed
                    )
                )
            }
        }

        composeRule.onNodeWithText("Could not save ranking. Try again.").assertIsDisplayed()
    }
}
