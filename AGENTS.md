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

### Adzuna configuration and secret handling (Stage 10)

Stage 10 connected Adzuna for real. The provider, the DataStore keys and the Settings card
already existed; the work was making the credentials safe to use and proving the source
returns genuine overseas maintenance vacancies.

- **Adzuna needs credentials, and they must never leak.** Adzuna requires `app_id` **and**
  `app_key` as query parameters, so the key rides the request URL. `HttpJsonClient` used to
  throw `IOException("HTTP $status for $url")`, which put the key into the exception message
  (and would put it in any future log). `HttpJsonClient.redactUrl` now masks any query
  parameter whose name looks secret (`key`, `token`, `secret`, `password`, `auth`,
  `credential`, `app_id`) before the URL is used in a message. `app_id` is masked too: it is
  semi-public (it also appears in `redirect_url`'s `utm_source`) but there is no reason to
  echo it.
- **The Settings UI no longer renders a stored key.** `CredentialField` shows a saved value
  as `Saved ••••••••` with a "Replace" action and an empty input; the field is only populated
  when the user chooses to replace it, and it is a `PasswordVisualTransformation` field. A
  key pasted into Settings therefore never reappears on screen or in a screenshot. Credentials
  live in DataStore (`adzuna_app_id`, `adzuna_app_key`) and are never cleared by a refresh.
- **Live verification.** With the user's real credentials supplied only as environment
  variables (never written to the repo), the app's own `AdzunaProvider` fetched **243 unique
  postings** across eight maintenance keywords and six markets (GB/CA/DE/FR/NL/AU). 69 scored
  ≥55% for the candidate; the top hits are real overseas roles (a Venlo maintenance
  technician at 77%, PPG Mechanical Maintenance in Australia at 71%, Amentum in Canada at
  67%). Adzuna is the only configured source that does **server-side keyword search**, which
  is why it is the one that can target the candidate's trade.
- **Benefits stayed conservative.** Of 243 postings, only 7 had a `CONFIRMED` expat benefit,
  and the two marked `VISA_SPONSORED` are the same two whose text says "457 Sponsorship
  Available". The evidence sentences were printed and checked; the "Transport / allowance"
  finding traces to "Company Car", which is a real documented benefit.
- **Failure behaviour.** `ProviderErrorClassifier` now recognises Adzuna's own `AUTH_FAIL`
  code as `AUTHENTICATION` (previously the word "auth" alone did not match). A concrete HTTP
  status is classified before the generic `cause is IOException` branch, so an HTTP 503 is
  reported as `HTTP_ERROR` ("server rejected") rather than `NETWORK` ("check your internet").
  A failed market does not discard the markets that answered, and a provider that fails
  entirely is reported as a source diagnostic — it never surfaces as a job.
- Tests: `Stage10AdzunaTest` (17 tests) covers credential validation, the key travelling in
  the URL, redaction, all five error categories, empty/malformed/skipped results, per-market
  isolation and salary/remote mapping. `JobSourcesScreenTest` asserts the stored key is never
  rendered and that "Replace" appears. Suite: 142 tests, 0 failures.

### Application eligibility and expat shortlist (Stage 11)

Stage 11 answers a different question from the match score: not "does my CV fit?" but "can I
actually apply from Madagascar, and what is standing in the way?".

- **Readiness is a band, not a number.** `ApplicationReadiness` (PRIORITY / READY /
  PREPARE_FIRST / NEEDS_REVIEW / BLOCKED) is derived by `ShortlistEngine` from four inputs:
  the match score, the official route (a documented visa/relocation path), the candidate's
  profile completeness, and the documented benefits. `ReadinessBlocker` names what holds a
  posting back and how many points it costs, so the UI can explain a lower band rather than
  just assert it.
- **`ShortlistEntry.hasOfficialRoute` matches labels case-insensitively.** The engine accepts
  an official route when a reason label contains a known phrase (`visa sponsor`, `work
  permit`, `relocation`, …) ignoring case, so a real posting that says "Visa Sponsorship
  offered" is recognised. The entry-level helper must use the same rule; a case-sensitive
  `contains` there silently disagreed with the engine and dropped roles out of the top band.
- **Shortlist layout on a narrow phone.** The screen header ("Expat shortlist") is rendered
  outside the scrolling list, so layout tests must assert it directly rather than trying to
  scroll to it. Everything below — the readiness band, the blocker summary, the job card and
  both actions — is inside the `LazyColumn` and reachable with `performScrollToNode`.
- **Entry points.** Home carries a premium `ShortlistEntryCard` banner and the AI screen has a
  capability card; both navigate to the shortlist route. `SecondaryRoutes` also registers
  EXPAT, INTERVIEW, SCAM, ALERTS, AUTO_APPLY and DIAGNOSTICS.
- Tests: `Stage11PipelineTest` covers the band/blocker maths and `ShortlistState` counts;
  `Stage11ScreenTest` verifies the ready role, its actions and the empty state render and
  scroll at 320×640dp; `ScreenshotTest.shortlist` captures light and dark. Suite: 159 tests,
  0 failures.

### Finishing navigation and removing invented content (Stage 12)

Stage 12 audited every navigation entry and removed the last invented content so the app can
be trusted as a real job-application tool.

- **Dead affordances are wired, not hidden.** Home's notification bell was a no-op
  (`.clickable { }`); it now opens the alerts route and its badge lights when there is any
  active application or interview. The Home job cards' bookmark now calls the real
  `toggleSave`. The shortlist's "Apply" no longer silently navigates to the cover letter —
  it opens the same `ApplyLinkSheet` the Jobs and Home screens use, so the host and any link
  warnings are shown before the browser opens.
- **No invented identity or advice.** Empty-state fallbacks must not fabricate a user:
  the Home greeting shows `Hello, <first name>` only from the stored profile and otherwise
  `Welcome back 👋` (never a hard-coded "Jonathan"). `ProfileScreen.profileStrengthHint`
  derives the next gap from the profile's own empty sections instead of asserting an
  invented "German A2 certificate".
- **Dashboard numbers are derived, never canned.** `HomeUiState.totalJobs` carries the live
  feed size, so `AiInsightCard` reports "scanned N listings in your last refresh" and the
  count of Madagascar-eligible top matches, replacing the fixed "1,240 listings today".
  `AiScreen.AnalysisCard` takes `hasCv`/`skillCount`/`strongMatchCount` from `AiViewModel`
  rather than the canned "8 strong matches found" / "12 skills extracted".
- **Cards that promised unbuilt features were removed.** The AI workspace dropped the
  "Cover letters" (which actually opened CV analysis) and "Skills gap plan" capability cards
  — nothing behind them. "CV adaptation" is now wired to `SecondaryRoutes.CV_ADAPTATION`
  (`"cvadaptation"`), which was registered in `AiJobFinderRoot` but had no entry point.
- **`JobsViewModel.toggleSave(jobId)`** flips a local `savedJobIds` set so the bookmark
  reacts instantly, then persists through `repository.toggleSaved`; `JobsUiState.isSaved`
  unifies the local and feed flags so the saved-only filter agrees with the icon.
- **`AutoApplyCenter` honours the saved threshold.** It builds a fresh
  `AutoApplyEngine(threshold = store.threshold.value)` per run instead of a default-threshold
  engine, so the queue matches the number the settings screen shows.
- Tests: the full suite (`./gradlew :app:testDebugUnitTest`) is green at 162 tests, 0
  failures, covering navigation, both themes and narrow layouts.

### Production data hygiene and demo mode (Stage 13)

The app is a real tool for one candidate, so a fresh install must never show an invented
candidate or invented applications. Sample data now lives behind an explicit demo switch.

- **Demo mode is opt-in and persisted.** `UserPreferencesRepository.demoMode`
  (`demo_mode_enabled`, default `false`) is read into `LiveJobRepository.demoMode` and
  `ApplicationCenter.demoMode`. Nothing sample-shaped reaches the screen while it is off.
- **The dashboard profile is derived, never seeded.** `LiveJobRepository` no longer sets
  `_profile = SampleData.profile(...)`. Its `profile` flow combines the imported `CvRecord`
  (`cvRepository.record`) with the auto-apply flag through `buildUserProfile(record, auto)`
  (`data/model/UserProfile.kt`). With no CV the profile is empty and `ProfileScreen` shows
  "Your profile" / "Import a CV to build your profile" instead of a fabricated name.
- **Applications start empty.** `ApplicationCenter` only calls `seedFromSample()` when
  `demoMode` emits `true` on a store with no persisted document; it collects the flow
  (`store.load()` first) rather than reading it once, so toggling demo mode later still
  seeds. In production the Applications tab is empty until the user creates a record.
- **Posting dates are never invented.** `Job.postedAtEpochMillis` is nullable and
  `JobMapper` keeps it null when the source omits a date; `Job.retrievedAtEpochMillis`
  records when the app fetched it. `postedAgeLabel(...)` (`ui/components/Formatters.kt`)
  renders a missing date as "Date not stated" rather than a fake "just now".
- **`JobRepository.setDemoMode(Boolean)`** is the seam; the live repo delegates to the
  persisted setter, `SampleJobRepository` is a no-op (it is already a fixture). Switching
  demo off triggers a live refresh so the user is not left with an empty list.
- **Test seam:** `AppContainer.seedsSampleData` (default `false`) feeds `ApplicationCenter`;
  `NavigationTest.TestApp` overrides it to `true` so its fixture container keeps the
  seeded pipeline. `ScreenshotTest` deliberately uses the production default and renders
  screens from explicit fixtures. `ProductionDataTest` pins the invariant.
- **One demo flow for the whole container.** `AppContainer.demoMode` combines the persisted
  choice with `seedsSampleData`, and `jobRepository`, `applicationCenter` and
  `jobAlertsCenter` all read it, so jobs, applications and saved searches can never disagree
  about whether fixtures are being served.
- **The auto-apply choice and the bookmarks survive a restart.** `UserPreferencesRepository`
  gained `auto_apply_enabled` (bool) and `saved_job_ids` (string set); `LiveJobRepository`
  collects both in `init` and writes them back from `setAutoApply`/`toggleSaved`. Only the
  ids are stored — the posting itself is re-fetched and re-scored from the feed.
- **Saved searches are the user's own.** `JobAlertsCenter` takes a `demoMode` flow and only
  seeds the two curated examples on a fresh install *in demo mode*; outside demo mode the
  alert list starts empty.
- **`UserPreferencesRepository` keeps a DataStore seam.** Its `dataStore` parameter defaults
  to the process-wide `Context.dataStore` delegate. Keep that default: the delegate caches a
  single DataStore per Context, and constructing one with `PreferenceDataStoreFactory` on
  every build opens a second store on the same file (`IllegalStateException: multiple
  DataStores active`). Tests inject a temporary-file store instead.
- **Release R8 needs the pdfbox `-dontwarn`.** `pdfbox-android` references an optional
  `com.gemalto.jp2.JP2Decoder`; `proguard-rules.pro` carries the AGP-generated
  `-dontwarn` so `assembleRelease` links.
- Tests: `Stage13PersistenceTest` writes with one repository and reads back with a second
  over the same temporary DataStore (a real restart), covering auto-apply, bookmark and
  un-bookmark persistence plus demo-gated alert seeding. Suite: 170 tests, 0 failures.

### Real-device QA and live search validation (Stage 14)

Stage 14 separates what the suite actually proves from what it only appears to prove, and
validates live retrieval against the real feeds.

- **No physical-device or emulator QA is possible in this environment.** There is no
  `/dev/kvm`, no `$ANDROID_HOME/emulator` binary, no installed system images and no AVDs, and
  `adb devices` lists nothing. Every UI assertion in the suite is therefore Robolectric
  (JVM) or Compose UI-test based, not on-device. Do not describe a green suite as
  "device-verified" — it is not.
- **Live retrieval probe:** `Stage14LiveProbeTest` hits the real providers and maps every
  posting through `JobMapper`/`MatchingEngine`/`EligibilityEngine`. It is skipped unless
  `LIVE_PROBE=1` is set, so the normal suite stays offline. Robolectric is required even
  though it has no UI, because the providers parse with `org.json` (a stub on a plain JVM).
  Run it with `LIVE_PROBE=1 ./gradlew :app:testDebugUnitTest --tests '*Stage14LiveProbeTest' --rerun-tasks -i`.
- **What live retrieval found (one run):** the four key-free sources returned 220 raw / 145
  unique postings (arbeitnow 60, remotive 90, jobicy 50, himalayas 20); Adzuna reported
  `NOT_CONFIGURED` because no key is saved. **No posting reached the 60% "matching" bar** and
  only **one** of 145 titles was a maintenance-family role — the top-scored items were social
  workers and account executives. This is the same limitation Stage 9 recorded: these boards
  ignore server-side keywords, so the feed is a broad remote sample, not a maintenance feed.
  A targeted national source (Adzuna, or Jooble once a key is entered) is what actually
  surfaces visa-sponsoring trades roles.
- **Safety invariants re-verified:** no credential is committed (keys live only in DataStore
  and are redacted from URLs by `HttpJsonClient.redactUrl`); nothing is auto-submitted (the
  only network POST is Jooble's search endpoint; `AutoApplyCenter.approve` merely marks a
  draft and tells the user to send it); and no LLM/cloud endpoint exists — all "AI" features
  are on-device heuristics, so no CV or profile text leaves the device.
- **Restart race fixed in `LiveJobRepository`.** The persisted `savedIds`/`autoApplyEnabled`
  collectors are the only thing that seeds the in-memory state on launch. A toggle issued
  before the first emission read the empty default, so removing a bookmark re-saved it (and a
  late emission could clobber a fresh write). Writes now await both restore flags
  (`awaitUserStateRestored`, capped at 5s) and are serialised by `userStateMutex`; the
  collectors apply only their first value. This was surfaced as a flaky `Stage13PersistenceTest`
  failure that alternated between `autoApplyChoiceSurvivesARestart` and
  `unbookmarkingSurvivesARestart`; three consecutive full-suite runs are now clean.

### Targeted global search and AI enhancement (Stage 15)

Stage 14 proved the remote boards return a broad sample, not a maintenance feed. Stage 15
adds a targeted-search layer on top of the existing feed and makes the honesty of every
claim machine-checkable.

- **Target-role taxonomy (`data/jobs/search/TargetSearch.kt`).** A static list of the roles
  the candidate genuinely targets (industrial/mechanical maintenance, pump, fitter, rotating
  equipment, mining, plant, service, production) with the phrases a board accepts and the
  title words that mark each one. `keywordPlan()` sends the canonical titles, then the CV's
  own titles, capped so a keyword provider is not asked for unbounded requests.
- **`RoleClassifier` (`search/RoleRelevance.kt`)** is title-led and allowlist-first: a
  canonical target wins; a strong trade word ("maintenance", "mechanic", "fitter") is
  maintenance-family on its own; a weak word ("technician", "electrician") needs an
  equipment/industry word in the title or the first 400 chars of the description. A title
  naming a clearly different profession (`TargetSearch.otherProfessionMarkers`) is rejected
  even when it also carries a trade word ("Software Engineer — Maintenance Tooling"). The
  verdict carries the words that produced it. The result is stored on `Job.roleFamily` and
  drives the **"My trade only"** filter chip on the Jobs screen.
- **Requirements are read, never invented (`search/RequirementAnalyzer.kt`,
  `search/RequirementAnalysis.kt`).** Years, certifications and licences are extracted only
  from phrases actually present. `QualificationMatcher` reports a gap as `met == null`
  ("cannot tell") when the CV does not mention the thing — it never asserts "you do not have
  it" from a CV that simply did not say.
- **Typed, source-linked evidence (`data/model/Evidence.kt`, `jobs/engine/JobEvidenceEngine.kt`).**
  Every claim about a posting is an `Evidence` item with a kind and a strength: DOCUMENTED
  (employer's own sentence), REFUSED, INFERRED, NEEDS_VERIFICATION or UNKNOWN. UNKNOWN items
  are shown, not hidden. The Jobs explanation sheet renders them with the quote and the
  posting link.
- **Honest AI catalog (`data/model/AiCapability.kt`).** The AI screen lists each capability
  with its real mechanism (rule-based, document parser, grounded template) and location
  (on-device). `HAS_ON_DEVICE_MODEL` and `HAS_REMOTE_MODEL` are both `false`; there is no
  generative model and no cloud endpoint, and the catalog says so plainly.
- **Search coverage (`search/SearchStrategy.kt`).** `JobProvider` reports whether a source
  can narrow by keyword (`serverSideSearch`), and `SearchCoverage` distinguishes a targeted
  search from a broad sample so a generic remote feed is never labelled targeted.
- **Assisted, never silent, applications.** `ApplicationStatus.APPLIED` is the only status
  that stamps the apply time and it is gated behind a confirmation dialog in
  `ApplicationDetailScreen` ("Mark as applied?"). The auto-apply flow prepares drafts and
  never submits; there is no silent submission entry point. Tests pin this invariant.
- **Restart read race fixed in `LiveJobRepository`.** The `profile` and `jobs` read flows are
  now gated behind a `userStateReady` StateFlow (`restoredGate`), so a reader cannot observe
  empty defaults before the persisted state is restored. Because a StateFlow replays its
  value, the gate cannot be missed the way a one-shot flag could. A watchdog opens the gate
  if the store never emits. This is the same class of bug as the Stage 14 write race.
- Tests: `Stage15AssistedApplyTest` (20) pins classification, requirement honesty, coverage
  labelling, the assisted-apply invariants; `Stage15ScreenTest` (5) pins the applied
  confirmation at `w320dp`; `JobsFilterTest` gains the trade filter. Suite: **196 tests, 0
  failures**.

### NVIDIA assistant and configurable Adzuna markets (Stage 16)

Stage 15's AI catalog said plainly that there was no generative model and no cloud endpoint.
Stage 16 makes that statement true instead of aspirational: it adds one real remote model
(NVIDIA's OpenAI-compatible endpoint) behind an explicit consent gate, and lets the user
choose which Adzuna national markets to query.

- **Remote model, opt-in twice over (`data/ai/NvidiaAiClient.kt`, `NvidiaJobAssistant.kt`).**
  `NvidiaAiClient` posts to `https://integrate.api.nvidia.com/v1/chat/completions` with the
  key in the `Authorization` header (never the URL), a timeout, and an output-token ceiling.
  `NvidiaJobAssistant` refuses to send anything unless the user has both saved a key **and**
  turned the assistant on: `consentGranted == false` returns `CONSENT_REQUIRED` with zero
  network calls. Nothing is ever submitted — the model returns a draft the user edits.
- **Narrow grounding (`data/ai/GroundingContext.kt`).** The only thing sent is the
  candidate's own verified fields and the posting's own text. It deliberately excludes the
  CV file, email, phone and address; a drafting model does not need them. `Stage16NvidiaTest`
  asserts the captured request body contains neither the email nor the phone.
- **Every failure has a name (`NvidiaStatus`).** 401/403 → `INVALID_KEY`, 404/410 →
  `MODEL_UNAVAILABLE` ("pick another model", not "the app broke"), 429 → `RATE_LIMITED`,
  5xx/timeouts/DNS → `NETWORK`/`TIMEOUT`, blank key → `NOT_CONFIGURED`. The UI says what
  happened instead of "something went wrong".
- **Never a dead end.** When the assistant is off or a call fails, `localExplanation()` gives
  a deterministic, on-device explanation of the match, prefixed "no data was sent anywhere".
  The score is computed locally and the app never needs the network to explain it.
- **Key handling unchanged from Stage 10.** The NVIDIA key is stored through the same
  encrypted preference path; `AiAssistantCard` shows it masked with a "Replace" affordance
  and never renders the stored value. Consent is a separate switch, stored with the key.
- **Adzuna markets are user-selectable (`AdzunaCountries.supported`).** The country picker in
  Profile → Job sources writes the chosen set; `AdzunaProvider` queries exactly that set
  (each market is its own request because Adzuna's country is a path segment). An empty set
  falls back to the defaults. `Stage16NvidiaTest.onlyTheChosenMarketsAreQueried` pins that an
  unpicked market is never queried.
- Tests: `Stage16NvidiaTest` (11) pins the consent/config gates, the failure classification,
  the exact request body (model id, token ceiling, grounding, no personal identifiers), the
  on-device fallback, and the Adzuna market selection; `ScreenshotTest` gains the assistant
  panel in both themes and the NVIDIA settings card; `JobSourcesScreenTest` gains the market
  picker and the assistant card at `w320dp`. Suite: **212 tests, 0 failures** (1 skipped:
  the opt-in `Stage14LiveProbeTest`).

### Real API configuration and testing (Stage 17)

Stage 16 wired the endpoints but left two gaps for actually testing them from the app: there
was no way to prove an Adzuna key worked, and the NVIDIA model list was a hardcoded guess.

- **Adzuna connection test (`AdzunaProvider.checkConnection`).** Tapping "Test Adzuna
  connection" calls the cheap `/v1/api/version` endpoint first — which separates a bad key
  from a bad search — then runs one real `results_per_page=1` search in the first configured
  market that answers. The result (`AdzunaCheckStatus`/`AdzunaCheckResult`) is a plain-language
  outcome that names the failure: rejected credentials, rate limited, timed out, unreachable,
  unreadable body, service error. It carries what Adzuna actually returned (market, a sample
  title, the reported total), so a live key is distinguishable from a plausible-looking
  failure. An empty market is reported as empty — nothing is fabricated. A rejected key stops
  the loop after one attempt; a market that is merely down falls through to the next.
- **NVIDIA model discovery (`NvidiaAiClient.listModelIds`).** The same `/v1/models` call that
  `validateKey` uses now also returns the ids the account can use, read from `data[].id`. The
  Profile card shows them as selectable rows and offers a free-text field for any id, so the
  user never has to trust the suggested list.
- **The model list is no longer a whitelist.** NVIDIA retires and adds models constantly, so
  `NvidiaModels.isSelectable` accepts any non-blank id and the suggestions are just starting
  points. `UserPreferencesRepository` keeps whatever id the user picked instead of silently
  reverting an unknown one to the default. The suggested ids were also corrected to
  current, widely-hosted models (`meta/llama-3.1-8b-instruct` and friends) rather than the
  retired placeholders Stage 16 guessed at.
- **One request path, not two.** The in-app test and the feed share the same `AdzunaProvider`
  instance (`AppContainer.adzunaProvider`), so "Test connection" exercises the real request
  and parse rather than a separate approximation.
- **Key handling is unchanged.** Keys stay encrypted on-device via `SecretCipher`; the test
  sends them only to the source they belong to, and Adzuna's query-string key is still
  redacted from any failure message by `HttpJsonClient.redactUrl`.
- Tests: `Stage17ApiConfigTest` (17) pins the Adzuna check (success, not-configured, rejected
  key, rate limit vs timeout, unreadable body, empty market, stop-on-bad-key, fall-through,
  key-as-query-param) and NVIDIA discovery (sort/dedupe, rejected key, blank key, bad body,
  assistant passthrough, custom-id persistence across a restart). `JobSourcesScreenTest`
  gains the connection-test result and the custom-model flow; `ScreenshotTest` gains the
  expanded assistant card and the connection-test card. Suite: **232 tests, 0 failures**
  (2 skipped: the opt-in `Stage14LiveProbeTest` and `Stage17LiveApiProbeTest`).

### Live API validation and international job search (Stage 18)

Stage 17 could list models and test keys; Stage 18 proves the selected model can actually
run, and widens eligibility to remote roles scoped to a region that includes Madagascar.

- **Model inference probe (`NvidiaAiClient.testModel`).** "Test the selected model" sends the
  cheapest possible request — a two-word prompt at the smallest output budget the client
  allows — and classifies the outcome. It proves the account can *run* the model, not merely
  list it, which is the gap that let a listed-but-unusable model look fine. The answer is
  discarded; only the verdict is kept. Exposed through `NvidiaJobAssistant.testModel` and
  `AppViewModel.testSelectedNvidiaModel`, surfaced as a result chip under the model list.
- **Unsupported model ids are named, not guessed (`NvidiaStatus.UNSUPPORTED_MODEL`).** A model
  id that is not `vendor/name` shape cannot resolve at NVIDIA, so `complete` and `testModel`
  reject it before spending a request and say so in plain language, instead of surfacing an
  opaque HTTP error.
- **Region-scoped remote eligibility (`EligibilityEngine`).** A remote role bound to a region
  that normally includes Madagascar (EMEA, Africa, Indian Ocean, UTC+1/2/3, …) now resolves to
  ELIGIBLE with a heuristic reason, rather than falling through to UNCLEAR. This matters because
  the mapper flags *any* non-worldwide remote location as `locationRestrictedRemote`, so the
  region case used to be swallowed by the one-country case. Priority is now: hard restriction →
  sponsorship → explicit international → region-including-Madagascar → worldwide remote →
  remote-bound-to-a-foreign-country (UNCLEAR). A region that does *not* include Madagascar
  ("Europe") and a remote role bound to one foreign country both stay UNCLEAR.
- **Nothing is fabricated.** The region reason is marked `official = false`; the probe sends no
  CV facts (asserted in tests); eligibility still never reads silence as sponsorship.
- Tests: `Stage18LiveValidationTest` (15) pins the probe (blank key/model, malformed id, success
  with a parsed model field, empty-body bad response, unknown model, rejected key, no profile
  data in the probe body) and the region verdicts (EMEA/Africa eligible, Europe/US unclear,
  restriction still wins, mapper carries the verdict to the `Job`). `ScreenshotTest` gains the
  model-probe card. Suite: **249 tests, 0 failures** (2 skipped: the opt-in `Stage14LiveProbeTest`
  and `Stage17LiveApiProbeTest`).

### Region-vs-country correctness and explainable ranking (Stage 19)

A hardening pass over the live path, driven by running the probe against the real boards
(Arbeitnow, Remotive, Jobicy, Himalayas — 144 postings, no key needed).

- **A region is not a country (`Country.isRecognized`).** Stage 18's region rule was defeated by
  its own input: the mapper flagged *any* non-worldwide remote location as restricted, so a
  region-scoped role ("EMEA", "Africa") was already classified as "bound to one country" before
  the region rule could run. The flag is now `isRecognizedCountry`, and `CountryCatalog.isKnownCountry`
  returns true only for a real catalog country — a region resolves to no country, so it is judged
  by the region rules. The `EligibilityInput` field is renamed `isRecognizedCountry` to match.
- **Region heuristic can no longer override a restriction.** `remoteRegion` now requires
  `!isRecognizedCountry`, so a *country* whose name embeds a region word ("South **Africa**"
  contains "africa") is no longer rated eligible through the Africa region rule.
- **Explainable rank (`RankComponent`).** `ShortlistEntry` now carries `rankComponents` — the four
  weighted ingredients (route 45 + CV 30 + benefits 15 + profile 10) with points earned and the
  ceiling each was scored against — so the readiness number on the card can be traced rather than
  trusted. The shortlist explanation sheet gains a "How this ranks" breakdown.
- **Honest expat badge.** `ShortlistEntry` carries the real `expatEvidenceCoverage` (was hardcoded
  to 100 in the card) plus `benefitsMatched`/`benefitsConsidered`, so a low score caused by a thin
  listing is labelled "N% documented" rather than read as an employer shortcoming.
- **Live probe exercises the shipping engine.** `Stage14LiveProbeTest` now runs `ShortlistEngine`
  over the real feed and prints each entry's components plus a `rank_components_sum_mismatches`
  counter; on the last run it was 0, i.e. the components sum to the score the user sees.
- Tests: `Stage19HardeningTest` (6) pins the region/country distinction and that the components sum
  to the readiness score and never exceed their ceilings. Suite: **254 tests, 0 failures**
  (2 skipped: the opt-in live probes).

### Automated discovery, freshness and background refresh (Stage 20)

Turns the feed from "refresh when the app is open" into a job search that keeps working while
the app is closed, without ever inventing freshness or firing a notification the user did not ask for.

- **One periodic worker, ever.** `JobRefreshScheduler` schedules a single unique periodic job
  (`JobRefreshWorker` via `work-runtime-ktx`). It is network-constrained (`NetworkType.CONNECTED`)
  and holds off under low battery, so it never runs offline; `ExistingPeriodicWorkPolicy.UPDATE`
  means a preference change replaces the schedule rather than stacking a second one. `apply()` is
  driven by `UserPreferencesRepository.refreshPreferences` collected in `AiJobFinderApp.onCreate`,
  so the switch and the interval always match the worker that exists.
- **Honest retry.** `JobRefreshWorker` returns `Result.retry()` only when the run produced nothing
  usable (no network, or the repository fell back to cache-only); a partial success still succeeds,
  because the cache already holds the last good feed. Backoff is exponential.
- **Freshness never invented (`JobFreshnessEngine`).** Publication age comes only from
  `Job.postedAtEpochMillis`; a missing or future date is `UNKNOWN` ("Date not stated"), never
  "fresh". Retrieval time (`Job.retrievedAtEpochMillis`) is reported separately
  (`retrievedLabel`) so a listing the source keeps returning is never shown as newly published.
  Surfaced in `ApplyLinkSheet` ("Freshness and source"), which also states the source and that a
  working link does not by itself confirm the vacancy is open.
- **Notifications are opt-in and de-duplicated (`JobNotificationPolicy`, `NotificationHistoryStore`,
  `JobNotificationsCoordinator`).** Off by default; the `POST_NOTIFICATIONS` runtime permission is
  requested only when the user turns the switch on (Android 13+; implicit below). A job is announced
  at most once, ever — the notified ids persist to a small file and survive a restart — and the body
  claims visa sponsorship or international eligibility *only* when the eligibility engine already
  found that evidence. Runs are capped (default 3) so a big feed cannot fire a burst. Demo fixtures
  never notify, because they are not real opportunities.
- **UI.** Profile gains a "Automatic refresh" card: an on/off switch, the interval chips
  (6 h / twice a day / daily / weekly) and the "New match alerts" switch with its permission flow.
  The copy says "about every N hours" — never a fixed time — because Android may defer background work.
- Tests: `Stage20DiscoveryTest` (freshness buckets, no-invented-date, notification selection/dedup/
  cap/honest-wording, refresh-pref fallback), `Stage20NotificationTest` (history survives a restart,
  a strong match is announced once, off = silent, demo = silent), `Stage20ProfileScreenTest` (card
  layout at 320 dp, interval wiring, off-state copy). Suite: **272 tests, 0 failures**
  (2 skipped: the opt-in live probes).

### Expat job intelligence and application assistant (Stage 21)

Unifies the findings the app already produces about a posting into one honest, ordered plan, and
rolls the whole feed up into a digest of where the routes in actually are.

- **Per-posting plan (`data/assistant/ApplicationPlanEngine` → `data/model/ApplicationPlan`).**
  `ApplicationPlanEngine.build(...)` is a pure function of the posting, the `ShortlistEntry`
  (from `ShortlistEngine`), the CV completeness and the state of the other tools (cover letter,
  interview rehearsal count, scam verdict, whether it has already been applied for). It returns
  an ordered `List<PlanStep>` (profile → visa route → tailor CV → cover letter → safety check →
  interview prep → submit → follow up), the per-posting `CvTailoring` (which of the candidate's
  *own* skills to lead with, which posting skills the CV does not yet show), the documents to
  prepare and the single `nextAction`. Nothing is invented: a step with nothing to say says so,
  and a `RESTRICTED` posting yields a `BLOCKED` visa step with a quoted reason. `weight` is the
  readiness points a step is withholding, so the checklist reads as impact-ordered.
- **Whole-feed digest (`data/assistant/ExpatIntelligenceEngine` → `data/model/ExpatIntelligence`).**
  `build(ShortlistState)` groups the actionable postings by country into `CountryOpportunity`
  (open count, total seen, best readiness, whether any states an official route), surfaces the
  recurring `ReadinessBlocker`s and the strongest `topOpportunities`. An empty feed yields a
  summary that says so rather than implying opportunity.
- **View models (`viewmodel/Stage21ViewModels.kt`).** `ApplicationPlanViewModel` combines
  `repository.jobs`, `cvRepository.record`, `expatPreferences`, `applicationCenter.applications`
  and `interviewStore.attempts`, and re-derives the plan on any change. `ExpatIntelligenceViewModel`
  combines jobs + CV + preferences and rebuilds the digest. Both factories take the
  `ExpatPreferences` flow so the plan honours the user's priorities.
- **UI (`ui/screens/assistant/`).** `ApplicationAssistantScreen` (readiness hero, next-action
  card, animated step checklist, tailoring, documents, route card, action rows) and
  `ExpatIntelligenceScreen` (summary hero, country cards with open-share bars, recurring gaps,
  strongest opportunities). Both keep the thin `XScreen(viewModel)` / `internal XContent(state)`
  split so tests target the stateless body. Routes: `SecondaryRoutes.EXPAT_INTELLIGENCE` and
  `SecondaryRoutes.APPLICATION_ASSISTANT` (`assistant/{jobId}`, blank id = best current match).
  Both are reached from new AI-tab capability cards (`onOpenIntelligence`, `onOpenAssistant`).
- Tests: `Stage21PipelineTest` (8: blocked route, cover-letter to-do→done, profile gap, progress
  counting, plan carries posting facts, digest counts only Madagascar-open postings, empty feed,
  strongest-first) and `Stage21ScreenTest` (4: assistant headline/checklist scroll, assistant
  empty state, digest sections scroll, digest empty state). `ScreenshotTest` gains
  `application-assistant` and `expat-intelligence` in both modes. Suite: **286 tests, 0 failures**
  (2 skipped: the opt-in live probes).

## Stage 22 — audit and validation

Audited the live data path across every provider, the secure credential store and the
connection states. Two defects were found and fixed in `LiveJobRepository`, both in the
diagnostics table that the source screen and the live-validation screen render with
`items(providers, key = { it.providerId })` — so a duplicate id is a hard crash, and an
empty table hides the very failure the user is looking at.

- **Offline produced an empty table.** `finishAttempt(..., online = false)` passed an empty
  `results` list, and only the dormant rows were added — but a dormant row is produced only
  for a disabled or unconfigured source, so with no such source the table was empty and no
  source was reported as "waiting for network". Added `offlineDiagnostics()`, which emits one
  row per source and keeps the honest disabled / not-configured / waiting distinction.
- **An unconfigured credential-gated source was listed twice, online.** The fetch loop took
  every non-disabled provider, so Jooble (and Adzuna) without a key was queried (failing with
  `NOT_CONFIGURED`) *and* added as a dormant row. The loop now also requires `hasCredentials`,
  so such a source is described once, as `NOT_CONFIGURED`, and is never queried — which also
  saves the pointless request. Providers still guard their own missing key, so this only
  removes work and a duplicate row.

Verified live against the real endpoints (2026-10): Remotive `remotive.com/api/remote-jobs`,
Jobicy `jobicy.com/api/v2/remote-jobs`, Himalayas `himalayas.app/jobs/api` all answer;
Arbeitnow `arbeitnow.com/api/job-board-api` 301-redirects to `www.` and the client's
`instanceFollowRedirects` handles it. No API key, token or secret is hardcoded anywhere in
`src/main`; Adzuna/Jooble/NVIDIA credentials are AES-GCM encrypted at rest via
`data/prefs/SecretCipher` (`AndroidKeystoreSecretCipher`), and a failed Adzuna URL is
redacted before it can reach a message or a log.

Tests: `Stage22AuditTest` (3: offline names every source and reports waiting, not failure;
an unconfigured gated source is not-configured/not-broken and is never queried; an online
attempt lists each source exactly once). Suite: **289 tests, 0 failures** (2 skipped: the
opt-in live probes).

## Stage 23 — device journey & Madagascar candidate validation

Validated the real candidate journey end to end with the app's own providers and engines,
run against the live public endpoints (Remotive, Jobicy, Himalayas, Arbeitnow) via the
opt-in `Stage14LiveProbeTest` (`LIVE_PROBE=1`). No device or emulator is available in this
environment (`/dev/kvm` absent, no `emulator` binary, no system images, no AVD), so the
journey was exercised through the real pipeline and the Compose screenshot/UI test harness
rather than an attached device; the debug and release APKs build cleanly and lint is clean.

The probe retrieved 144 real postings and surfaced a defect that no fixture had caught:
**an unresolved location was badged as worldwide.** Arbeitnow publishes a bare city as the
location (`"Munich"`, `"Kulmbach"`), which `CountryCatalog` cannot map to a country. The
fallback returned `Country(raw, WORLDWIDE_FLAG)`, so a **local German vacancy wore the 🌍
globe and passed the worldwide-remote eligibility rules**, presenting a local job as open to
applicants from anywhere and adding a bare city to the country filter as if it were a country.

- `CountryCatalog.resolve` now returns a distinct `UNRESOLVED_FLAG` ("📍") for a location it
  cannot map, keeping the worldwide globe only for a genuine "Worldwide" / "Anywhere" /
  "Global" listing. `isRecognized` was already false for these, so eligibility was never the
  bug — the globe and the mislabelled local role were. No behaviour changed for a real
  country, for a genuine worldwide listing, or for a region such as "EMEA" (still `UNCLEAR`).

Tests: `Stage23CandidateJourneyTest` (7: a bare city and a region are not given the globe; a
genuine worldwide listing keeps it; a local city vacancy is not scored as worldwide-remote;
a remote listing pinned to an unmapped city stays `UNCLEAR`; a worldwide remote role stays
`ELIGIBLE` and applyable from Madagascar; a recognised country still resolves to its own
name and flag). Suite: **296 tests, 0 failures** (2 skipped: the opt-in live probes).

## Conventions

- Every screen composable reads spacing from `LocalSpacing.current` and applies
  `navBarClearance` as bottom content padding so the floating nav bar never covers content.
- Dark mode is a hand-tuned palette in `Theme.kt`, not an inversion. Check both modes.
- The bottom-nav active pill hugs its content (`AnimatedVisibility` + `expandHorizontally`),
  not a fixed width, so a long label such as "Applications" expands the gradient instead of
  being clipped by it. The label still only exists on the selected item.
- Premium design guidance comes from the workspace `frontend-design` skill
  (`.agents/skills/frontend-design`), installed from `OpenHands/extensions`. Keep Kotlin +
  Compose + Material 3; apply its principles to typography, cards, motion and loading states.
- No hardcoded user-visible strings worth extracting yet; copy lives inline in Stage 1.
