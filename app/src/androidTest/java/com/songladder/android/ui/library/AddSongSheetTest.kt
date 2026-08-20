package com.songladder.android.ui.library

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.songladder.android.ui.theme.SongLadderTheme
import org.junit.Rule
import org.junit.Test

class AddSongSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun addSection_keepsActionsReachableAtCompactHeight() {
        composeRule.setContent {
            SongLadderTheme {
                LazyColumn(modifier = Modifier.width(320.dp).height(280.dp)) {
                    items(3) {
                        androidx.compose.material3.Text("Spacer item $it")
                    }
                    item {
                        AddSongSectionContent(
                            title = "",
                            artist = "",
                            album = "",
                            onTitleChange = {},
                            onArtistChange = {},
                            onAlbumChange = {},
                            onAddSong = {}
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Add to ladder")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
