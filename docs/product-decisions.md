# Song Ladder product decision record

This document records the decisions made during product grilling. It is a
canonical handoff for future agents. The executable Phase 1 requirements are
in [phase-1-score-first-rankings-spec.md](phase-1-score-first-rankings-spec.md);
this document also preserves decisions deferred to later phases so they are
not rediscovered or contradicted.

## Product direction

- The primary user-facing concept is an absolute personal score from `1.0` to
  `10.0`, displayed with one decimal place.
- Rankings is the main leaderboard. Matchups are supporting evidence and a
  way to refine ordering, not a replacement for the visible score.
- Visible scores are never percentiles and never auto-generated.
- The app is pre-production. Phase 1 is a clean-slate schema and export format;
  old Room data and old JSON backups do not need compatibility.
- The delivery order is manual scoring, suggestions, albums, then artists.
- The top-level destinations are **Matchups**, **Library**, and **Rankings**.
  Rankings has **Songs**, **Albums**, and **Artists** tabs; Albums and Artists
  show a brief “Coming soon” message until their phases ship.
- Keep one Library destination with a view switcher rather than separate
  libraries. Preserve the selected list/grid presentation across visits.

## Phase 1 decisions

### Scores and ordering

- A score is required to be between `1.0` and `10.0`, in `0.1` increments. It
  can be edited but not cleared back to unrated.
- Store score tenths as an integer and render exactly one decimal place.
- Songs sort by score descending. Exact score ties use hidden global Elo,
  then most recently rated, then deterministic title/ID ordering.
- The only shipped sort is Score (highest visible `/10` first); any temporary
  sort selector resets to Score on entry. The ordinal rank always reflects the
  active score ordering.
- Show the user’s score and ordinal rank; never show Elo.
- There is no separate Head-to-head sort mode.
- Unrated songs have no score/rank and appear in a separate, collapsible
  Unrated section. The section is collapsed with a count when ranked songs
  exist and is newest-added first.

### Matchups and Elo

- A matchup requires a winner or Skip; there is no draw.
- Elo is one global `Double` per song and only breaks exact visible-score ties.
- Seed rated songs with `1200 + 80 × (score - 5.5)`; unrated songs seed at 1200.
- Use a per-song K-factor that decreases from 64 toward 16 over roughly ten
  matchups. A manual score edit resets that song halfway to `K=40`; the edit
  does not erase its effect on opponents.
- Replay every confirmed score change from score-based seeds through the full
  event history. Persist effective K values in events for deterministic replay.
- Confirmed score changes can show a brief “Rankings recalculated” notice when
  visible tie order changes.
- Choose the closest-Elo eligible pair, favoring lower exposure and randomizing
  exact ties. Prefer same-score pairs and then nearest scores (within 0.5 when
  possible); include an unrated song about one in five matchups.
- The same unordered pair, including a skipped pair, is cooled down for the
  next three matchups. If everything is blocked, show “Caught up” and a
  Continue anyway action that chooses a random blocked pair.
- Undo only the latest winner decision and only until the next decision.
- Backups contain history and cached Elo. Import recomputes and reports only the
  count of repaired songs.
- Deleted songs retain tombstones and ranking history. Re-import can restore by
  exact external identity or normalized title/artist; ambiguous matches ask the
  user, and Start fresh suppresses that association. Settings offers both
  individual and bulk history deletion.

### Matchup interaction and playback

- On tall screens, cards are vertical. Feed-style global swipes choose the
  bottom song on swipe up and the top song on swipe down; commit on release
  after roughly a short-form-video-sized drag. Buttons remain as fallback.
- On wide screens, cards are side by side; left/right swipes choose the
  opposite card. In compact-height or large-font layouts, use buttons instead
  of global swipes.
- Highlight the selected song briefly, then show the rating prompt when needed.
- Auto-preview is on by default. The first Play tap arms autoplay for the
  foreground session; backgrounding requires a fresh tap. Play both previews
  sequentially, alternate which song starts, stop immediately on choice/Skip,
  and preserve pair and playback position through rotation.
- Skip has a floating action near the bottom edge; Undo is shown above it.
- If an eligible result includes unrated songs, prompt for winner first and
  loser second. Save or Skip for now is independent for each song.
