# Song Ladder

Song Ladder is a native Android app built with Kotlin, Jetpack Compose, Room, and local-first import/export flows. The application code lives under [app](app).

## What is implemented

- Compose app shell with `Matchups`, `Library`, and `Rankings` destinations
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

## Release APK updates

Release APKs must be signed with the same keystore for Android to accept them as
updates. When configured, CI reads the keystore and credentials from the
`SONG_LADDER_KEYSTORE_BASE64`, `SONG_LADDER_KEYSTORE_PASSWORD`,
`SONG_LADDER_KEY_ALIAS`, and `SONG_LADDER_KEY_PASSWORD` GitHub Actions secrets,
and publishes the signed release APK for pushes and manual runs. If those secrets
are not configured, CI skips the signed release artifact and still verifies the
debug build. Increment
`versionCode` in `app/build.gradle.kts` for each release. Existing debug APK
installations may need one uninstall before the first signed release can be
installed.

## Import

Use the Library screen to search iTunes, add songs manually, load the sample pack, preview a public YouTube Music playlist, or import/export a Song Ladder JSON backup.

## Notes

- The app is local-first. JSON import replaces the current library after confirmation, while export writes a backup containing songs and ranking stats.

## Product specifications

- [Phase 1: Score-First Rankings](docs/phase-1-score-first-rankings-spec.md)
- [Product decision record](docs/product-decisions.md)
- [Phase 1 implementation plan](docs/phase-1-implementation-plan.md)

## License

Song Ladder is licensed under the [GNU General Public License v3.0](LICENSE).
