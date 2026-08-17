# Phase 1: Score-First Rankings

**Status:** Approved product and implementation specification
**Scope:** Phase 1 only. This is a pre-production, clean-slate redesign; it does not require legacy database migrations or compatibility with older JSON exports.

## 1. Outcome

Song Ladder becomes a score-first personal music app.

- Users give songs an explicit personal score from **1.0 to 10.0**.
- **Rankings** is the primary place to browse that score-first ordering.
- **Matchups** records one-vs-one preferences and uses a hidden global Elo value only to resolve songs with the same explicit score.
- **Library** remains the place to import, add, and manage music.

Phase 1 ships songs only. Albums and Artists are visible as unavailable future views, but their aggregation and metadata behavior are out of scope.

## 2. Scope and non-goals

### In scope

- Manual 1.0–10.0 song scores with one-decimal precision.
- A score-first Rankings experience with adaptive grid and list presentations.
- Event-backed global Elo tie-breaking, matchup undo, cooldowns, and deterministic replay.
- Matchups with preview playback, swipe gestures, button fallbacks, and optional post-match rating.
- Playlist-import rating queue.
- Song detail, settings, deletion, tombstones, and ranking-history cleanup.
- A new clean-slate Room schema and JSON export/import format.

### Explicitly out of scope

- Score suggestions or automatic score changes. These are Phase 2.
- Album matching, album scores, missing-track imports, and release metadata. These are Phase 3.
- Artist-credit parsing, artist scores, aliases, and artist ranking. These are Phase 4.
- Compatibility with the current version-1 database or current JSON backups. The app is not in production.
- A separate Head-to-head sort mode. It would duplicate the score sort because Elo is used only for exact-score ties.

## 3. Navigation and terminology

### Top-level destinations

| Current destination | Phase 1 name | Role |
| --- | --- | --- |
| Rank | **Matchups** | Compare two songs and record a preference. |
| Library | **Library** | Import, add, remove, export, and restore music. |
| Leaderboard | **Rankings** | Browse score-first standings. |

The app launches to **Matchups**. It remains the start destination even when the library is empty; its empty state directs the user to import music.

Each top app bar exposes a Settings gear. A compact device uses the existing bottom navigation; medium and expanded layouts may use a navigation rail while retaining the same destinations.

### Terms

- **Personal score:** The user-controlled 1.0–10.0 value. It is the primary ranking input.
- **Elo:** A hidden, global, double-precision paired-comparison value. It only breaks exact personal-score ties.
- **Rated:** A song with a personal score. A score cannot be cleared back to unrated; it can always be changed.
- **Unrated:** A song without a personal score.
- **Matchup event:** A persisted winner/loser or skip event. Winner events update Elo; skip events never do.
- **Ranking subject:** A stable record referenced by Elo and matchup events. It can outlive a visible song as a tombstone.

## 4. Ranking contract

### 4.1 Visible ordering

Ranked songs have one authoritative order:

1. Personal score, descending.
2. Global Elo, descending, only when the personal score is exactly equal.
3. `lastRatedAt`, descending.
4. Normalized title, then stable ID, ascending, solely for deterministic total ordering.

Every rated song receives a unique ordinal rank from this order. Elo is never shown to users. Unrated songs have no ordinal rank and appear separately.

### 4.2 Score representation

- Valid scores are `1.0` through `10.0`, inclusive, in increments of `0.1`.
- Persist the score as a nullable integer number of tenths (`10..100`), not as a floating-point value.
- Render scores with exactly one decimal place using standard rounding rules.
- A confirmed first score or later edit updates `lastRatedAt`.
- Any score change recalculates rankings and may change other exact-score tie-break positions.

### 4.3 Elo model

Use one global Elo value per ranking subject. It is not partitioned by score group. A matchup between differently scored songs can influence future exact-score tie-breaks, but it can never make a lower personal score rank above a higher personal score.

#### Initial seed

