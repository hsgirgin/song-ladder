# Song Ladder

Song Ladder is a native Android app built with Kotlin, Jetpack Compose, Room, DataStore, and a Spotify-ready import layer. The application code lives under [app](app).

## What is implemented

- Compose app shell with `Rank`, `Library`, and `Leaderboard` destinations
- Elo-based song ranking engine
- Local persistence with Room
- Session storage for a Spotify bearer token with DataStore
- Manual song entry, sample pack import, JSON import/export
- Spotify search/import flow using the Spotify Web API search endpoint and a pasted bearer token

## Opening the project

1. Open the repo root in Android Studio.
2. Sync the project with the checked-in Gradle wrapper.
3. Build and run the `app` module on an emulator or device.

## Spotify import

The current implementation expects a valid Spotify Web API bearer token to be pasted into the Library screen before searching. This keeps the app local-first while preserving a real import path and a clean `MusicSourceClient` abstraction for a future PKCE auth flow.

## Notes

- The app is local-first today, with Spotify import built around a pasted bearer token while the auth flow remains intentionally simple.