- The post-match rating steps use the same confirmation behavior as every other
  rating editor: Save commits the shown value immediately and Skip for now
  advances without rating that song.

### Rankings and rating UI

- Default to an adaptive artwork grid and remember the user’s grid/list choice.
- Grid cards show artwork rank, title, artist, and score. Tap the card to
  play/pause; tap the score for a compact editor; long-press for details.
- List rows have inline score editing and a trailing expand/collapse control;
  multiple rows may remain expanded until leaving Rankings. Expanded details
  show wins, losses, and skips.
- The shared rating control is a continuous track with ten whole-number stops,
  0.1 snapping, only `1` and `10` labels, and a value bubble above the thumb.
  Confirm with “Save 8.7” or Cancel. Save updates immediately, briefly keeps
  the card visible, then reorders after about one second.
- Song detail shows artwork, title, artist, score, rank, wins/losses/skips, the
  editor, and Delete. Delete returns immediately with a ten-second Undo.
- Search is Songs-only in Phase 1 and expands from a top-bar search icon.
- Contextual one-time hints explain grid, matchup, and rating gestures.
  Settings includes “Show tips again”.
- Use Material 3, UDF/StateFlow, lifecycle-aware collection, stable lazy keys,
  48dp targets, semantic actions, reduced-motion equivalents, and visible
  button fallbacks for custom gestures.

### Import and library

- Importing a playlist opens a full-screen, one-song-at-a-time rating queue.
- Show a progress bar; Save advances automatically; “Skip for now” is beside
  Save. Completion shows rated and skipped counts plus View Rankings.
- A manually added or searched song is persisted first, then optionally offered
  for rating. The user may skip it.
- The Library remains the import/manage surface and keeps import result context.

## Phase 2: score suggestions

- Comparisons can suggest a precise score, but never auto-score or silently
  convert a suggestion into a user rating.
- Show both comparison count and score gap, plus estimate stability. Keep these
  as secondary details behind an expandable row section and show the underlying
  values in the review screen.
- Use the last five comparisons for stability. All five must have rated
  opponents; stability means the estimate moves no more than 0.5 over those
  comparisons. When evidence is weak, prompt the user to choose more anchor
  songs; the user chooses the anchors.
- Recalculate all pending suggestions when new anchors arrive and show a brief
  “Suggestions updated” notice.
- The review screen is the main suggestion surface. Provide Accept selected,
  Edit inline, and Later. Keep selected values frozen until submission and
  apply acceptance immediately after the required confirmation. Editing a
  value is normal, not a bulk confirmation flow.
- Only a clear, stronger-evidence threshold makes the system decide a value;
  otherwise leave the suggestion pending. Dismissed suggestions are recalculated
  later from new evidence. When the score must be shown in a tier, use the
  nearest score within 0.5, but preserve the precise suggested value itself.
- Suggestions should prioritize songs where manual score and comparison
  evidence disagree by at least 0.5. Repeats are allowed to resolve a
  high-priority disagreement; otherwise continue with same-score tie-breakers.
- Suggestions remain secondary to explicit scores and never change the visible
  score without user acceptance.

## Phase 3: albums

- Albums are excluded from Phase 1 and have their own rank list and score.
- In the Albums view/menu, show an **Incomplete albums** section separately
  from complete album results.
- Show every named real-world release and include all tracks on the selected
  release when computing its average.
- Fetch release metadata from a music metadata provider. Use the best automatic
  match temporarily and show a small “Check release” label beside the score.
  Ask the user to choose only when the match cannot be determined.
- Start metadata matching automatically after songs are added; retry it later
  on any connection when the provider is unavailable.
- Offer both metadata correction paths: let the user choose a release when the
  automatic result is ambiguous, and ask whether an ambiguous search result is
  the same track before adding it.
- Show missing tracks and offer to add selected tracks; add all selected tracks
  in one confirmation. Ask whether an ambiguous result is the same track.
- Retry metadata automatically later on any connection. The setting is enabled
  by default and controls only song artist/title/album metadata retrieval.
