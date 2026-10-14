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
  `JsonFeed` is the network seam (`HttpJsonFeed` in production, a fake in tests); providers
  take it as a constructor argument, so mapping is testable offline.
  Key-free remote boards are always on: Arbeitnow, Remotive, Jobicy, Himalayas.
  Two sources are credential-gated and stay dormant until the user saves a key in
  Profile → "Job sources": Adzuna (`app_id`/`app_key`, queries ordinary national markets
  per country, the only place visa-sponsored local vacancies live) and Jooble (POST API,
  worldwide aggregation). Both throw `JobProviderException` with an actionable message
  when unconfigured, so the Jobs sync banner shows the source as unavailable rather than
  silently returning nothing. Credentials live in `UserPreferencesRepository` (DataStore)
  and are read into the container through `@Volatile` caches the provider lambdas close over.
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

### Expat intelligence and verified benefits (Stage 7)

- Three numbers are kept deliberately separate and must never be conflated on screen:
  the CV **match score** (`Job.matchScore`), the Madagascar **eligibility verdict**
  (`Job.eligibility`) and the **Expat Benefits Score** (`ExpatBenefitsAssessment.score`).
- `data/expat/ExpatBenefitExtractor.kt` — reads documented benefits from the posting text
  with the proving sentence. Phrase tables per benefit key: `strong` → `CONFIRMED`,
  `hedged` → `NEEDS_VERIFICATION`, `refusals` → `NOT_OFFERED`, and **silence →
  `UNKNOWN`** (never treated as a refusal). Pure function of the text.
- `data/expat/ExpatBenefitsEngine.kt` — scores documented benefits against the user's
  priorities. Only documented benefits earn points; `evidenceCoverage` reports how much of
  the listing was readable and `lowEvidence` (coverage < 40%) marks the score as
  unreliable with an explanation that blames missing information, not the employer.
  `minimumSalaryEur` is scored separately and only once the user sets a minimum.
- `data/model/ExpatBenefits.kt` — `BenefitType`/`JobBenefitTypes`, `BenefitPriority`
  (`NOT_IMPORTANT`=0, `NICE`=1, `IMPORTANT`=2, `ESSENTIAL`=3), `BenefitEvidence`,
  `BenefitScoreComponent`, `ExpatPreferences`, `ExpatBenefitsAssessment`. `ExpatPreferences`
  reads unset keys from each type's `defaultPriority`.
- Preferences persist in `UserPreferencesRepository` (`expat_priorities` as `key=weight`,
  plus min salary/countries/scores/visa gate) and are exposed through `AppViewModel`
  (`expatPreferences`, `setBenefitPriority`, `setRequireVisaSponsorship`).
- `JobsViewModel` builds `JobsUiState.expatAssessments: Map<jobId, assessment>` and the
  expat filters (`minExpatScore`, `visaDocumentedOnly`); `JobsUiState.expatAssessment(job)`
  is the UI accessor.
- UI: `ExpatScoreBadge` (`ui/components/Badges.kt`) shows the score plus evidence coverage;
  `ExpatBenefitsSection`/`BenefitEvidenceRow` (`ui/screens/jobs/JobsScreen.kt`) render the
  per-benefit evidence in the explanation sheet; `ExpatPriorityCard` in `ProfileScreen`
  edits the priorities. Tests: `Stage7PipelineTest`.

### Interview, scam, alerts and auto-apply pipeline (Stage 5)

- `data/model/InterviewCoach.kt` — `InterviewQuestion`, `InterviewPrep`, `InterviewAttempt`,
  `AnswerConfidence`. All `@Serializable` because attempts persist as JSON.
- `data/interview/InterviewCoach.kt` + `InterviewQuestionBank.kt` — builds a prep sheet from
  the posting + CV (opening/tricky/technical/salary/visa questions) and scores attempts.
  `InterviewPrepStore.kt` persists attempts to app-private storage.
- `data/model/ScamReport.kt` + `data/scam/ScamDetector.kt` — rule-based scan producing a
  `ScamRisk` verdict, weighted findings and "good signs". Rules are pure and take a `clock`.
