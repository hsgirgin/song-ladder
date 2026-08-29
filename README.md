# Song Ladder

[![Latest release](https://img.shields.io/github/v/release/hsgirgin/song-ladder?include_prereleases)](https://github.com/hsgirgin/song-ladder/releases)

Song Ladder is a native Android app built with Kotlin, Jetpack Compose, Room, and local-first import/export flows. The application code lives under [app](app).

This project is in active pre-release — expect frequent updates and some rough edges.

## Try it

1. Download `app-release.apk` from the [most recent release](https://github.com/hsgirgin/song-ladder/releases) (all releases are currently marked pre-release, so pick the topmost one).
2. On your device, allow installing apps from unknown sources if prompted, then open the downloaded APK to install.
3. There's no Google Play listing yet, so updates are manual: download and install the latest release APK again when a new version comes out (release APKs are signed consistently, so this works as an in-place update).

Found a bug or have feedback? Please [open an issue](https://github.com/hsgirgin/song-ladder/issues/new) — include your Android version/device and steps to reproduce if it's a bug.

## What is implemented

- Compose app shell with `Matchups`, `Library`, and `Rankings` destinations
- Elo-based song ranking engine
- Local persistence with Room
- Manual song entry, sample pack import, JSON import/export
- iTunes song search/import
- YouTube Music playlist preview/import for public playlist links
- Song preview playback with iTunes and Deezer preview fallback

## Building from source

1. Open the repo root in Android Studio.
2. Sync the project with the checked-in Gradle wrapper.
3. Build and run the `app` module on an emulator or device.

See [CONTRIBUTING.md](CONTRIBUTING.md) for details on the signed-release CI setup.

## Import

Use the Library screen to search iTunes, add songs manually, load the sample pack, preview a public YouTube Music playlist, or import/export a Song Ladder JSON backup.

## Notes

- The app is local-first. JSON import replaces the current library after confirmation, while export writes a backup containing songs and ranking stats.

## Product specifications

- [Phase 1: Score-First Rankings](docs/phase-1-score-first-rankings-spec.md)
- [Product decision record](docs/product-decisions.md)
- [Phase 1 implementation plan](docs/phase-1-implementation-plan.md)
- [Phase 3 implementation plan: Albums](docs/phase-3-albums-implementation-plan.md)

## License

Song Ladder is licensed under the [GNU General Public License v3.0](LICENSE).
