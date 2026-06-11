package com.songladder.android.data.repository

import com.songladder.android.domain.model.MusicSourceType
import com.songladder.android.domain.model.SongInput

val sampleSongs = listOf(
    SongInput("Dreams", "Fleetwood Mac", "Rumours", sourceType = MusicSourceType.SAMPLE),
    SongInput("Shine On You Crazy Diamond", "Pink Floyd", "Wish You Were Here", sourceType = MusicSourceType.SAMPLE),
    SongInput("Nights", "Frank Ocean", "Blonde", sourceType = MusicSourceType.SAMPLE),
    SongInput("All Too Well", "Taylor Swift", "Red", sourceType = MusicSourceType.SAMPLE),
    SongInput("Superstition", "Stevie Wonder", "Talking Book", sourceType = MusicSourceType.SAMPLE),
    SongInput("Hey Ya!", "Outkast", "Speakerboxxx/The Love Below", sourceType = MusicSourceType.SAMPLE)
)
