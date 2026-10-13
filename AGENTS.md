# AI Global Job Finder — working notes

Android app (`com.aiglobal.jobfinder`) that helps a Madagascar-based job seeker find and
apply for real international roles. Jetpack Compose + Material 3, Kotlin DSL build.

## Environment

- JDK 21: `/usr/lib/jvm/java-21-openjdk-amd64` (`JAVA_HOME`)
- Android SDK: `/home/openhands/android-sdk` (`ANDROID_HOME`); compileSdk/targetSdk 34, minSdk 26
- Gradle 8.7 via the wrapper (`./gradlew`). `local.properties` points at the SDK.
- No KVM in this container, so **do not** plan on a booted emulator. Verify UI headlessly instead.

## Commands

```bash
./gradlew :app:assembleDebug          # build the APK
./gradlew :app:compileDebugKotlin     # fast compile-only check
./gradlew :app:testDebugUnitTest      # headless UI verification (Robolectric + Compose)
```

Test output PNGs land in `app/build/screenshots/` (git-ignored). Read them to check layout.

## Headless UI verification

There is no emulator, so screens are verified two ways, both on the JVM:

1. `ScreenshotTest` — renders each screen at `w411dp-h891dp-xhdpi` through a real Activity
   window and writes a PNG per screen in light and dark. Rendering goes through
   `view.draw(Canvas)`, **not** `captureToImage()` (that path relies on surface redraw and
   times out under Robolectric).
2. `NavigationTest` — launches `MainActivity` and clicks through every bottom-nav tab.

Notes that cost time to rediscover:

- The Compose rule owns the frame clock. Set `mainClock.autoAdvance = false` and advance it
  by hand, otherwise the infinite shimmer/spin animations mean the clock never idles and
  every call times out. Interleave `shadowOf(Looper.getMainLooper()).idle()` so frames flush.
- One `setContent` per test only. To capture both themes, flip a `mutableStateOf(ThemeMode)`
  and re-capture from the same tree.
- Bottom-nav items expose `contentDescription` (the icon), and the label `Text` only exists
  on the selected item. Match nav targets with `onNodeWithContentDescription`.
- `ScreenshotTest` and `NavigationTest` take `autoRun`/repositories with zero delay so
  animation and loading states settle deterministically.

## Architecture

- `AiJobFinderApp` (Application) owns `AppContainer`, which holds `JobRepository` and
  `UserPreferencesRepository`. `MainActivity` reads the container and builds view models.
- `data/model` — `Job`, `UserProfile`, application records, enums (eligibility, visa, scam risk).
- `data/repository/JobRepository.kt` — the `JobRepository` interface plus `SampleJobRepository`.
  `SampleData.kt` holds the seeded jobs/profile/stats. `refreshDelayMillis` and `clock` are
  injectable so tests can make it instant.
- `data/prefs/UserPreferencesRepository.kt` — DataStore-backed theme preference.
- `ui/theme` — `Color`, `ExtendedColors`, `Type` (Plus Jakarta Sans), `Shape`, `Spacing`,
  `Theme` (`AiJobFinderTheme` + `ThemeMode`), `Brush`. Semantic colours beyond Material 3
  live in `extendedColors` (success/warning/danger/info containers).
- `ui/components` — `JobCard` (the centrepiece), `Badges`, `Buttons`, `Skeletons`, `Common`.
  `isDarkTheme()` lives in `ui/components/Common.kt`; screens import it from there.
- Screens keep a thin `XScreen(viewModel)` wrapper and an `internal XContent(state, ...)`
  composable. **Tests target the `Content` composables** — keep them state-driven and free of
  ViewModel references.

### CV pipeline (Stage 2)

- `data/cv/` — `CvFileStore` (copies a picked `Uri` into app-private storage under a
  UUID-derived name), `CvParserRegistry` (PDF via pdfbox-android, DOC/DOCX, TXT),
  `HeuristicCvAnalyzer`, `CvLexicon`, `CvProfileMerger`. `CvFileType` lives in `data/model`.
- `data/repository/CvRepository.kt` — owns the flow: import → parse → analyse → persist.
  `record` is a `StateFlow<CvRecord?>`; `completeness()` derives profile completeness.
  All disk/parse work runs on `Dispatchers.IO`/`Default` behind a write mutex.
- `viewmodel/CvViewModel.kt` — `CvUiState` + `CvViewModelFactory`. Import runs then
  auto-triggers `analyze()`.
- `ui/screens/cv/CvScreen.kt` — single `LazyColumn`, `onBack` supplied by the caller.
  Because items are lazy, tests must scroll the list (`performScrollToNode`), not the node.
