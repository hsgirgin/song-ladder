package com.songladder.android.ui.library

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.songladder.android.ui.theme.SongLadderTheme
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun addTab_keepsActionsReachableAtCompactHeight() {
        composeRule.setContent {
            SongLadderTheme {
                AddTabContent(
                    title = "",
                    artist = "",
                    album = "",
                    onTitleChange = {},
                    onArtistChange = {},
                    onAlbumChange = {},
                    onAddSong = {},
                    onLoadSamplePack = {},
                    modifier = Modifier
                        .width(320.dp)
                        .height(280.dp)
                )
            }
        }

        composeRule.onNodeWithText("Load sample pack")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
