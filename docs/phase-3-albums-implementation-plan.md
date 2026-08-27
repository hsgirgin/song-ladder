# Phase 3 implementation plan — Albums

This plan turns the approved [Phase 3: albums](product-decisions.md#phase-3-albums)
decisions — together with the binding "Deferred but binding UX principles" and the
"Album and artist detail" / "Restoration and correction detail" completeness-register
subsections of the same document — into implementation slices, in the same style as
[phase-1-implementation-plan.md](phase-1-implementation-plan.md). Complete each slice
with its tests before starting the next one; Slices 3-5 depend on the Room/domain
contracts and matching engine established in Slices 1-2.

Two facts materially shape this plan versus Phase 1's:

1. **The app now ships signed release APKs to real users** (README's CI publishing
   flow, and a real `MIGRATION_1_2` already exists in `SongLadderDatabase.kt` —
   `version = 2`, wired via `.addMigrations(MIGRATION_1_2)`, no destructive fallback).
   Phase 3's schema changes must ship as a real, additive `Migration(2, 3)`, never a
   destructive reset — Phase 1's "development installs are fresh" note no longer
   applies.
2. **iTunes (already integrated for search/preview) can supply full release
   metadata.** `ItunesSongPreviewResolver`'s `lookupCollection(collectionId)` call
   (`entity=song`) already returns the collection header plus every track on that
   release in one request — sufficient for missing-track detection without a new
   provider integration, once the DTO is extended with `trackNumber`/`trackCount`.

This plan reuses established patterns directly: `DefaultRankingRepository`'s
`database.withTransaction { ... }` plus injectable `timeSource: TimeSource` and
`repositoryScope`/`shareIn` conventions, the export/import pipeline's "persist
user-explicit state, recompute matcher-derived state" philosophy
(`ExportEntities.recomputeDerivedStateWithRepairCount`), and the existing
nullable-id-driven dialog overlay convention for detail screens (`SongDetailDialog`)
rather than introducing the app's first NavHost detail route.

## Slice 1 — album domain model, Room migration, and export/import plumbing

Touch `domain/model/Models.kt`, a new `domain/model/AlbumModels.kt`,
`data/local/Entities.kt`, `data/local/SongLadderDatabase.kt`, a new
`data/local/AlbumDao.kt`, a new `data/local/AlbumMappers.kt`,
`data/local/ExportMappers.kt`, and migration/persistence tests.

- **Album identity is derived, not synced.** A song's own `title`/`artist`/`album`
  fields (normalized the same way `RankingSubjectEntity.normalizedTitle`/
  `normalizedArtist` already are) determine which local album a track belongs to.
  `AlbumEntity` gets a deterministic composite id from the normalized artist+album
  pair, so grouping falls out of the existing song list (like `rankedSongs`/
  `unratedSongs` already fall out of `songRepository.observeSongs()`) with no
  backfill hook needed in `DefaultSongRepository.addSong`/
  `DefaultImportRepository.importTracks`/`restoreSong`. `AlbumEntity` only persists
  state that can't be derived: provider match state and per-track exclusions.
- Add `AlbumEntity` (`tableName = "albums"`): `id`, `title`, `artist`,
  `artworkUrl: String?`, `normalizedTitle`, `normalizedArtist`,
  `providerSourceType: String = "ITUNES"`, `providerCollectionId: String?`,
  `providerTrackCount: Int?`, `matchStatus: String` (`PENDING`, `AUTO_MATCHED`,
  `NEEDS_REVIEW`, `CONFIRMED`, `NO_MATCH`), `matchConfidence: Double?`,
  `createdAt: Long`, `lastMatchAttemptAt: Long?`, `lastMatchedAt: Long?`.
- Add `AlbumTrackExclusionEntity` (`tableName = "album_track_exclusions"`,
  `@PrimaryKey songId`, plus `albumId`, `excludedAt: Long`): row presence means that
  owned track is one-tap-excluded from its album's average. Scoped per
  `(albumId, songId)`, untouched by any matcher/refresh path — satisfies "preserve
  all song/ranking state" and "exclusions ... remain when metadata is restored
  unless the user requests refresh" by construction.
- Add `AlbumMissingTrackEntity` (`tableName = "album_missing_tracks"`, indexed on
  `albumId`): `albumId`, `providerTrackId`, `title`, `trackNumber: Int?`,
  `artworkUrl: String?`. Fully matcher-derived, rebuilt on every successful lookup,
  **never exported** — mirrors how Elo/caches are rebuilt from event history rather
  than trusted as stored fact.
- Add domain types in a new `domain/model/AlbumModels.kt` (kept separate from the
  already-358-line `Models.kt`): `AlbumMatchStatus`, `Album`, `AlbumTrackExclusion`,
  `AlbumMissingTrack`, `RankedAlbum(rank, album, scoreTenths,
  includedRatedTrackCount, totalOwnedTrackCount)`, `AlbumDetail(album,
  tracks: List<AlbumTrackRow>, missingTracks, scoreTenths,
  includedRatedTrackCount)`, `AlbumTrackRow(song, excludedFromAverage,
  trackNumber)`, and a pure `fun computeAlbumScoreTenths(includedRatedScoreTenths:
  List<Int>, providerTrackCount: Int?): Int?`.
  **Threshold rule (confirmed with the user, refining the literal "at least three
  tracks" spec text):** the rated-track minimum scales to release size instead of
  a flat 3 — `ceil(providerTrackCount / 2.0)` rated *included* tracks, based on
  the real release's total track count (not how many the user owns), so a
  partially-owned large album can stay permanently unranked if the user never
  rates enough of it — an accepted tradeoff. Returns `null` (unranked) whenever
  `providerTrackCount` is unknown (album not yet `AUTO_MATCHED`/`CONFIRMED`) or
  the included-rated count is under that threshold; otherwise the rounded
  one-decimal simple average, consistent with `formatScoreTenths`/
  `scoreTenthsForElo`'s rounding. **Singles are excluded from Albums entirely**:
  once a local grouping resolves (`AUTO_MATCHED`/`CONFIRMED`) to
  `providerTrackCount == 1`, it is filtered out of both `rankedAlbums` and
  `incompleteAlbums` at the query layer (Slice 3) — its song keeps ranking
  normally under Songs, and no `AlbumEntity` row needs deleting, it's simply
  never surfaced. A single briefly appearing in "Incomplete albums" before its
  first match resolves is an accepted, transient edge case (see Slice 3's open
  callout).
- Extend `RankingSettings`/`RankingSettingsEntity`/`RankingSettingsExport` with
  `metadataRetrievalEnabled: Boolean = true` (persistent, enabled-by-default
  toggle; its Settings UI ships in Slice 5, but the column belongs in this
  migration).
- Add `AlbumExport`/`AlbumTrackExclusionExport` `@Serializable` types; add
  `albums: List<AlbumExport> = emptyList()`, `albumTrackExclusions:
  List<AlbumTrackExclusionExport> = emptyList()` to `ExportPayload`; bump
  `schemaVersion` default to `2`. Following the established "recompute
  matcher-derived state" philosophy: `AlbumExport` carries only user-explicit state
  (`id`, `title`, `artist`, `artworkUrl`, `normalizedTitle`, `normalizedArtist`,
  `createdAt`, plus `confirmedProviderSourceType`/`confirmedProviderCollectionId`
  populated only when `matchStatus == CONFIRMED`). `AUTO_MATCHED`/`NEEDS_REVIEW`/
  `NO_MATCH` state, `matchConfidence`, and `AlbumMissingTrackEntity` rows are
  dropped on export and rebuilt post-import by the matcher.
  `AlbumTrackExclusionExport` (songId, albumId) is persisted verbatim.
- Update `ExportPayload.validateForImport()` to accept `schemaVersion in 1..2` (old
  backups without albums stay importable via kotlinx.serialization field defaults)
  plus referential-integrity checks: exclusion rows reference known song ids, each
  `songId` in at most one exclusion.
- Add `MIGRATION_2_3` to `SongLadderDatabase.kt` (raw-SQL `Migration(2, 3)`,
  `CREATE TABLE IF NOT EXISTS` for the three new tables, `ALTER TABLE
  ranking_settings ADD COLUMN metadataRetrievalEnabled INTEGER NOT NULL DEFAULT
  1`), bump `version = 3`, wire via `.addMigrations(MIGRATION_1_2,
  MIGRATION_2_3)`, extend `entities = [...]` and DAO accessors — following
  `MIGRATION_1_2` exactly. Never fall back to destructive recreation.

Open callout: deterministic text-grouping means two textually different local
groupings that are actually the same real release (e.g. "Abbey Road" vs. "Abbey
Road (Remastered)") stay as separate `AlbumEntity` rows/scores in this design —
cross-group reconciliation is out of scope here. Worth a decision before Slice 2
if it matters.

Acceptance: DAO tests cover `AlbumDao`/exclusion/missing-track CRUD; a migration
test extends the existing v1->v2 pattern with a v2->v3 case asserting existing
rows survive and the new tables/column are queryable; mapping tests cover
`AlbumExport` defaults and the `CONFIRMED`-only serialization of
`providerCollectionId`; a round-trip export/import test proves an `AUTO_MATCHED`
album downgrades to `PENDING` post-import while a `CONFIRMED` album and all
exclusions survive verbatim; `validateForImport()` still accepts a
`schemaVersion = 1` fixture with no `albums` field.

## Slice 2 — metadata provider, matching engine, connectivity retry, and `AlbumRepository`

Touch `data/itunes/ItunesMusicSourceClient.kt`, a new
`data/itunes/ItunesAlbumMetadataProvider.kt`, a new
`domain/engine/AlbumMatchingEngine.kt`, `domain/repository/Repositories.kt`, a new
`data/connectivity/NetworkAvailabilityMonitor.kt`, a new
`data/repository/DefaultAlbumRepository.kt`, `data/AppContainer.kt`,
`AndroidManifest.xml`.

- Extend `ItunesTrackResult` in place with nullable `trackNumber`, `trackCount`,
  `artistId`, `collectionArtistId` — additive and safe (`ignoreUnknownKeys = true`
  already set; both existing consumers access fields by name).
- Add `domain/repository/AlbumMetadataProvider`: `suspend fun
  searchReleases(artist: String, album: String): Result<List<AlbumReleaseCandidate>>`
  and `suspend fun lookupRelease(collectionId: String):
  Result<AlbumReleaseLookup>` (reusing the same `lookupCollection(collectionId)`
  shape `ItunesSongPreviewResolver` already exploits). Add
  `AlbumReleaseCandidate`/`AlbumReleaseLookup`/`AlbumReleaseTrack` to
  `AlbumModels.kt`.
- Implement `ItunesAlbumMetadataProvider` in `data/itunes/` (transport lives next
  to `ItunesMusicSourceClient`/`ItunesSongPreviewResolver`; scoring logic stays in
  `domain/engine`, matching the existing split). Cache lookups in a
  `ConcurrentHashMap<String, CachedX>` **with a TTL** (following
  `DeezerSongPreviewResolver`'s pattern, not `ItunesSongPreviewResolver`'s
  permanent cache), since matches must be retryable. Treat non-2xx (iTunes's ~20
  req/min/IP -> 403) as a distinct `AlbumMetadataUnavailable` failure, separable
  from "confidently no match."
- Implement `AlbumMatchingEngine` (pure, unit-testable like `EloMatchupEngine`/
  `SuggestionEngine`): scores candidates against owned track titles (normalized
  title/artist similarity + track-count proximity + title overlap) and classifies
  `AUTO_MATCHED` / `NEEDS_REVIEW` (top candidates too close) / `NO_MATCH`.
  Separately computes the missing-track diff from a confirmed/auto-matched
  `AlbumReleaseLookup` vs. owned songs.
- Implement `NetworkAvailabilityMonitor` (`data/connectivity/`): thin
  `ConnectivityManager.NetworkCallback` wrapper registered once from
  `AppContainer`, exposing `onAvailable: () -> Unit`. No WorkManager — consistent
  with the app's plain manual-DI style and the absence of any existing
  background-work framework. Add `<uses-permission
  android:name="android.permission.ACCESS_NETWORK_STATE" />`.
- Implement `DefaultAlbumRepository` (`AlbumRepository` interface in
  `Repositories.kt`): `observeAlbums(): Flow<List<Album>>`,
  `observeAlbumDetail(albumId): Flow<AlbumDetail>`, `setTrackExcluded(albumId,
  songId, excluded): Result<Unit>`, `chooseRelease(albumId, providerCollectionId):
  Result<Unit>` (explicit pick -> `CONFIRMED`), `addMissingTracks(albumId,
  providerTrackIds): Result<Int>`, `refreshMetadata(albumId): Result<Unit>`
  (explicit refresh only), `retryPendingMatches(): Result<Unit>`. An internal
  auto-discovery collector (built like `DefaultRankingRepository.suggestionsFlow`'s
  `shareIn` off `repositoryScope`) diffs live (artist,album) groupings from
  `songDao.observeSongsWithStats()` against known `AlbumEntity` rows, upserts new
  groupings as `PENDING`, and launches bounded per-album match attempts gated on
  `metadataRetrievalEnabled` and `lastMatchAttemptAt` backoff — this alone
  satisfies "start metadata matching automatically after songs are added," with no
  changes to `DefaultSongRepository`/`DefaultImportRepository`.
  `NetworkAvailabilityMonitor.onAvailable` calls `retryPendingMatches()`. All
  writes go through `database.withTransaction { ... }`, mirroring
  `DefaultRankingRepository`. Inject `timeSource: TimeSource` for deterministic
  tests.
- Wire `AppContainer`: construct `ItunesAlbumMetadataProvider`,
  `NetworkAvailabilityMonitor`, `val albumRepository: AlbumRepository =
  DefaultAlbumRepository(...)`, then `networkAvailabilityMonitor.onAvailable {
  repositoryScope.launch { albumRepository.retryPendingMatches() } }`.

Acceptance: `AlbumMatchingEngineTest` (unit) covers auto-match/ambiguous/no-match
classification and missing-track diffing with fixed fixtures;
`ItunesAlbumMetadataProviderTest` (unit, fixture-JSON style) covers extended-field
parsing, TTL cache expiry, 403 handling; `DefaultAlbumRepositoryTest` covers
auto-discovery from inserted songs, transactional exclusion writes,
`chooseRelease` setting `CONFIRMED` and surviving a later auto-match pass,
`refreshMetadata` re-running the matcher, and `retryPendingMatches` respecting
`metadataRetrievalEnabled` and succeeding after a prior failure.

## Slice 3 — Albums tab list UI

Touch `ui/rankings/RankingsViewModel.kt`, `ui/rankings/RankingsScreen.kt`, new
`ui/rankings/RankingsAlbumsSection.kt`, `AlbumsGridSection.kt`,
`AlbumsListSection.kt`, `ui/rankings/RankingsGridSection.kt` (visibility only),
`ui/rankings/RankingsSharedUi.kt`, `res/values/strings.xml`.

- `RankingsViewModel`: take `albumRepository: AlbumRepository`; add a parallel
  `combine()` chain off `albumRepository.observeAlbums()` (mirroring the existing
  `songsAndSuggestionRows`/`rankingState` chain) producing `rankedAlbums:
  List<RankedAlbum>` (`computeAlbumScoreTenths` non-null, i.e. rated-included
  count has cleared the `ceil(providerTrackCount / 2.0)` bar, score-descending)
  and `incompleteAlbums: List<Album>` (matched but under that bar — distinct from
  Songs' "unrated," do not conflate). Both lists filter out any album whose
  resolved `providerTrackCount == 1` (singles never appear in Albums; their song
  still ranks normally under Songs). Add both fields to `RankingsUiState`.
- `selectTab(tab)`: stop forcing `RankingsStatus.ComingSoon` for `ALBUMS` only
  (`ARTISTS` still forces it — stays a Phase 4 placeholder).
- `RankingsScreen.kt`: split the tab-dispatch `when` so `RankingsTab.ALBUMS ->
  RankingsAlbumsContent(...)` while `ARTISTS -> ComingSoonContent` stays alone. No
  change needed to the Songs-only search-icon gating.
- `RankingsAlbumsContent` mirrors `RankingsSongsContent`'s structure (own scroll
  state, anchor preservation across grid/list switches) and dispatches on the
  **shared** `uiState.presentation` toggle (reuse, don't fork per-tab — simpler,
  and nothing in the spec calls for per-tab memory).
- Add non-generic `AlbumsGrid`/`AlbumsGridCard`/`AlbumsList`/`AlbumsListRow`
  (mirroring the existing non-generic `RankingsGridSection.kt`/
  `RankingsListSection.kt` split — Song grid/list are hardcoded to `Song`/
  `RankedSong` and bake in a settable score editor that doesn't fit a derived
  album average). Reuse `SongArtwork`, `ScoreBadge`, `ScoreTransitionBadges` as-is;
  relax `RankBadge` from `private` to `internal` (or move it to
  `RankingsSharedUi.kt`) for album cards to reuse — no score-editing UI needed.
- Complete albums render rank + `ScoreBadge`; incomplete albums render a new
  "Unranked" badge instead. Cards with `matchStatus == NEEDS_REVIEW` show a small
  "Check release" label beside the score/unranked badge.
- Add an "Incomplete albums" section rendered separately from complete albums.

Open callouts:
- The spec requires a separate Incomplete-albums section but doesn't specify
  collapse/order behavior the way it does for Unrated Songs ("collapsed with a
  count when ranked songs exist, newest-added first"). This plan defaults
  Incomplete Albums to that same treatment for consistency — flag if a different
  behavior is actually wanted.
- A single can transiently appear in "Incomplete albums" for the short window
  between its local grouping being discovered and its first successful match
  resolving `providerTrackCount == 1` (before that, the matcher doesn't know
  it's a single). Accepted as a minor, self-correcting edge case rather than
  adding a pre-match heuristic.

Acceptance: `RankingsViewModelTest` covers the `rankedAlbums`/`incompleteAlbums`
split (the `ceil(providerTrackCount / 2.0)` threshold at a few release sizes,
exclusion-aware averaging, singles filtered out of both lists once matched,
unmatched albums treated as incomplete, `ComingSoon` no longer set for `ALBUMS`,
`ARTISTS` unaffected). Compose tests cover grid/list Albums content: stable lazy
keys, complete/incomplete separation, "Unranked" vs. numeric badge, "Check
release" visibility gated on `matchStatus`, anchor preservation across
grid<->list, shared presentation toggle round-trip.

## Slice 4 — album detail dialog and ambiguous-match review

Touch new `ui/rankings/AlbumDetailDialog.kt`, `ui/components/MatchCandidateRow.kt`,
`ui/rankings/AlbumMatchReviewSection.kt`, `ui/rankings/RankingsViewModel.kt`,
`ui/rankings/RankingsScreen.kt`, `res/values/strings.xml`.

- Follow the existing nullable-id-driven overlay convention (`SongDetailDialog`)
  rather than a NavHost route — the app has zero precedent for argument-based nav
  routes; everything today (Settings, AddSongs, ImportRatingQueue,
  TombstoneConflict, SongDetail) is a dialog/sheet gated by local or ViewModel
  state. Add `detailAlbumId: String?` with `showAlbumDetails(id)`/
  `hideAlbumDetails()`, mirroring `showDetails`/`hideDetails`. Subscribe to
  `albumRepository.observeAlbumDetail(id)` only while `detailAlbumId != null`
  (richer data than the list-level flow carries), merged into `uiState` the way
  `previewStates` is merged today.
- `AlbumDetailDialog` (full-screen `Dialog(usePlatformDefaultWidth = false)`,
  matching the existing overlay convention): artwork, title/artist, rank +
  `ScoreBadge`/"Unranked"; a track list (track number, title, per-track
  `ScoreBadge`, one-tap exclude/include toggle -> `setTrackExcluded`); a
  Missing-tracks section (checkbox rows + single "Add N tracks" bulk-confirm,
  reusing `SuggestionsSection`'s select-then-confirm shape); when `matchStatus ==
  NEEDS_REVIEW`, an inline candidate picker (via `MatchCandidateRow`) calling
  `chooseRelease`; an explicit "Refresh metadata" action calling
  `refreshMetadata` (distinct from any automatic behavior — only explicit refresh
  may touch restored metadata, per the binding UX principle).
- Extract `ui/components/MatchCandidateRow.kt` (title/artist/artwork/track-count
  summary + primary action) — reusable from `ui/library` in Slice 5, alongside
  this codebase's other cross-feature composables (`ScoreBadge`, `SongArtwork`,
  `SongRatingControl`).
- Add `AlbumMatchReviewSection`, shown above the Albums list/grid exactly where
  `SuggestionsSection` sits above Songs' Unrated list: one row per `NEEDS_REVIEW`
  album with a "Choose" action opening `AlbumDetailDialog` with the candidate
  picker pre-expanded (one candidate-choosing UI, not two).
- `RankingsScreen.kt`: render `uiState.albumDetail?.let { AlbumDetailDialog(...) }`
  alongside the existing `SongDetailDialog` block.

Open callout: "exclusions remain when metadata is restored unless the user
requests refresh" is read literally here as: `refreshMetadata` never deletes
`AlbumTrackExclusionEntity` rows (only `AlbumMissingTrackEntity` is rebuilt) —
exclusions are independent of which release is matched. If the intent is instead
that refresh *may* reset exclusions, that's a one-line change to
`DefaultAlbumRepository.refreshMetadata` (Slice 2). Flagging since the spec
sentence is genuinely ambiguous between the two readings.

Acceptance: `RankingsViewModelTest` covers detail-flow lazy subscription (active
only while `detailAlbumId != null`, cancelled on dismiss), exclusion toggling
recomputing `scoreTenths` optimistically, missing-track bulk-add invoking the
repository once with selected ids. Compose tests cover `AlbumDetailDialog`
(artwork/rank/score, exclude toggle, missing-tracks bulk-confirm, candidate
picker only when `NEEDS_REVIEW`, 48dp targets) and `AlbumMatchReviewSection` (row
rendering, "Choose" opening detail).

## Slice 5 — import-flow ambiguous-match prompt, settings toggle, and verification

Touch `ui/library/LibraryViewModel.kt`, `ui/library/AddSongSheet.kt` (or a new
`AmbiguousSearchMatchDialog.kt`), `domain/repository/Repositories.kt`,
`data/repository/DefaultSongRepository.kt`, `ui/settings/SettingsViewModel.kt`,
`ui/settings/SettingsDialog.kt`, `res/values/strings.xml`, README/spec links.

- Add `SongRepository.findAmbiguousMatches(candidate: MusicTrackCandidate):
  Result<List<Song>>` (default `Result.failure(UnsupportedOperationException(...))`,
  matching the existing optional-capability convention used by `restoreSong`/
  `findTombstoneMatches`) — a fuzzy near-duplicate check against *active* songs,
  distinct from `findTombstoneMatches`'s exact/normalized check against deleted
  tombstones. Implement in `DefaultSongRepository`.
- In `LibraryViewModel.addSearchResult(candidate)`, after the existing (unchanged)
  `findTombstoneMatches` check and before `importSearchCandidate`, call
  `findAmbiguousMatches`; if non-empty, hold a new local `ambiguousSearchMatch`
  state and render a confirmation surface reusing `MatchCandidateRow` ("is this
  the same track?" per candidate, plus an explicit "No, add as new" fallback that
  proceeds with the original import). This is the spec's second metadata-
  correction path, and deliberately touches the Library search flow, not Albums.
- Add a `metadataRetrievalEnabled` switch row in `SettingsDialog.kt` (reusing the
  existing switch-row pattern and `autoPlayMatchupPreviews`-style wiring) and
  `setMetadataRetrievalEnabled(enabled)` in `SettingsViewModel.kt`, calling
  `settingsRepository.saveSettings(...)` like `setAutoPlay`/`showTipsAgain`. Label
  text makes clear the setting controls only song artist/title/album metadata
  retrieval.
- Update README/spec links if behavior changed materially during implementation.
- Run the narrowest tests after each slice, then from the repository root:

  ```text
  ./gradlew testDebugUnitTest lintDebug
  ./gradlew connectedDebugAndroidTest
  ./gradlew assembleDebug
  ```

Acceptance: `LibraryViewModelTest` covers the ambiguous-search-match gate (prompt
surfaces, "same track" cancels the add, "add as new" proceeds through the
unchanged `importSearchCandidate` path, existing tombstone-conflict path
unaffected since it still runs first); `DefaultSongRepositoryTest` covers
`findAmbiguousMatches` fuzzy-matching fixtures; a settings test covers the toggle
round-tripping through `RankingSettings` and gating Slice 2's auto-discovery/retry
(integration-level check only).

## Definition of done

- All Phase 3 acceptance tests pass, or have a documented environment blocker.
- The migration path is `MIGRATION_1_2` -> `MIGRATION_2_3` only; no destructive
  fallback anywhere in `SongLadderDatabase.getDatabase`; a device on schema v2
  with real ranking history upgrades in place.
- Only intended files are changed; `git diff --check` is clean.
- Album UI state is exposed through `RankingsUiState`'s existing immutable,
  lifecycle-aware `StateFlow`; no Room entities, `AlbumMatchingEngine` internals,
  or provider DTOs leak past the repository boundary.
- No album average silently includes an excluded track; no metadata correction/
  refresh/restore removes ranking or album state except through the one explicit
  "Refresh metadata" action.
- An album release match is never silently applied without either high automatic
  confidence or explicit user choice; an ambiguous search-result match is never
  added without explicit confirmation (extends the Phase 2 "a suggestion never
  becomes a visible score without explicit accept" invariant to albums).
- Material 3, localization (`rankings_album_*`/`rankings_incomplete_albums_*`
  string keys), semantic actions, stable lazy keys, and 48dp targets are present
  across all new Albums composables.