- Album score is the simple average of rated tracks, shown to one decimal place.
  Hide it until at least three tracks are rated; otherwise show the album as an
  unranked result. Users may exclude tracks from an album average in one tap.
  Exclusions are per album release, affect albums only, and remain when metadata
  is restored unless the user requests refresh.
- Album details open its detail page and show album rank and score. Preserve
  ranking and album state when metadata is restored.

## Phase 4: artists

- Artists have a separate rank list and detail view. Search results are grouped
  by result type when that phase supports global search.
- Split collaborators and show a song under every credited artist. Count the
  full track score for each artist.
- Artist score is the average of all rated tracks. Require at least three rated
  tracks; hide the score/rank before qualification but show the artist as an
  unranked result.
- Artist details show the artist score and rank, followed by the qualifying
  track sections. Artist ranking is separate from Songs and Albums.

## Deferred but binding UX principles

- The system should offer both automatic and explicit controls where either is
  useful, with gentle prompts rather than repeated interruptions.
- Ask again only after materially stronger evidence.
- Preserve user selections until an explicit submission, and make reversible
  actions easy with Undo where practical.
- Metadata corrections should preserve ranking state and restored metadata
  unless the user explicitly requests a refresh.

## Completeness register and superseded choices

This section preserves smaller interaction decisions that are easy to lose when
implementing from the higher-level requirements.

### Matchup selection detail

- Matchups are mostly within score tiers, with occasional cross-tier checks.
- Once rated songs exist, unrestricted selection ends; before that point,
  unrestricted pairs are valid.
- Existing matchup history is reused when selecting pairs.
- A pair that is skipped is still cooled down. A blocked pair temporarily
  excludes both of its songs when that is needed to avoid an immediate repeat.
- Randomization is only among equally eligible pairs; it must be injectable in
  tests.
- The visible selection feedback is a brief highlight. Do not expose an
  explanation of why a disagreement was prioritized.
- The old Head-to-head sort, score bands, and separate head-to-head ranking
  presentation are retired. Exact one-decimal score ordering is authoritative.

### Gesture and playback detail

- The vertical swipe commit threshold is approximately one-quarter of the
  screen height, with a short-form-feed feel rather than a tiny fling.
- Up chooses the bottom song and down chooses the top song. On horizontal
  layouts, left chooses the right card and right chooses the left card.
- The selected card can use outline, elevation, and scale together; color alone
  never communicates selection.
- Swiping is disabled when it would fight scrolling at compact height or large
  font scale. The labeled button remains available.
- A choice is saved immediately and playback stops immediately. The next
  matchup/rating prompt follows after the brief highlight.
- Autoplay alternates the first song on every matchup. It is armed by tapping
  either song’s Play control, remains armed only for the current foreground
  session, and requires a new tap after backgrounding.
- Preserve matchup identity, first-song order, and playback position through
  rotation. Do not persist a stale active player across lifecycle exit.

### Ranking list and score-editor detail

- The grid/list switch is in the top app bar and the most recently selected
  presentation is remembered.
- Preserve the same top-ranked song/approximate anchor when switching modes,
  rather than resetting to position zero.
- The card hierarchy is artwork first, then title/artist, then score/rank.
- The preview affordance is a muted-preview icon when unavailable, and an
  active preview shows an animated equalizer plus a Pause label. Reduced motion
  uses a static equivalent.
- The score editor supports tapping the ten marks and dragging across them;
  fine adjustment remains possible at tenths. It expands inline or in the
  compact sheet depending on context.
- Save uses the current precise value in the button label (for example,
  “Save 8.7”) and Cancel discards the draft. The card shows only the updated
  score during the brief one-second settle period, then reorders.
- Display scores use standard rounding rules and always retain one decimal
  place; no integer-only 1–10 mode exists.
- Rating a song is a score change, not a reversible clear-to-unrated action.

### Import and first-run detail

- With fewer than two songs, Matchups offers Import playlist as the primary
  action and Load sample pack as the secondary action.
- Playlist import opens the rating queue automatically, starts with recently
  imported songs, and returns to Library import results when closed.
- The queue is one full-screen song at a time, uses the same preview setting as
  Matchups, and advances after Save or Skip for now. Its completion summary
  includes rated and skipped counts and a View Rankings button.
