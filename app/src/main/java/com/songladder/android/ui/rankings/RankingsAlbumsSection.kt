package com.songladder.android.ui.rankings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.songladder.android.R
import com.songladder.android.domain.model.AlbumMatchStatus
import com.songladder.android.domain.model.RankedAlbum
import com.songladder.android.domain.model.RankingPresentation
import com.songladder.android.ui.components.ScoreBadge
import com.songladder.android.ui.components.SongArtwork

@Composable
internal fun RankingsAlbumsContent(
    uiState: RankingsUiState,
    onToggleIncompleteAlbums: () -> Unit,
    onShowAlbumDetails: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    var previousPresentation by remember { mutableStateOf(uiState.presentation) }

    LaunchedEffect(uiState.presentation) {
        if (previousPresentation != uiState.presentation) {
            val anchorAlbumId = when (previousPresentation) {
                RankingPresentation.GRID -> gridState.firstVisibleAlbumKey()
                RankingPresentation.LIST -> listState.firstVisibleAlbumKey()
            }
            when (uiState.presentation) {
                RankingPresentation.GRID -> anchorAlbumId
                    ?.let { uiState.albumIndexFor(it) }
                    ?.let { gridState.scrollToItem(it) }
                RankingPresentation.LIST -> anchorAlbumId
                    ?.let { uiState.albumIndexFor(it) }
                    ?.let { listState.scrollToItem(it) }
            }
            previousPresentation = uiState.presentation
        }
    }

    if (uiState.rankedAlbums.isEmpty() && uiState.incompleteAlbums.isEmpty()) {
        EmptyAlbumsContent(modifier = modifier)
        return
    }

    when (uiState.presentation) {
        RankingPresentation.GRID -> AlbumsGrid(
            uiState = uiState,
            onToggleIncompleteAlbums = onToggleIncompleteAlbums,
            onShowAlbumDetails = onShowAlbumDetails,
            gridState = gridState,
            modifier = modifier
        )
        RankingPresentation.LIST -> AlbumsList(
            uiState = uiState,
            onToggleIncompleteAlbums = onToggleIncompleteAlbums,
            onShowAlbumDetails = onShowAlbumDetails,
            listState = listState,
            modifier = modifier
        )
    }
}

@Composable
internal fun AlbumsGrid(
    uiState: RankingsUiState,
    onToggleIncompleteAlbums: () -> Unit,
    onShowAlbumDetails: (String) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (uiState.albumsNeedingReview.isNotEmpty()) {
            item(key = "review-section", span = { GridItemSpan(maxLineSpan) }) {
                AlbumMatchReviewSection(albums = uiState.albumsNeedingReview, onChoose = onShowAlbumDetails)
            }
        }
        items(uiState.rankedAlbums, key = { it.album.id }) { rankedAlbum ->
            AlbumsGridCard(rankedAlbum = rankedAlbum, onShowDetails = { onShowAlbumDetails(rankedAlbum.album.id) })
        }
        item(key = "incomplete-albums-header", span = { GridItemSpan(maxLineSpan) }) {
            IncompleteAlbumsHeader(
                count = uiState.incompleteAlbums.size,
                expanded = uiState.incompleteAlbumsExpanded,
                onToggle = onToggleIncompleteAlbums
            )
        }
        if (uiState.incompleteAlbumsExpanded) {
            items(uiState.incompleteAlbums, key = { it.album.id }) { rankedAlbum ->
                AlbumsGridCard(rankedAlbum = rankedAlbum, onShowDetails = { onShowAlbumDetails(rankedAlbum.album.id) })
            }
        }
    }
}