- `data/model/JobAlert.kt` + `data/alerts/` — `JobAlertRule`, `JobAlert`, `JobAlertsState`;
  `AlertMatcher` decides which postings fire a rule and `JobAlertsCenter` owns the rule set
  plus the detected-alert list. `JobAlertStore` persists both.
- `data/model/AutoApply.kt` + `data/autoapply/` — `AutoApplyState`, `AutoApplyItem`,
  `AutoApplyStatus`, `AutoApplyCenter`/`AutoApplyEngine`. The contract is draft-only:
  strong matches get a prepared draft, nothing is ever sent automatically.
- `viewmodel/Stage5ViewModels.kt` — `InterviewViewModel`, `ScamShieldViewModel`,
  `JobAlertsViewModel`, `AutoApplyViewModel` and their factories.
- `ui/screens/{interview,scam,alerts,autoapply}/` — thin stateful wrapper + `internal`
  stateless `*Content`, same pattern as Stage 4. `InterviewContent`/`ScamShieldContent`
  take `InterviewUiState`/`ScamUiState`; the alerts/auto-apply content take their state
  model directly.
- Routes: `SecondaryRoutes.INTERVIEW` (`interview/{jobId}`), `SCAM` (`scam/{jobId}`),
  `ALERTS` (`alerts`), `AUTO_APPLY` (`autoapply`). The AI tab's capability cards open all
  four. Note the interview/scam cards sit near the bottom of the AI list, so tests that tap
  them must scroll the list to the end first — the floating nav bar overlays the list and
  otherwise swallows the tap.

### Live search validation and application links (Stage 8)

- `data/model/ProviderDiagnostics.kt` — `ProviderStatus` (finer-grained than `SourceState`:
  `SUCCESS_EMPTY` vs `SUCCESS_WITH_RESULTS` vs `PARTIAL_SUCCESS`, and `NOT_CONFIGURED`/
  `DISABLED`/`WAITING_FOR_NETWORK` are **dormant, not failures**), `ProviderDiagnostic`
  (per-source counts, timings, classified `errorCategory`, `message`), `ProviderSettings`
  (`disabledIds` + `configuredCredentialIds`), and `ProviderDiagnostics` (the whole feed's
  snapshot with `successCount`/`failureCount`/`totalValidJobs`/`showingCachedOnly`).
  Every field is only ever set from a real attempt — a provider never reports success
  before it has actually been queried.
- `data/repository/JobRepository.kt` — adds `diagnostics: Flow<ProviderDiagnostics>` and
  `suspend runLiveValidation()`. `LiveJobRepository` records one diagnostic per source
  (including dormant rows so the screen has no gaps), keeps the last good list when a run
  produces nothing usable, and guards concurrent runs with a `Mutex.tryLock()` (a second
  call while one is in flight is ignored, not queued).
- `viewmodel/Stage8ViewModels.kt` — `SourceDiagnosticsViewModel` + factory; drives the
  validation screen and turns a finished run into a plain-language note.
- `ui/screens/diagnostics/LiveSearchValidationScreen.kt` — `LiveSearchValidationScreen`
  wrapper + `internal LiveSearchValidationContent(state, ...)`. `SourceStatusBadge` and
  `providerDetail()` are shared with Profile. Route: `SecondaryRoutes.DIAGNOSTICS`
  (`"diagnostics"`), pushed on top of the Profile tab and hiding the bottom bar.
- `ProfileScreen.JobSourcesCard` — now takes `diagnostics` and shows each source's real
  last-run outcome (status badge + message) with an on/off `Switch`, plus "Run live
  validation" / "Open diagnostics" actions. Credential entry (Adzuna appId+appKey, Jooble
  key) is behind "Add optional sources". Toggles persist through
  `AppViewModel.setSourceEnabled` → `UserPreferencesRepository.setSourceEnabled`.
- `data/apply/ApplicationUrlInspector.kt` — classifies a posting URL (employer / job board
  / government portal / unverified / invalid) and lists concrete warnings (http, free
  domains, punycode, raw IP, over-long links). Pure function of the URL + source name.
- `ui/screens/jobs/ApplyLinkSheet.kt` — `ApplyLinkSheet` + the shared
  `applicationUrlInspector`. **Apply never opens a link silently**: both Jobs and Home
  route an Apply tap through this sheet, which states the host, the destination kind and
  any warnings before the browser opens. The URL is only launched via
  `Context.openExternally` in `AiJobFinderRoot.kt`, which accepts http/https only and
  swallows a missing-handler failure instead of crashing.
