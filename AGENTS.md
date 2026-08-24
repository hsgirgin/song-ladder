# Song Ladder Agent Rules

These instructions apply to the entire repository. Treat every rule below as mandatory unless the user explicitly directs otherwise.

## Before changing anything

- Read `README.md`, the relevant Gradle files, the implementation being changed, and its existing tests before editing.
- Run `git status --short`. Preserve all user changes and unrelated work. Never discard, overwrite, reformat, or include unrelated changes.
- Trace behavior across UI, ViewModel, repository, database/network client, and tests before changing a cross-layer feature. Do not infer contracts from one file alone.
- Keep the patch as small as correctness permits. Do not perform opportunistic refactors, dependency upgrades, package moves, or formatting sweeps.
- Do not introduce a library, Gradle plugin, external service, permission, or secret without explicit user approval.
- Never run destructive Git or filesystem commands, force-push, rewrite history, or commit unless the user explicitly requests it.

## Graphify workflow

- This repo does not check in a maintained `graphify-out/graph.json`; it's a small single-module app where full-repo exploration is already cheap, so a checked-in graph isn't worth the staleness risk and upkeep. `graphify-out/` is gitignored — treat any graph you build locally as ephemeral, session-scoped scaffolding, not a repo artifact.
- If you build a local graph, query it before broad repository exploration to find likely files and relationships, then verify conclusions against the source code and documentation. Treat `graph.json`, `GRAPH_REPORT.md`, and `graph.html` as derived navigation artifacts, not sources of truth — never edit them manually, and never commit them.
- If the graph is missing or stale, rebuild it rather than infer its contents; do not assume one exists.

## Project architecture

- This is a single-module native Android app using Kotlin, Java 17, Jetpack Compose, Material 3, Room, coroutines/Flow, Kotlin serialization, OkHttp, and Coil.
- Preserve the existing dependency direction: `ui` -> `domain` interfaces/models -> `data` implementations. UI code must not access DAOs, Room entities, OkHttp, `MediaPlayer`, or concrete repositories directly.
- Construct app-scoped dependencies in `AppContainer`. Keep external boundaries behind interfaces and constructor-inject collaborators so unit tests can use fakes.
- Keep feature UI and ViewModels under their feature package. Put reusable UI in `ui/components`, domain-only behavior in `domain`, and Android/external-system implementations in `data`.
- Use structured concurrency. ViewModel work belongs in `viewModelScope`; disk and network work must not block the main thread. Never use `GlobalScope` or create an unmanaged application-lifetime coroutine.
- Represent recoverable boundary failures explicitly with the repository/client `Result` contracts. Never silently swallow an exception or report success before persistence succeeds.

## Compose and UI

- Use Material 3 APIs only. Use `MaterialTheme` color, typography, and shape roles; do not add hardcoded visual values when an existing theme token or named component constant applies.
- Follow unidirectional data flow: ViewModels expose immutable `StateFlow<UiState>`, screens collect it with `collectAsStateWithLifecycle()`, state flows down, and callbacks/events flow up.
- Keep reusable composables stateless. Do not pass a ViewModel or `NavController` below a screen boundary; pass immutable data and callbacks. Every layout-emitting reusable composable accepts `modifier: Modifier = Modifier` and applies it to its root.
- Never perform navigation, network/database work, playback operations, or coroutine launches directly during composition. Use the correctly keyed `LaunchedEffect`, `DisposableEffect`, or event callback, and always perform required cleanup.
- Apply `Scaffold` content padding. Give lazy-list items stable, unique keys. Move filtering, sorting, mapping, and other non-trivial work out of hot composable bodies.
- User input or selection that should survive configuration changes uses `rememberSaveable`; durable/business state belongs in a ViewModel or repository.
- New or changed user-visible copy must be localized through Android resources at the UI boundary. Do not expand the existing hardcoded-copy debt.
- Preserve accessibility: meaningful images and icons need localized descriptions; decorative media uses `contentDescription = null`; custom controls need an appropriate role/action and at least a 48dp target. Do not communicate state by color alone. Check light/dark themes and large font scaling for substantial UI changes.
- Add focused previews or isolated Compose UI tests for new non-trivial UI when feasible. Do not connect previews or UI tests to real ViewModels, databases, media, or network services.