@Composable
internal fun AlbumsGridCard(
    rankedAlbum: RankedAlbum,
    onShowDetails: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val album = rankedAlbum.album
    val isComplete = rankedAlbum.scoreTenths != null
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.rankings_open_album_details), onClick = onShowDetails),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                SongArtwork(
                    artworkUrl = album.artworkUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
                if (isComplete && rankedAlbum.rank != null) {
                    RankBadge(
                        rank = rankedAlbum.rank,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    )
                } else {
                    AlbumUnrankedBadge(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    )
                }
                if (album.matchStatus == AlbumMatchStatus.NEEDS_REVIEW) {
                    CheckReleaseLabel(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isComplete) {
                    ScoreBadge(scoreTenths = rankedAlbum.scoreTenths)
                } else {
                    Text(
                        text = stringResource(
                            R.string.rankings_album_track_progress,
                            rankedAlbum.includedRatedTrackCount,
                            rankedAlbum.totalOwnedTrackCount
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun AlbumsList(
    uiState: RankingsUiState,
    onToggleIncompleteAlbums: () -> Unit,
    onShowAlbumDetails: (String) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (uiState.albumsNeedingReview.isNotEmpty()) {
            item(key = "review-section") {
                AlbumMatchReviewSection(albums = uiState.albumsNeedingReview, onChoose = onShowAlbumDetails)
            }
        }
        items(uiState.rankedAlbums, key = { it.album.id }) { rankedAlbum ->
            AlbumsListRow(rankedAlbum = rankedAlbum, onShowDetails = { onShowAlbumDetails(rankedAlbum.album.id) })
        }
        item(key = "incomplete-albums-header") {
            IncompleteAlbumsHeader(
                count = uiState.incompleteAlbums.size,
                expanded = uiState.incompleteAlbumsExpanded,
                onToggle = onToggleIncompleteAlbums
            )
        }
        if (uiState.incompleteAlbumsExpanded) {
            items(uiState.incompleteAlbums, key = { it.album.id }) { rankedAlbum ->
                AlbumsListRow(rankedAlbum = rankedAlbum, onShowDetails = { onShowAlbumDetails(rankedAlbum.album.id) })
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
internal fun AlbumsListRow(
    rankedAlbum: RankedAlbum,
    onShowDetails: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val album = rankedAlbum.album
    val isComplete = rankedAlbum.scoreTenths != null
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.rankings_open_album_details), onClick = onShowDetails),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SongArtwork(artworkUrl = album.artworkUrl, modifier = Modifier.size(58.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (isComplete && rankedAlbum.rank != null) {
                            stringResource(R.string.rankings_ranked_title, rankedAlbum.rank, album.title)
                        } else {
                            album.title
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = album.artist,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!isComplete) {
                        Text(
                            text = stringResource(
                                R.string.rankings_album_track_progress,
                                rankedAlbum.includedRatedTrackCount,
                                rankedAlbum.totalOwnedTrackCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (isComplete) {
                    ScoreBadge(scoreTenths = rankedAlbum.scoreTenths, size = 40.dp)
                } else {
                    AlbumUnrankedBadge()
                }
            }
            if (album.matchStatus == AlbumMatchStatus.NEEDS_REVIEW) {
                CheckReleaseLabel()
            }
        }
    }
}

@Composable
internal fun AlbumUnrankedBadge(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.rankings_album_unranked_badge),
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
internal fun CheckReleaseLabel(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.rankings_album_check_release),
        modifier = modifier
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
internal fun IncompleteAlbumsHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    FilledTonalButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(pluralStringResource(R.plurals.rankings_incomplete_albums_count, count, count))
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null
            )
        }
    }
}

@Composable
private fun EmptyAlbumsContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rankings_album_empty_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.rankings_album_empty_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun LazyGridState.firstVisibleAlbumKey(): String? =
    layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { item ->
        (item.key as? String)?.takeIf { it != "incomplete-albums-header" && it != "review-section" }
    }

private fun LazyListState.firstVisibleAlbumKey(): String? =
    layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { item ->
        (item.key as? String)?.takeIf { it != "incomplete-albums-header" && it != "review-section" }
    }

private fun RankingsUiState.albumIndexFor(albumId: String): Int? {
    val reviewOffset = if (albumsNeedingReview.isNotEmpty()) 1 else 0
    val rankedIndex = rankedAlbums.indexOfFirst { it.album.id == albumId }
    if (rankedIndex >= 0) return rankedIndex + reviewOffset
    val incompleteIndex = incompleteAlbums.indexOfFirst { it.album.id == albumId }
    if (incompleteIndex >= 0 && incompleteAlbumsExpanded) return rankedAlbums.size + 1 + reviewOffset + incompleteIndex
    return null
}