- Tests: `JobSourcesScreenTest` drives `JobSourcesCard` with recorded diagnostics (not a
  hard-coded "always on" list) at `w320dp-h640dp`, so the credential fields and the
  per-source status must both fit a narrow phone.

### Real-world retrieval verification (Stage 9)

Stage 9 asked whether the app retrieves **genuine** overseas vacancies for the candidate
and whether it can tell **verified** employment benefits apart from marketing prose. A
throwaway live probe (hitting the real provider APIs and running the real engines over the
results) answered both, and two scoring defects it exposed were fixed.

- **Live retrieval works for the key-free sources.** Arbeitnow (HTTP 200), Remotive (200),
  Jobicy (200) and Himalayas (200) all returned real postings and mapped them cleanly
  (title/company/location/url 100% populated). Adzuna reports `NOT_CONFIGURED` without an
  app id/key, and Jooble is blocked by a Cloudflare bot challenge from a datacenter IP —
  both are surfaced as actionable diagnostics, not silent failures.
- **The remote boards ignore search terms.** Remotive (`search`/`category`), Jobicy
  (`industry`/`tag`) and Himalayas (`q`/`search`) all return the same generic
  newest-first feed regardless of the parameter. Only Adzuna/Jooble do server-side keyword
  search, and both need credentials. So with no credentials the app cannot *target*
  maintenance-technician roles; it can only fetch generic feeds and rank them locally. This
  is a source limitation, documented here rather than worked around with fabricated data.
- **`MatchingEngine` — a skill overlap is now gated by role alignment.** A live
  "Product Design Manager" posting that mentioned "CMMS" once was scored 66% for a
  maintenance technician: because it was the only skill detected, it awarded the *full*
  skill weight even though the role signal was 0. Skill points are now multiplied by a
  gate (`titleRatio >= 0.35` → full, `>= 0.2` → half, else → 0.2), and the Skills reason
  says so ("...it is not a role you do; the overlap counts for little"). The same posting
  now scores 41% and no unrelated role reaches the 60% "matching" threshold. Titles that
  are genuinely close (ratio ≥ 0.35) are unaffected, so a real match is not penalised.
- **`ExpatBenefitExtractor` — whole-word matching + negation awareness.** The live audit
  found two ways the extractor misled the user:
  1. *False positives from incidental words.* "family-owned company", "transportation
     disruptions", "insurance and g suite" were being read as family-support / transport /
     health-insurance benefits, because hedged phrases were matched as bare substrings
     ("family", "transport", "insurance"). Phrases are now matched on whole words
     (`\b…\b`), and the bare-noun hedged entries were removed — a bare noun is not
     evidence of a benefit. Documented benefits ("visa sponsorship", "relocation package")
     still resolve; only the noise is gone.
  2. *Refusals read as offers.* "we **cannot** support candidates requiring visa
     sponsorship" and "visa sponsorship ... **are not available**" contain a strong phrase,
     so they were classified `CONFIRMED`. A sentence that contains a strong phrase **and**
     an unambiguous negation (`cannot`, `do not`, `will not`, `unable to`, `not available`,
     …) is now recorded as `NOT_OFFERED`. The negation list deliberately excludes the
     word "no" on its own so that "no cost"/"no fee" offers are not misread as refusals.
- Tests: `Stage3PipelineTest.anUnrelatedRoleDoesNotScoreHighOnOneSharedTool`,
  `Stage7PipelineTest.incidentalProseDoesNotInventBenefits`,
  `Stage7PipelineTest.aNegatedStrongPhraseIsARefusalNotAnOffer`, and
  `Stage7PipelineTest.documentedBenefitsStillResolveWithWholeWordMatching` pin the exact
  live-observed strings so these regressions cannot return.

## Conventions

- Every screen composable reads spacing from `LocalSpacing.current` and applies
  `navBarClearance` as bottom content padding so the floating nav bar never covers content.
- Dark mode is a hand-tuned palette in `Theme.kt`, not an inversion. Check both modes.
- No hardcoded user-visible strings worth extracting yet; copy lives inline in Stage 1.