- `ui/AiJobFinderRoot.kt` — `SecondaryRoutes.CV` is pushed on top of the AI tab and hides
  the bottom bar (`showBottomBar` only true for `Destination` routes). The `OpenDocument`
  picker uses `CV_MIME_TYPES`.

### Live job pipeline (Stage 3)

- `data/jobs/provider/` — `JobProvider` (stateless, network-bound) + `RawJob` (a posting
  exactly as the source sent it; absent fields stay null). Providers only translate a feed;
  they never score or enrich. `TextParsing.kt` holds `IsoDates`, `HtmlText` and `SalaryParser`.
- `data/jobs/engine/` — `MatchingEngine` (six weighted signals → score + one `MatchReason`
  per signal) and `EligibilityEngine` (priority rules → verdict + reasons tagged
  `official = true` only when the posting actually states the fact). `SkillLexicon` is the
  whole-word skill vocabulary. These are pure functions of their inputs — test them directly.
- `data/jobs/JobMapper.kt` — turns `RawJob` + `CandidateProfile` into the `Job` the UI renders.
- `data/geo/` — `CountryCatalog` (free-text location → canonical country + flag) and
  `CurrencyConverter` (coarse EUR-per-unit table; returns null for unknown currency so the
  card keeps showing the raw amount rather than inventing a number).
- `data/repository/LiveJobRepository.kt` — queries providers concurrently, caches to disk,
  re-scores from the cached raw postings whenever the profile flow changes. `syncState`
  reports per-source success/error so "0 jobs" and "source down" are distinguishable.
- `viewmodel/ViewModels.kt` — `JobsUiState` owns *all* filtering and sorting; `visibleJobs`
  is a pure derivation over `jobs` + the filter fields. Tests in `JobsFilterTest` exercise it
  directly, so filter/sort regressions fail there instead of only in the UI.

## Test seam

`AppContainer` is swappable: `AiJobFinderApp.container` is a `var` and `NavigationTest`
installs a deterministic container backed by `SampleJobRepository` before launching
`MainActivity`. This keeps `NavigationTest` off the live network without faking production
behaviour. Do not remove the seam.

### Application pipeline (Stage 4)

- `data/model/ApplicationTracking.kt` — `ApplicationPipeline.steps` is the happy path
  (SAVED→PREPARING→APPLIED→INTERVIEW→OFFER). `stepIndex` is `-1` for the terminal states
  (REJECTED, CLOSED), so the stepper never implies a rejection is "further along". Display
  labels live here too (`displayLabel`, `shortLabel`, `stageHint`) — use them, do not inline.
- `data/applications/ApplicationCenter.kt` — owns every rule that happens *after* a job is
  found: status changes, the append-only timeline, reminders (derived from records, never
  stored, so a stale reminder is impossible) and the next-action sentence. It takes
  `candidateProfile` as a flow so seeded letters are grounded in the CV. Persistence is
  `ApplicationStore`; the center owns rules, not storage.
- `data/coverletter/CoverLetterGenerator.kt` — writes a letter from the CV + posting only.
  Every letter carries `usedFacts`/`missingFacts` so the UI can show the letter is grounded
  rather than invented. `LetterLanguage.forCountry` picks French for France/Canada etc.
- `data/expat/ExpatBenefitsCatalog.kt` — per-country settlement guide. Every `BenefitItem`
  requires `sourceName` + `sourceUrl`; keep it that way so claims stay verifiable.
- `viewmodel/Stage4ViewModels.kt` — `ApplicationDetailViewModel`, `CoverLetterViewModel`,
  `ExpatGuideViewModel` and their factories. Note `ApplicationCenter` is constructed with
  `candidateProfile` in `AiJobFinderApp.kt`.
- `ui/screens/applicationdetail|coverletter|expat/` — each screen has a thin stateful
  wrapper plus an `internal` stateless `*Content` composable. Screenshot and layout tests
  target the `*Content` variants directly, so they need no live pipeline.
- Routes: `SecondaryRoutes` in `ui/AiJobFinderRoot.kt` (`application/{id}`, `coverletter/{jobId}`,
  `expat/{country}`). They are pushed on top of a tab and hide the bottom bar; the country
  argument is URI-encoded because it can contain spaces.

## Conventions

- Every screen composable reads spacing from `LocalSpacing.current` and applies
  `navBarClearance` as bottom content padding so the floating nav bar never covers content.
- Dark mode is a hand-tuned palette in `Theme.kt`, not an inversion. Check both modes.
- No hardcoded user-visible strings worth extracting yet; copy lives inline in Stage 1.