For a rated song:

`seedElo = 1200 + 80 × (personalScore - 5.5)`

This maps `1.0` to `840`, `5.5` to `1200`, and `10.0` to `1560`. An unrated song seeds at `1200`.

Store Elo as `Double`; do not round it during updates or replay.

#### Per-song K-factor

Winner and loser use their own K-factor, so Elo is intentionally not zero-sum.

For a new or first-time rated song, use:

`Knew(n) = 16 + 48 × e^(-n / 4)`

where `n` is the number of completed, non-skipped matchups since the current responsiveness epoch. This starts near `64`, is near `30` after five matchups, near `20` after ten, and approaches `16`.

After a later manual score edit, reset that song's live responsiveness epoch to:

`Kedited(n) = 16 + 24 × e^(-n / 4)`

This starts at `40` and approaches `16`. Repeated score edits reset to `40`; they never stack above it.

For each winner event, calculate expected scores from the current replay Elo values, then update each participant with its recorded effective K-factor.

### 4.4 Replay and source of truth

The matchup event log is the source of truth. Cached Elo, wins, losses, skips, and app counters are derived state.

On every confirmed manual score change:

1. Seed all ranking subjects from their current personal score, or `1200` if unrated.
2. Replay every non-skipped winner event in chronological sequence order.
3. Write the resulting Elo and aggregate caches in the same Room transaction as the score change.
4. Reset the edited subject's future responsiveness epoch as described above.

Persist the effective K-factor for each participant in every winner event. This keeps replay deterministic when later events use an edited-song K epoch. Skips are persisted for activity statistics and matchup scheduling only.

### 4.5 Matchup selection

The selector must be deterministic under an injected random source and clock, and must never block the main thread.

1. Fewer than two active songs: no matchup.
2. With no rated songs: choose unrestricted active songs.
3. Once scores exist: approximately one in five matchups should include an unrated song when one is available.
4. Otherwise prefer pairs with the exact same personal score.
5. If no exact-score pair is eligible, select the nearest available scored pair. Prefer a score difference of at most `0.5`; if none exists, use the nearest remaining score to avoid a dead end.
6. Among otherwise eligible pairs, minimize absolute Elo difference first, then favor lower matchup exposure, then randomize exact ties.

#### Cooldown

- Treat a pair as unordered for repeat prevention.
- Any displayed pair, including a skipped pair, is blocked for the next three displayed matchups.
- If a two-song exact-score group is blocked, temporarily exclude both songs rather than immediately repeating the pair.
- If every eligible pair is blocked, show **You're caught up for now** with **Continue anyway**. That action picks a random blocked pair for one matchup only and then restores normal cooldown rules.

### 4.6 Winner, skip, and undo

- A winner must be chosen; there is no Equal outcome.
- Skip increments skip activity data and enters the pair cooldown, but makes no Elo or preference update.
- A successful winner decision stops playback, persists one event, updates aggregate counters, and presents an Undo control.
- Undo removes only the most recent winner event. It remains available until the next winner decision and removes the event, rewrites aggregate caches, and replays Elo atomically.

## 5. Rankings experience

### 5.1 Tabs, search, and presentation

Rankings contains a `Songs | Albums | Artists` tab row.

- **Songs** is functional in Phase 1.
- Tapping Albums or Artists shows a brief **Coming soon** message.
- Search is activated from a top-app-bar icon that expands into a search bar. Phase 1 searches songs only; Back closes search before leaving Rankings.
- The default first-use presentation is an artwork grid. Users can switch with a top-app-bar grid/list icon; persist their most recent choice across visits.
- Grid/list changes preserve the approximate visible anchor song. Use stable song keys, not positions.

### 5.2 Adaptive grid

