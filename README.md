# Song Ladder

Song Ladder is a native Android app built with Kotlin, Jetpack Compose, Room, and local-first import/export flows. The application code lives under [app](app).

## What is implemented

- Compose app shell with `Rank`, `Library`, and `Leaderboard` destinations
- Elo-based song ranking engine
- Local persistence with Room
- Manual song entry, sample pack import, JSON import/export
- iTunes song search/import
- YouTube Music playlist preview/import for public playlist links
- Song preview playback with iTunes and Deezer preview fallback

## Opening the project

1. Open the repo root in Android Studio.
2. Sync the project with the checked-in Gradle wrapper.
3. Build and run the `app` module on an emulator or device.

## Import

Use the Library screen to search iTunes, add songs manually, load the sample pack, preview a public YouTube Music playlist, or import/export a Song Ladder JSON backup.

## Notes

- The app is local-first. JSON import replaces the current library after confirmation, while export writes a backup containing songs and ranking stats.