## Data and behavioral invariants

- Elo rating changes, per-song wins/losses/skips, and aggregate match/skip counters are one logical operation. Keep related reads and writes in a single Room transaction and add regression tests for any change to this flow.
- `BASE_RATING`, `K_FACTOR`, rating rounding, matchup selection, and repeat-avoidance are product behavior. Do not alter them accidentally. If requirements change them, make randomness and time controllable where needed and test exact outcomes and boundary cases.
- Any Room schema change requires an intentional database version bump, a non-destructive migration, and migration coverage. Never add `fallbackToDestructiveMigration`, clear user data to make a migration pass, or change stored column meaning in place.
- JSON backup is a compatibility surface. Preserve existing field names and defaults, tolerate unknown fields, normalize unknown source types to `IMPORT`, and keep old valid backups readable. Add round-trip and backward-compatibility tests when changing exported models or mappers.
- Parse and validate an imported backup before opening the replacement transaction. A malformed or unreadable import must leave the current library and stats untouched. Whole-library replacement/reset must remain behind explicit user confirmation in the UI.
- Preserve case-insensitive, trimmed title-and-artist deduplication for track imports unless the user explicitly changes that product rule. Validate blank and duplicate inputs and keep batch accounting consistent with actual inserts.
- Never expose Room entities outside the data layer. Keep domain/entity/export mappings explicit and test defaults, unknown enum values, and missing related rows.

## Network and preview playback

- Reuse the shared, bounded-timeout `OkHttpClient`; do not create ad hoc production clients. Use HTTPS and validate external URLs and response data before persisting or playing them.
- Keep endpoint/base URL collaborators injectable when required for deterministic tests. Network tests use `MockWebServer` or fakes and must never call iTunes, Deezer, YouTube Music, or another live service.
- Treat external response formats as untrusted. Handle malformed bodies, missing fields, non-success responses, empty matches, ambiguity, cancellation, and transient failure without crashing or corrupting state.
- Preview resolution must reject incorrect track/artist matches and must not permanently cache transient failures as unavailable.
- Playback must release `MediaPlayer`, abandon audio focus, unregister the noisy-audio receiver, and clear stale state on completion, error, replacement, stop, and lifecycle exit. Preserve generation/cancellation guards so stale prefetch results cannot overwrite the current matchup.

## Testing and verification

- Every behavior change requires tests in the same patch. Test the observable contract, not private implementation details.
- Use JUnit 4. Use `kotlinx-coroutines-test` with a controlled dispatcher for coroutine/ViewModel behavior. Avoid real delays, clocks, randomness, Android services, files, and network calls in local unit tests.
- Cover relevant success, failure, empty/invalid input, duplicate, cancellation/stale-result, and persistence/serialization compatibility paths. A bug fix must include a regression test that fails without the fix.
- Run the narrowest relevant tests while developing. Before handoff, run from the repository root:

  ```bash
  ./gradlew testDebugUnitTest lintDebug
  ```

- For manifest, resource, dependency, Room/KSP, or broad integration changes, also run:

  ```bash
  ./gradlew assembleDebug
  ```

- Resolve new warnings and failures caused by the patch. Do not weaken, delete, ignore, or broadly suppress a test/lint rule to obtain a green build; a narrow suppression requires a nearby explanation.
- If tooling or the environment prevents a check, report the exact command, failure, and which verification remains outstanding. Never claim a check passed when it did not run or did not complete.

## Completion standard

- Review the final diff and `git status --short`. Confirm only intended files changed, no secrets or generated artifacts are tracked, and documentation matches behavior.
- Summarize changed behavior, list tests/checks run with results, and call out residual risks or unverified items. Do not conceal limitations behind a generic "tests not run" statement.