- Use an adaptive grid: two columns on compact phones and additional columns on wider screens.
- A grid card shows a rank overlay on artwork, then title, artist, and score below it.
- Tap the main card area to play or pause a preview.
- Tap the score to open a compact rating sheet.
- Long press opens song details. Show a one-time hint: **Tap to preview · Hold for details**. Also expose a named accessibility action to open details.
- When playing, show an equalizer treatment and a Pause label. Reduced-motion mode uses a static equivalent.
- For unavailable previews, show a muted-preview icon; never silently disable the card's primary interaction.
- After saving a score, leave the updated card in place for about one second, then reorder. In reduced-motion mode, update position immediately.

### 5.3 List presentation

- Main row tap plays or pauses preview.
- The score control expands inline from its score target.
- A trailing 48dp expand/collapse control reveals wins, losses, and skips.
- Multiple rows may be expanded. Expansion state is keyed by song ID and lasts until the user leaves Rankings.
- Long press and the equivalent accessibility action open song details.

### 5.4 Unrated section

- Render unrated songs in a collapsible section below ranked songs.
- When rated songs exist, the section starts collapsed and displays its song count.
- In Phase 1, order unrated songs by most recently added first. Phase 2 may elevate pending suggestions.
- If no songs are rated, the Rankings empty state sends the user directly to the Unrated section.

### 5.5 Rating editor

Use the same control in grid sheets, list expansion, song detail, and the rating queue:

- Continuous 1.0–10.0 track with marked whole-number stops and 0.1 snapping.
- Only `1` and `10` labels are shown below the track.
- A value bubble above the thumb shows the current value while dragging.
- A primary `Save {value}` button and a secondary `Cancel` action confirm or discard the draft.
- Saving updates the visible score directly; no redundant toast is required.
- The control exposes proper slider semantics plus accessible 0.1 increment/decrement actions.

### 5.6 Song detail

Keep Phase 1 details intentionally small:

1. Artwork, title, artist, score, and rank.
2. Wins, losses, and skips.
3. Score editor.
4. A destructive Delete song action at the bottom.

Delete immediately returns to the previous screen and shows a 10-second Undo bar. On failure, leave the song visible and report the persistence error.

## 6. Matchups experience

### 6.1 Layout and choices

- On tall compact screens, show two vertically stacked cards.
- A feed-style upward swipe chooses the bottom card; a downward swipe chooses the top card.
- The cards track a deliberate feed-like drag and commit on release after a substantial drag or decisive flick (approximately one-quarter of the screen height, with a short-form-feed feel). The selected target receives outline/elevation/scale feedback, not color alone.
- Do not capture system-edge gestures.
- On compact-height or high-font-scale layouts that require scrolling, disable the global swipe gesture and use buttons.
- On wide/landscape layouts, show side-by-side cards: swipe left chooses the right song and swipe right chooses the left song.
- Visible, labeled Choose buttons remain available in every layout as the accessible fallback.

Rated matchup cards display a compact `#rank · score` artwork badge. Unrated songs show no score or rank in that area.

### 6.2 Preview playback

- Auto-preview is enabled by default and is controlled by a Settings toggle.
- Once the user initiates playback, play both available previews sequentially, alternating which side starts first between matchups.
- On a cold launch, autoplay is not armed. The user must tap a Play control once; autoplay stays armed for the foreground session.
- Backgrounding the app disarms autoplay. Returning requires another Play tap.
- A winner decision or Skip stops playback immediately.
- Rotation preserves the pair, selected first-preview order, current playback position, and playback state.
- Unavailable previews show the muted icon and are skipped in the sequence.

### 6.3 Controls and feedback

- Place the Skip floating action safely above system navigation near the bottom edge.
- Place the Undo bar above Skip after a successful winner decision.
- After a swipe/button choice, briefly highlight the winner and then either present rating work or load the next matchup.
- The first-run empty state explains that two songs are required, offers **Import playlist** as primary action, and **Load sample pack** as secondary action.

### 6.4 Post-match rating

Every eligible matchup that includes an unrated song offers lightweight rating work after the result:

