package com.songladder.android.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "session_preferences")

class SessionPreferencesRepository(private val context: Context) {
    private val spotifyTokenKey = stringPreferencesKey("spotify_token")

    val spotifyToken: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[spotifyTokenKey] ?: ""
    }

    suspend fun saveSpotifyToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[spotifyTokenKey] = token.trim()
        }
    }
}
