# Phase 1 implementation plan

This plan turns the approved [Phase 1 specification](phase-1-score-first-rankings-spec.md)
into implementation slices. Complete each slice with its tests before starting
the next one; later UI slices depend on the domain contracts established in the
first two slices.

## Slice 1 — clean-slate persistence and contracts

Touch `domain/model`, `domain/repository`, `data/local`, `data/AppContainer`,
and JSON mapping code.

- Replace the current ranking-stat shape with `RankingSubject`, `MatchupEvent`,
  `RankingSettings`, `AppStats`, and nullable score-tenths fields.
- Keep `Song` as active metadata linked to a stable ranking subject. Deletion
  must leave a tombstone and must not cascade event history.
- Add Room DAOs and a clean-slate database definition. Do not add a destructive
  migration fallback; development installs are fresh.
- Define repository `Result` contracts for score saves, winner/skip, undo,
  deletion/restoration, history deletion, and export/import.
- Add new export records containing source-of-truth event history, tombstones,
  settings, and cached values.

Acceptance: database fakes and mapping tests cover defaults, null scores,
tombstones, unknown outcome values, and round-trip export/import.

## Slice 2 — domain engine and transactional repository

Touch `domain/engine`, `DefaultRankingRepository`, and repository tests.

- Implement integer-tenths score validation and formatting.
- Implement score seed, per-song K curves, asymmetric Elo updates, replay, and
  score-first ordering. Keep Elo as `Double` internally and round only in UI if
  ever needed for diagnostics.
- Implement deterministic selector ordering: exact scores, nearest scores,
  unrated cadence, closest Elo, exposure, seeded random tie-break, and the
  three-matchup unordered-pair cooldown.
- Implement caught-up/Continue anyway behavior, skip semantics, one-event undo,
  and replay after score edit.
- Implement tombstone matching, restoration choice, Start fresh suppression,
  and individual/bulk ranking-history deletion.
- Put event, counters, Elo, cooldown, score, undo, and replay writes in Room
  transactions. Inject clock and random source for deterministic tests.

Acceptance: domain and repository matrix in the Phase 1 specification passes,
including failure atomicity and replay determinism.

## Slice 3 — navigation and Rankings

Touch `ui/navigation`, `SongLadderApp`, the current leaderboard feature, new
rankings components, resources, and ViewModel tests.

- Rename Rank to Matchups and Leaderboard to Rankings while keeping Matchups as
  the launch destination.
- Build Songs/Albums/Artists tabs, Songs-only expanding search, adaptive grid,
  remembered list/grid mode, stable anchor preservation, and collapsed Unrated.
- Add `SongRatingControl` as a stateless reusable Material 3 component with
  slider semantics, 0.1 snapping, Save/Cancel, and localized strings.
- Add grid cards, list rows, inline score editing, expandable stats, score sheet,
  long-press/detail accessibility actions, muted-preview state, and reduced
  motion behavior.
- Add song detail and Settings gear in every top app bar. Include tips reset,
  autoplay setting, and ranking-history cleanup UI.

Acceptance: Compose tests cover grid/list, stable keys/anchor, score semantics,
Unrated, search, details, settings, large text, dark theme, and 48dp actions.

## Slice 4 — Matchups and playback session

Touch the current rank feature, preview player boundary, Matchups ViewModel,
resources, and tests.

- Render vertical feed-style cards on tall screens and side-by-side cards on
  wide screens. Use global vertical swipes only when layout/font size permits;
  always retain labeled Choose buttons.
- Add selected-card threshold feedback, Skip FAB, Undo bar, caught-up state,
  and post-choice rating steps (winner then loser, independent Skip for now).
- Implement autoplay arming after a Play tap, disarm on background, alternating
  first preview, sequential playback, immediate stop, rotation preservation,
  audio-focus/noisy-receiver cleanup, and stale-prefetch guards.
- Ensure rapid repeated input cannot record two events.

Acceptance: ViewModel and Compose tests cover both layouts, gesture fallback,
playback lifecycle, rotation, undo, skip, unavailable previews, and post-match
rating.

## Slice 5 — import queue and completion flows

Touch import/library ViewModels and screens, navigation, and resources.

- After playlist import succeeds, open a full-screen one-song rating queue with
  progress bar, shared autoplay behavior, Save, Skip for now, close handling,
  and rated/skipped completion summary.
- Add optional post-persistence rating for a manually added or searched song.
- Add View Rankings completion action without disrupting import-result context.
- Add contextual one-time hints and Settings “Show tips again”.

Acceptance: queue tests cover save/skip advancement, close destination,
completion counts, cancellation, and persistence failure.

## Slice 6 — deletion, export/import, and release verification

Touch deletion UI, JSON porter, repository replacement transaction, and tests.

- Add ten-second song-delete Undo and tombstone restoration prompts.
- Validate an entire import before replacing the library. Recompute Elo/caches
  from events and report only the repaired-song count.
- Add history deletion confirmation showing the number of events removed.
- Update README/spec links if behavior changes during implementation.
- Run the narrowest tests after each slice, then from the repository root:

  ```text
  ./gradlew testDebugUnitTest lintDebug
  ./gradlew assembleDebug
  ```

The current environment previously failed before tests at AAPT2 startup because
`/lib64/ld-linux-x86-64.so.2` was unavailable. Re-run both commands and report
that exact blocker if it remains.

## Definition of done

- All Phase 1 acceptance tests pass or have a documented environment blocker.
- Only intended files are changed; `git diff --check` is clean.
- UI state is exposed through immutable lifecycle-aware StateFlow and reusable
  composables receive state/callbacks rather than ViewModels or repositories.
- No Elo, provider internals, or Room entities leak through the UI boundary.
- Material 3, localization, semantic actions, stable lazy keys, lifecycle-safe
  playback cleanup, and reduced-motion behavior are present.