1. Replace matchup cards with a rating step for the unrated winner, if any.
2. Then show the unrated loser, if any.
3. Each step uses the shared rating editor and its own **Skip for now** action beside Save.
4. A saved score advances immediately; skipped songs remain unrated.
5. After the final step, load the next matchup and apply normal autoplay rules.

## 7. Import and first-run flows

### 7.1 Rating after import

After a successful playlist import, automatically open a dedicated full-screen rating queue.

- One imported song appears at a time.
- It uses the shared auto-preview setting and rating editor.
- A progress bar advances after either a save or Skip for now.
- Saving automatically advances to the next song.
- Closing the queue immediately returns to the Library import result.
- Completion summarizes rated and skipped counts and offers **View Rankings** rather than navigating automatically.

For a single manual or search-added song, ask for a rating after persistence succeeds but allow the user to skip it. Do not make scoring a precondition of adding a song.

### 7.2 Contextual tips

Use contextual, one-time hints rather than an onboarding carousel. Cover grid interactions, Matchups gestures, and the score editor. Settings provides **Show tips again**.

## 8. Settings

Settings is available from the gear in every top app bar.

Phase 1 settings:

- **Auto-play matchup previews** — enabled by default.
- **Show tips again**.
- **Deleted ranking histories** — list tombstones individually and allow bulk selection.

Deleting ranking history is a separate, explicitly confirmed action. Show the number of events that will be erased. Erasure deletes the tombstone's events and replays every remaining ranking subject; it is not recoverable.

## 9. Persistence and data model

Use a new, clean-slate Room schema. Do not add `fallbackToDestructiveMigration`; a fresh install is expected for Phase 1 development.

### 9.1 Logical records

| Record | Required fields and role |
| --- | --- |
| `Song` | Active music metadata and a stable `rankingSubjectId`. |
| `RankingSubject` | Score tenths, Elo `Double`, cached wins/losses/skips, last-rated timestamp, responsiveness state, normalized identity, and tombstone status. |
| `MatchupEvent` | Stable sequence ID, timestamps, participant subject IDs, outcome, winner/loser when applicable, and effective K-factor for both participants. |
| `RankingSettings` | Autoplay, tips, grid/list choice, and any persisted view preference. |
| `AppStats` | Cached total winner-event and skip-event counts. |

Song deletion must not cascade to ranking subjects or matchup events. A deleted song becomes a tombstone that retains only what is needed for replay and possible restoration: ranking identity, source-scoped external ID, normalized title/artist, score seed, matchup references, and deletion time.

### 9.2 Re-import restoration

When importing a song, first match a tombstone by either:

1. Exact `(sourceType, externalId)`; or
2. Case-insensitive, trimmed title and artist.

If one tombstone matches, offer **Restore ranking history** or **Start fresh**. If multiple tombstones match, ask the user which history to restore. Starting fresh permanently suppresses that tombstone-to-new-song association but keeps the tombstone for historical replay.

### 9.3 Transactions

The following are atomic Room transactions:

- Winner event, per-song win/loss cache updates, app match count, Elo update, and cooldown history.
- Skip event, per-song skip cache updates, app skip count, and cooldown history.
- Undo of the last winner event, cache rewrite, and full Elo replay.
- Personal-score save, `lastRatedAt`, responsiveness reset, and full Elo replay.
- Song deletion/restoration and their visible/tombstone state changes.
- Ranking-history erasure and replay.

Recoverable repository failures use `Result`; the UI must not announce success before the transaction commits.

## 10. Export and restore

Phase 1 defines a new export schema. It exports:

- Active songs and all personal scores.
- Ranking subjects, including tombstones.
- Complete matchup history, effective K snapshots, counters, and cached Elo.
- Ranking settings needed to reproduce the local experience.

