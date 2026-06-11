# Song Ladder Android

Song Ladder is now scaffolded as a native Android app built with Kotlin, Jetpack Compose, Room, DataStore, and a Spotify-ready import layer. The original web prototype is still in the repo as a reference, but the Android code lives under [app](C:\Users\hsgir\Documents\Codex\2026-06-10\i-want-to-make-an-app\app).

## What is implemented

- Compose app shell with `Rank`, `Library`, and `Leaderboard` destinations
- Elo-based song ranking engine
- Local persistence with Room
- Session storage for a Spotify bearer token with DataStore
- Manual song entry, sample pack import, JSON import/export
- Spotify search/import flow using the Spotify Web API search endpoint and a pasted bearer token

## Opening the project

1. Open the repo root in Android Studio.
2. Let Android Studio generate the Gradle wrapper or sync using a local Gradle installation.
3. Build and run the `app` module on an emulator or device.

## Spotify import

The current implementation expects a valid Spotify Web API bearer token to be pasted into the Library screen before searching. This keeps the app local-first while preserving a real import path and a clean `MusicSourceClient` abstraction for a future PKCE auth flow.

## Notes

- This repo does not currently include a checked-in Gradle wrapper.
- The web files at the repo root remain as the original prototype and are not part of the Android app module.