- Songs skipped in the queue are left in Unrated; there is no forced rate-now
  detour after the queue.
- Contextual tips are one-time per interaction surface. Settings can make them
  appear again.

### Suggestion detail

- A suggestion has a precise proposed score, comparison count, score gap, and
  an estimate-stability indicator. Show those as secondary details; the review
  screen is the primary place to act.
- Use exactly the last five comparisons for the stability check, and require
  five rated opponents. A stable estimate moves no more than 0.5 across those
  comparisons.
- If evidence is insufficient, prompt for more anchor songs and let the user
  choose which songs to compare. Do not choose anchors silently.
- Ask again only after materially stronger evidence. The system may choose a
  suggestion automatically only when its confidence crosses the explicit
  stronger-evidence threshold; otherwise keep asking or leave it pending.
- Newer comparisons receive gradually increasing weight, beginning after a
  fixed number of newer comparisons. The change should be noticeable over
  about 30 days. Do not change rankings merely because time passed; wait for
  new matchup evidence.
- Continue prioritizing score-versus-comparison disagreements of at least 0.5,
  including repeats when resolving a high-priority disagreement.
- Review rows expose Accept, Edit, and Later. Accept selected requires the same
  bulk confirmation pattern and displays only the number of ratings being
  applied. Selected values remain frozen until submission; inline edits are
  allowed before submission. Accepted values become normal user ratings and
  remain normally editable afterward.
- Show both a tab badge and inline pending marker. Dismissing Later keeps the
  song pending for future recalculation rather than applying a value.
- Pending suggestions are first in a suggestion queue or review list; within
  the remaining Unrated list, songs are ordered most recently added first.
- A compact suggestion card may appear between matchups with Accept, Edit, and
  Later. It shows the current score and precise estimate, while comparison
  count and gap remain expandable secondary details.
- If a candidate cannot be acted on in the current matchup, leave it pending
  and move to another eligible matchup rather than blocking the session.

### Album and artist detail

- Album and artist lists are separate rank lists. Album details show album rank
  and score; artist details show artist rank and score.
- Album score is hidden until three tracks are rated (not merely one); before
  qualification it is visible only as an unranked result. Its score is the
  simple average of all included rated tracks, formatted to one decimal.
- Album exclusions are one-tap, per release, affect only album averages, and
  preserve all song/ranking state. A restored release keeps its metadata and
  exclusions unless the user explicitly refreshes it.
- Artist details show the summary first and then both qualifying track
  sections. Artist track counts are shown in artist details, not in the main
  artist list. Collaborations count the full song score for every named artist.
- Artist scores/ranks remain hidden until at least three tracks qualify; the
  artist still appears as an unranked result.
- Future global search groups results by Songs, Albums, and Artists. Phase 1
  searches Songs only.

### Restoration and correction detail

- “Start fresh” permanently dismisses that specific tombstone-to-import
  association; it does not delete unrelated ranking history or the tombstone.
- A metadata correction keeps all ranking and album state. Restored metadata
  remains in use until the user explicitly requests a refresh.

### Superseded alternatives (do not implement)

- Do not use relative percentile ranking; keep visible scores absolute.
- Do not auto-score from comparisons, auto-convert suggestions, or hide the
  fact that a suggestion is pending.
- Do not require a score before adding a song. Add first, then offer rating.
- Do not make the Head-to-head view a second sort, and do not expose numeric
  Elo or score-band labels.
- Do not reset the grid/list view on every switch; remember the user’s last
  presentation choice.
- Do not leave all ranking tabs as placeholders. Songs is functional in Phase
  1; only Albums and Artists show Coming soon until their later phases.
- Do not silently disable preview interaction when a preview is unavailable;
  show the muted state and keep the rest of the card actionable.
- Do not use a gesture-only matchup UI, a draw outcome, or a global swipe when
  scrolling/high font scale makes it unsafe.
- Do not show an album score after only one rated track; the final threshold is
  three rated tracks.
- Do not wait for every album track to be rated before showing a score; the
  final rule is three rated tracks, with the average of included rated tracks.
- Do not wipe ranking state when metadata is corrected or a release is
  restored. Only explicit history deletion or an explicit metadata refresh may
  remove corresponding state.