On import, validate the entire payload before replacing the local library. Recompute Elo and caches from the source-of-truth fields. If the stored Elo differs, use recomputed values and report only the number of repaired songs. The malformed-payload rule remains strict: do not alter the current library until the replacement transaction can succeed.

## 11. Compose, state, and accessibility contract

- Screens collect immutable `StateFlow<UiState>` with `collectAsStateWithLifecycle()`.
- ViewModels own navigation events, rating drafts that outlive configuration where necessary, playback session state, and all repository calls; reusable composables receive immutable state and callbacks only.
- Use Material 3 theme color, typography, and shape roles. Add named design constants for gesture thresholds, animation durations, artwork sizes, and layout breakpoints.
- Use `rememberSaveable` for search text, rating drafts, selected presentation, queue position, and other user-visible temporary state that must survive configuration change.
- Give every lazy item a stable key. Keep selection, sorting, pairing, and event replay outside hot composable bodies.
- All custom gesture surfaces expose semantic roles, labeled actions, and visible button alternatives. Every interactive target is at least 48dp.
- Use meaningful localized descriptions for artwork when it identifies a song; decorative visual effects use null descriptions. Playback, muted-preview, expanded/collapsed, and selected states must not rely on color alone.
- Test light and dark themes, 200% font scale, compact height, landscape/wide layouts, touch exploration, and reduced-motion behavior.
- Preview start/stop, audio focus, noisy-audio receiver cleanup, and lifecycle behavior remain in the injected preview player boundary. Never start playback directly from composition.

## 12. Test and acceptance matrix

### Domain and repository tests

- Exact score validation and nullable-tenths mapping.
- Elo seed values, per-song asymmetric K updates, and no intermediate rounding.
- New-song and edited-song K curves.
- Replay determinism after a score edit, undo, deletion, restoration, and history erasure.
- Primary ordering, exact-score Elo tie-break, recent-rating fallback, and deterministic final fallback.
- Selector behavior: unrestricted start, exact-score priority, nearest score fallback, 20% unrated inclusion, exposure tie-break, pair cooldown, caught-up state, and Continue anyway.
- Skip leaves Elo unchanged while updating skip statistics and cooldown.
- Every logical mutation remains one transaction and failure leaves all related state unchanged.
- Tombstone matching, ambiguous restoration, Start fresh suppression, and explicit history erasure.

### ViewModel tests

- Matchup choice, undo window, persistence failure, and no double-recording under rapid input.
- Rating draft save/cancel, replay notice only when a visible tie order changes, and no score clearing.
- Cold-start/background playback gating, alternating auto-preview order, rotation state, unavailable preview behavior, and stale prefetch guards.
- Queue save/skip progression, close destination, completion summary, and manual-add rating prompt.

### Compose tests

- Songs grid/list rendering, view toggle persistence, stable scroll anchoring, Unrated expansion, and Songs-only search.
- Rating control semantics and confirmation actions.
- Grid card preview, score sheet, long-press/detail accessibility action, muted state, and reduced-motion state.
- List expansion controls, multiple expanded rows, and 48dp controls.
- Matchups vertical and wide gestures, visible Choose fallback, Skip, Undo, compact-height fallback, and large-font reachability.
- Empty states, import queue progress/completion, contextual tips, Settings actions, and deletion Undo.

### Verification

Run from the repository root before handoff:

```bash
./gradlew testDebugUnitTest lintDebug
./gradlew assembleDebug
```

## 13. Suggested implementation order

1. Replace the clean-slate data model with ranking subjects, event log, settings, and export records; write migration-free schema tests.
2. Implement the replay engine, scoring repository contracts, transactional mutations, selector, tombstones, and exhaustive unit tests.
3. Rename navigation and build score-first Rankings, shared rating control, song detail, Settings, and its UI tests.
4. Rebuild Matchups around event recording, adaptive gestures, preview-session gating, Undo, and post-match rating.
5. Add playlist rating queue, first-run states, deletion/history management, export/import repair reporting, and end-to-end verification.
