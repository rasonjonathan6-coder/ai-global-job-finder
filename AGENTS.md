# AI Global Job Finder — working notes

Android app (`com.aiglobal.jobfinder`) that helps a Madagascar-based job seeker find and
apply for real international roles. Jetpack Compose + Material 3, Kotlin DSL build.

## Environment

- JDK 21: `/usr/lib/jvm/java-21-openjdk-amd64` (`JAVA_HOME`)
- Android SDK: `/home/openhands/android-sdk` (`ANDROID_HOME`); compileSdk/targetSdk 34, minSdk 26
- Gradle 8.7 via the wrapper (`./gradlew`). `local.properties` points at the SDK.
- No KVM in this container, so **do not** plan on a booted emulator. Verify UI headlessly instead.

The toolchain lives outside `/workspace`, so an idle cleanup can wipe it while the repo
survives. If `java` is missing, restore it (this needs the box's `sudo`, which the
`openhands` user has):

```bash
sudo apt-get update && sudo apt-get install -y openjdk-21-jdk-headless unzip curl
mkdir -p /home/openhands/android-sdk/cmdline-tools && cd /home/openhands/android-sdk/cmdline-tools
curl -L -o c.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q c.zip && mv cmdline-tools latest
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/openhands/android-sdk
yes | ./latest/bin/sdkmanager --licenses
./latest/bin/sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

Run Gradle with `JAVA_HOME` and `ANDROID_HOME` exported and `$JAVA_HOME/bin` on `PATH`. The
first build downloads Gradle 8.7 and the dependency set (several minutes); run it in the
background and poll the log rather than blocking on a foreground command.

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
- Bottom-nav items expose `contentDescription` on the `NavigationBarItem` (the stable English
  name from `Destination.contentDescription`), and all five labels are always shown. Match nav
  targets with `onNodeWithContentDescription`; assert the visible label with `onNodeWithText`.
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
  UUID-derived name), `CvParserRegistry` (PDF via pdfbox-android, DOC/DOCX, TXT) and the
  optional `CvAiAnalyzer`. `CvFileType` lives in `data/model`.
- `data/cv/analysis/` — the **shareable** half of CV handling (Stage 68): `CvTextNormalizer`,
  `CvLexicon`, `CvTextStructure`, `CvAnalyzer` (`HeuristicCvAnalyzer` + `CvAnalysisResult`),
  `CvProfileMerger` (with `CvCompleteness`), `CvAnalysisWarnings`, `CvAnalysisLimits` and
  `CvAnalysisContract`. It imports only `data.model` — no `Context`, `Uri`, `Log` or file
  I/O — so it is a pure function of its inputs and is mirrored verbatim in
  `web/src/core/cv/`. The platform-bound parsers read `CvAnalysisLimits`, never the reverse.
- **pdfbox-android MUST be initialised before any PDF call.** PDFBox loads its glyph lists,
  AFM font metrics and CMaps from the app assets and, if `PDFBoxResourceLoader` was never
  initialised, the first `PDDocument.load`/`PDFTextStripper.getText` aborts the process
  (device logcat: `RuntimeException: FileNotFoundException: com/tom_roush/pdfbox/resources/
  glyphlist/additional.txt` from `LegacyPDFStreamEngine`). Because an uncaught exception on
  a background dispatcher kills the whole app, this surfaced as "the app keeps stopping" on
  "Analyse my CV". `PdfCvParser(context)` now calls `PDFBoxResourceLoader.init(context.
  applicationContext)` lazily (guarded by `isReady()`), so `CvParserRegistry.default(context)`
  takes the app `Context`. `Stage27CvAnalysisTest` locks this in — it asserts `isReady()` and
  reads a real asset-backed PDF under Robolectric. Do not log CV content or keys.
- **`CvTextNormalizer` is the single gate between raw text and every extractor.** PDFBox
  splits lines across text fragments and can double-decode UTF-8 as Latin-1, so "Mécanicien"
  can arrive as "MÃ©canicien" and "technician" as "techni-\\ncian". Text is repaired once
  (`normalize`) before parsing finishes, and matching runs on an accent-folded copy
  (`fold`). Extractors must never compare against raw case-folded text — that is what made
  the title appear while skills, education and languages stayed empty on a real CV.
  `fold` is for comparison only; the value stored in the profile keeps its original accents.
- **Keyword matching must be accent-insensitive.** `CvLexicon.sectionKeywords`,
  `titleMarkers`, `degreeMarkers` and skill/language aliases are looked up against folded
  text. `CvLexicon.languageAliases` maps canonical language names to their English, French
  and Spanish spellings, so a francophone CV listing "Anglais"/"Français"/"Malgache" is read
  instead of being scored as having no languages. `CvLexicon.foldedPlaces` (countries +
  major cities) backs location detection.
- **Absent vs unreadable is a different message.** `CvAnalyzer.sectionWarnings` reports
  "no section found" when the heading is missing and "a section was found but nothing could
  be read" when the heading exists with body text under it. Never report the former when the
  latter is true. Fields are only ever populated from text that is actually present — the
  analyzer never invents a name, phone or degree.
- `Stage28CvExtractionTest` covers all of the above: a realistic header with the job title
  before the name, a French CV with accents and French headings, a document that must yield
  empty fields, and the normaliser (mojibake, hyphen splits, folding).
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

## Stage 24 — industrial, mining & expat sources

Stage 23's live probe showed the key-free boards return broad remote samples, not the
industrial and mining vacancies an expat maintenance candidate actually applies for. Stage 24
adds sources that reach those roles, in two families:

- **Official state/EU feed.** `EuresProvider` (kind `GOVERNMENT`) queries the European
  Employment Services public search API (`europa.eu/eures/api/jv-searchengine/public/jv-search/search`).
  It covers the EEA plus Switzerland. The endpoint takes a keyword list but exposes no
  per-country query, so the provider sends one target title per request (`MAX_QUERIES = 4`)
  and pools the results. The full description is inline, and the `locationMap` is turned into
  a country name via `EuresCountryNames` (ISO-3166 alpha-2 → the canonical name the catalog
  resolves against) so eligibility can be judged. A vacancy with no description is dropped
  rather than faked.
- **Employer applicant-tracking-system boards** (kind `EMPLOYER_BOARD`, new this stage).
  `WorkdayProvider`, `GreenhouseProvider`, `AshbyProvider` and `LeverProvider` read the
  *employer's own* careers site — the most trustworthy feed for expat and industrial roles,
  and the posting applies directly with no middle party. Each holds a curated, verified list
  of employer sites (`WorkdayBoards`, `GreenhouseBoards`, `AshbyBoards`, `LeverBoards`).
  Workday's search row carries only a free-text location, so a bounded, concurrent per-posting
  detail call reads the real country and the description; Greenhouse's list row likewise omits
  the description, so a bounded concurrent detail call fills it in; Ashby and Lever return the
  description and all locations inline in one request.

Lever has no server-side keyword search and some boards are very large (Shield AI publishes
~600 roles, ~9 MB of JSON), so `LeverProvider` reads a bounded number of pages (`PAGE_SIZE`
100, `MAX_PAGES` 3) and filters locally against the target titles — a role buried deeper in a
huge board may be missed, accepted in exchange for a bounded mobile download. `JsonFeed`
gained `arrayAt` because Lever's board is a top-level JSON array rather than an object.

`ProviderKind` gained `EMPLOYER_BOARD` (a single employer's own ATS site) alongside the
existing `REMOTE_BOARD`, `GOVERNMENT` and `AGGREGATOR`, documented for the trust signals.

`CountryCatalog` was extended so the new coverage actually resolves: the missing EEA members
(Slovakia, Slovenia, Bulgaria, Croatia, Cyprus, Malta, Estonia, Latvia, Lithuania,
Liechtenstein) and mining-relevant countries (Guinea, Mali, Burkina Faso, Tanzania, Zambia,
Botswana, Namibia, DR Congo, Mongolia, Kazakhstan, Chile, Peru, Suriname, Papua New Guinea).
An unresolved location stays `UNRESOLVED_FLAG`, exactly as Stage 23 fixed.

All three ATS providers treat "no site answered" as a `JobProviderException` (so the sync
banner shows the source as unavailable) but a *partial* answer — some sites up, some down — as
a normal result, and a failed detail row is skipped rather than dropping the rest.

Tests: `Stage24IndustrialSourceTest` (11: EURES resolves the ISO country and uses the official
vacancy link and the record survives validation; EURES drops a description-less record;
EURES country coverage resolves to real countries; Workday reads country and description from
the detail call and sends the keyword server-side and its curated sites are well-formed;
Greenhouse uses the employer link and its inline detail; Ashby reads the inline description
and joins secondary locations and skips the "don't see your job" placeholder; Lever keeps only
target titles, reads the inline detail and joins all locations, and pages a board until an
empty page stops the walk; the industrial titles classify as maintenance family; and each
provider reports "no answer" as a usable failure, not an empty result). Suite: **307 tests,
0 failures** (2 skipped: the opt-in live probes).

**Resolved in Stage 27 — the `Stage22AuditTest` flake.** The intermittent
`expected:<IDLE> but was:<LOADING>` / `network is not available` failures had *two* causes, both
now fixed:

1. The test's `awaitSettled` polled `diagnostics` with a predicate
   (`!isRefreshing && no provider IN_PROGRESS`) that the repository's **seed state** also
   satisfies — the object is constructed "not refreshing" before its init coroutine runs, so
   `first {}` returned the blank seed instead of waiting. The wait now also requires a recorded
   attempt (`lastAttemptAtEpochMillis != null`) before it can settle.
2. `LiveJobRepository` launched **two** refreshes on startup: the explicit init refresh plus one
   from `demoMode.collect`, which fires on its own seed value. The two could serialize, so a
   source was queried twice and `syncState` was read mid-second-attempt. The collector now uses
   `demoMode.drop(1)`, so the seed value no longer schedules a refresh and a launch queries each
   source once.

The shared helper lives in `RepositorySettle.kt` (test source). Regression tests pin both the
seed-vs-attempt contract and "one launch reads each source once". Verified green 42 consecutive
runs where the old code failed roughly 1 run in 4.


## Stage 25 — technical job discovery quality & expat evidence

Measured against 277 real postings pulled from the app's own providers (Greenhouse, Ashby,
Lever, Workday) rather than guessed at. Two findings drove this stage.

**Relevance classifier (`RoleRelevance.kt` / `TargetSearch.kt`).** The "maintenance family"
gate accepted a weak trade word ("technician") whenever the *description head* carried any
industry word. Because almost every posting at an industrial employer opens by describing that
employer, 21 non-trade roles passed on the live corpus — "Process Technician", "EHS
Technician", "Quality Technician", "Controls Technician", "IT Support Technician" — purely on
a Form Energy-style blurb ("an American manufacturing and energy technology company"). The
support words are now split: concrete equipment/machinery (`TargetSearch.equipmentTerms`,
extended with mining terms: drill rig, excavator, haul truck, smelter, refinery, flotation,
thickener, pump station, …) and a *named* plant/mining setting count from the title or the
description head, but the broad sector words (`TargetSearch.industryTerms`: industrial, plant,
production, manufacturing, processing, factory) count only when they appear in the **title**.
`TargetSearch.descriptionIndustryTerms` is the narrow subset (mining, mine, smelter, refinery,
juice, beverage, food processing) allowed from the description. A lone technician title with no
trade context is still unrelated (Stage 24 rule preserved); a title that names the setting
("Production Technician") or whose body names a concrete machine still classifies as
maintenance family. Re-running the simulator: relevant 133 → 124 of 277, with every dropped row
a genuine false positive and nothing the candidate would want lost.

**Expat evidence (`ExpatBenefitExtractor.kt`).** The strong phrase table missed wording real
European and FIFO postings use, so a posting that plainly documented a package scored as if it
documented nothing. Added, from observed postings: visa ("visa sponsorship provided", "work
visa sponsorship", "immigration support", "we provide visa"), relocation ("relocation
assistance is available", "relocation benefits", "relocation expenses", "we offer relocation",
"relocation program"), accommodation ("camp accommodation", "company housing", "living
allowance", "staff housing"), flights ("air ticket(s)", "return flights", "annual return
flight", "rotation flights", "airfare"), health ("medical aid", "medical scheme", "health
benefits", "medical benefits"), transport ("company vehicle", "site transport", "shuttle",
"transport to and from"), meals ("free food", "site canteen", "three meals"), and family
("dependent visa", "spouse visa", "family relocation"). The refusal phrasings real employers use
("unable to offer sponsorship", "not able to sponsor", "cannot sponsor") were added so an
explicit refusal is still recorded as `NOT_OFFERED`, and the new phrases are all multi-word, so
incidental prose (a "family-owned" company, "transportation of materials") still yields
`UNKNOWN` rather than inventing a benefit.

**Coverage honesty (`SearchStrategy.kt`).** `SearchCoverage.summary` now states how many of the
retrieved postings matched the trade and the percentage (`relevantSharePercent`), so a large
unrelated remote feed cannot read as a good result set.

Tests: `Stage25DiscoveryQualityTest` (16: a generic technician title at an industrial employer
and five more live generic-technician titles are rejected despite industrial blurbs; a named
plant setting or a concrete equipment/mining word still supports a weak trade title; mining
equipment titles are recognised; genuine trade titles remain maintenance family; a broad sector
word in the description does not rescue an unrelated technician; European relocation/visa and
health wording is documented; company vehicle / site shuttle are documented as transport; FIFO
camp + flights wording is documented; an explicit refusal to sponsor is `NOT_OFFERED`;
incidental prose invents no benefit; and the broad-sample summary states the trade match count
and share). Suite: **323 tests, 1 failure** — the documented pre-existing `Stage22AuditTest`
flake below, which passes in isolation and reproduces on the untouched Stage 24 baseline.

## Stage 26 — shortlist quality dashboard & actionable ranking

Stage 25 fixed *what* the feed contains. This stage answers the next question a job seeker
actually asks: **is the shortlist I am looking at any good, and what is the single next thing
to do about each posting?** Both live on the existing shortlist screen and in `Shortlist.kt`;
no engine or repository behaviour changed, so the pipeline stays untouched.

**Shortlist quality read (`ShortlistState.quality` → `ShortlistQuality`).** A snapshot-derived
verdict with four bands — `NONE` (empty), `THIN` (nothing actionable), `BUILDING` (1–4
actionable, or fewer than five with no top-band posting), `STRONG` (≥5 actionable including at
least one `PRIORITY`). It reports the actionable count and its share of everything screened, the
strongest readiness score, the number of countries represented, and the leading country (the one
with the most actionable postings, or none). The detail line states plainly how many were
screened and that the figures describe *what the listings state*, ending "verify before you
invest time" — the same honesty rule as every other surface. `ApplicationReadiness.PRIORITY_MIN`
/`READY_MIN` are now named constants so the dashboard band and `fromScore` can never drift apart.

**Actionable ranking (`ShortlistState.rankedActionable` / `needsPreparation`).** The engine
already sorts by readiness then match, so `rankedActionable` keeps that order instead of
re-sorting. `needsPreparation` is everything not yet actionable **and not `RESTRICTED`** — a
local-only posting has no route in, so it is never presented as "worth preparing for".

**One next step per posting (`ShortlistEntry.nextStep`).** Derived from the hardest-hitting
blocker (the same `orderedBlockers` the card already shows), so the advice and the score always
agree. A posting with no blockers is "Ready to apply"; an unstated route is "Check eligibility
first — ask before applying" (never a claim the employer refused); a documented-but-thin package
is "Ask about the package … missing information, not a refusal"; a high scam risk is "Run the
scam check"; a CV-match gap is "Tailor your CV"; a low profile completeness is "Complete your
profile". `NextStepAction` drives the row's icon and tone.

**Screen.** A `QualityDashboard` card sits below the existing summary (band pill, actionable
share bar, three stats, honesty line). The list is split into "Ready to act on" (cards without a
next-step row) and "Worth preparing for" (cards **with** the next-step row), the latter showing
three at a time behind a "Show N more" button. When nothing is actionable, a `PreparationPrompt`
says so plainly and offers "Browse all matches". The explanation sheet also carries the next
step, so the advice is visible wherever the user reads the reasoning. All new icons come from
`material-icons-extended`, already a dependency.

Tests: `Stage26PipelineTest` (13: apply/eligibility/benefits/CV-gap/profile/scam next steps; a
silent posting says "ask" not "refused"; actionable order preserved and local-only excluded from
both lists; empty/thin/building/strong quality bands with their exact counts and shares; the
leading country only appears when something is actionable). `Stage26ScreenTest` (4: the dashboard
and its headline are reachable at 320 dp; a ready-only list shows no preparation section; a
gapped posting shows its next step; the empty state still explains itself). Suite: **340 tests,
0 failures** — the documented `Stage22AuditTest` flake stayed green this run.

## Stage 27 — stabilize tests, validate the APK, prepare real-world use

**The flake is fixed, at its source.** Two independent defects caused the intermittent
`Stage22AuditTest` failures (see the note above): the test's settle predicate matched the
repository's pre-attempt seed, and the repository fired a duplicate startup refresh from the
demo-mode collector. The fix is deliberately small and does not weaken any assertion:

- `app/src/test/java/com/aiglobal/jobfinder/RepositorySettle.kt` — one `awaitSettled` helper for
  the test source. It waits on **both** `diagnostics` and `syncState`, and it only accepts a
  diagnostics state that records an attempt (`lastAttemptAtEpochMillis != null`). That single
  extra clause is what excludes the seed. `isSettledDiagnostics(...)` is the pure predicate,
  exposed so the regression tests can pin the contract without racing the init coroutine.
- `LiveJobRepository` — the demo-mode collector is now `demoMode.drop(1).collect { ... }`. Its
  seed value is not a mode change; the explicit init refresh already covers startup. This removes
  a redundant refresh on every launch and is a real behaviour improvement, not a test-only tweak.
  Leaving demo mode still triggers a refresh, because a genuine change into live mode is always a
  later emission.

Tests added to `Stage22AuditTest` (now 9): the untouched seed must **not** settle; a recorded
finished attempt **must**; an in-flight attempt (refreshing, or a provider in progress) must not;
the seed-vs-attempt contract end to end; one launch reads each source once; and after settling,
both flows describe the same finished attempt. Reproduced the old failure (~1 run in 4) before
fixing, then verified **42 consecutive green runs**.

**Artifacts (verified, not assumed).**

| Artifact | Path | Size | Signing |
| --- | --- | --- | --- |
| Debug | `app/build/outputs/apk/debug/app-debug.apk` | ~29.8 MB | Android debug key (installs on a personal phone) |
| Release | `app/build/outputs/apk/release/app-release-unsigned.apk` | ~8.4 MB | **unsigned** — not install-ready |

Debug package `com.aiglobal.jobfinder.debug`, versionName `1.0.0-debug`, versionCode 1, minSdk 26,
targetSdk 34. Release is minified/shrunk and shares the same code; only its signing is absent.

**Validation.** Unit suite **354 tests, 0 failures, 0 errors, 2 skipped** (the two opt-in
`LIVE_PROBE=1` probes, skipped by design). `lintDebug` **0 errors, 52 pre-existing warnings**.
`assembleDebug` and `assembleRelease` both succeed. There is **no emulator run** — this container
has no KVM, so a device/emulator pass is out of scope and must be done by the user.

**Real-world use.** `docs/DEVICE_TEST_CHECKLIST.md` is the manual checklist for installing the
debug APK on the phone and verifying CV import, honest refresh/caching, shortlist quality,
unknown-visa handling, next-step guidance, employer links, editing, offline recovery,
notifications and both themes. It states plainly which layers are automated and which are not.

## Stage 29 — honest CV extraction (partial results, per-page counts)

**Reported:** importing `Jonathan_Rason_CV_Updated.pdf` (1.9 MB) showed "Analysed" with 12%
completeness — only the title *"Mechanical Seal Repair Technician"* and two skills, and no
name, email, phone, location or experience.

**Root cause (established from the code, not assumed).** The status was not derived from how
much text was recovered: `CvRepository.analyzeImported()` granted `CvStatus.ANALYZED` whenever
at least one character came out, and `CvParseResult.hasUsableText` was the only gate. A PDF whose
text layer yields a handful of words therefore looked like a *complete* analysis, and the
completeness percentage — the sum of the field weights actually found — correctly reported the
little it had (a title is worth 15% → 12% after normalisation). Separately, `PdfCvParser` never
counted pages and never distinguished an image-only (scanned) PDF from an empty one, so the user
had no diagnostic to act on. **The user's real CV is not in this repository**, so it could not be
run; the fix targets the confirmed code path and the tests are synthetic — no claim is made about
the real file.

**Fix.**

- `data/cv/CvParser.kt` — `CvPageText` (1-based `pageNumber`, `characterCount`, `isEmpty`); the
  result now exposes `pages`, `isPartial`, `imagesOnly` and `hasAnythingExtractable` on top of the
  existing `hasUsableText` / `characterCount`.
- `data/cv/PdfCvParser.kt` — page-by-page extraction respecting `setStartPage`/`setEndPage`, a
  per-page contributed-character count, honest image-only detection (an image XObject plus no
  text), a `pageCount == 0` guard, and `CvParseException.Corrupted` on a malformed file.
- `data/model/CandidateProfile.kt` — new `CvStatus.PARTIAL("Partly read")`, persisted by name, so
  a fragment is never shown as "Analysed". `IMPORTED` / `ANALYZED` / `FAILED` are unchanged.
- `data/repository/CvRepository.kt` — nothing extracted → `FAILED` (with page/char counts kept);
  some text but below `MIN_USABLE_CHARACTERS` → `PARTIAL`; otherwise `ANALYZED`.
- `ui/screens/cv/CvScreen.kt` + `viewmodel/CvViewModel.kt` — a warning `PartialReadBanner` states
  how much was recovered, the hero reads "Analysis incomplete", the status pill uses warning
  colours for partial, and a `TechnicalDetailsCard` shows pages, characters, format, size and
  engine. This is what turns the opaque "12%" into a diagnosable number.

**Tests (11 new).** `Stage29PdfExtractionTest`: multipage with no truncation, a page-by-page
gap count, French accents (Helvetica/WinAnsi, no external font), a two-column layout keeping both
columns, a title-only fragment reported as partial, an image-only PDF reported as image/OCR
(not parsed), a corrupt PDF → `Corrupted`, a password-protected PDF → `Encrypted`, no invented
fields, and Stage 27/28 PDF behaviour preserved. `CvScreenTest` gains a partial-status UI test.
Suite **377 tests, 0 failures, 2 skipped** (the opt-in `LIVE_PROBE=1` probes). `lintDebug`,
`assembleDebug` and `assembleRelease` all succeed. No emulator run (no KVM in this container).

## Stage 30 — application registry, dedup and simulation mode

The auto-apply flow could already prepare a draft per posting, but nothing recorded that an
opening had *already* been handled, and the same posting arrives repeatedly from several boards.
Stage 30 adds the persistent registry that closes that gap.

**New package `data/submissions/`.**

- `Submission.kt` — the identity and vocabulary of a send.
  - `SubmissionKey(company, title, country)` is the dedup identity, **not** the app's job id:
    normalised (case-folded, punctuation stripped) and provider-agnostic, so the same opening from
    Adzuna, Jooble, EURES or an employer ATS collapses to one key, and it matches the rule the feed
    already uses to collapse cross-source duplicates. The title is part of the key, so two roles at
    one employer stay distinct; the country keeps a global employer's two sites apart.
  - `SubmissionChannel` (`ATS_API`, `EMPLOYER_PORTAL`, `EMAIL`, `UNKNOWN`) is inferred from the
    posting URL, and carries `realSendSupported` + a plain-language `compatibilityNote`. **Every
    channel is currently `realSendSupported = false`** — the compatibility gate the user asked for.
  - `SubmissionOutcome` distinguishes `SENT` (real, unreachable in this stage) from `DRY_RUN`
    (simulated) and the three refusals: `BLOCKED_DUPLICATE`, `BLOCKED_CONCURRENT`,
    `BLOCKED_NOT_ENABLED`, `BLOCKED_INCOMPATIBLE`.
  - `SubmissionEntry` (persisted), `SubmissionDecision`, `SubmissionRequest`, `SubmissionPolicy`.
- `SubmissionRegistryStore.kt` — `submissions.json` in app-private storage, atomic write behind a
  mutex, same pattern as `ApplicationStore`/`AutoApplyStore`. `sendingEnabled` defaults to `false`.
- `SubmissionRegistry.kt` — the rules:
  1. **One opening, one application.** One entry per `SubmissionKey`; a second attempt returns
     `BLOCKED_DUPLICATE` and leaves the first receipt untouched. Works across sources and restarts.
  2. **No concurrent sends.** `submit()` runs its decide-and-record step under a mutex, so racing
     coroutines cannot both pass the duplicate check; the loser gets `BLOCKED_CONCURRENT`.
  3. **Simulation only.** A real send requires `allowRealSend` **and** `sendingEnabled` **and**
     `channel.realSendSupported` **and** a wired-in `realSender`. With no verified sender in this
     stage, `SENT` is unreachable and every attempt is recorded as `DRY_RUN`. The app never asks
     for, stores, or uses a mailbox password.

**Wiring.** `AiJobFinderApp.submissionRegistry` is exposed and injected into `AutoApplyCenter`.
Approving a draft now records a simulated attempt through the registry, so approving the same
opening twice is refused and the item says so — nothing is ever sent. All new constructor
parameters are optional, so the existing pure decision logic stays testable without a registry.

**Tests (11 new).** `Stage30SubmissionRegistryTest` drives the real registry over a real on-disk
document: a second submission of the same opening is refused; the same opening from a different
source is refused (and the refusal names the other source); the refusal survives a restart; 12
concurrent submissions of one opening record exactly one and block the other 11; sending is off by
default and nothing is sent; a real send is refused while sending is off, for an incompatible
channel even when activated, and when no verified sender is wired in; no channel claims real-send
support; the channel is inferred from the URL; and identity ignores case/punctuation while keeping
distinct roles apart. Suite **388 tests, 0 failures, 2 skipped** (the opt-in `LIVE_PROBE=1` probes).
`lintDebug`, `assembleDebug` and `assembleRelease` all succeed. No emulator run (no KVM here).

## Stage 31 — deduplication audit and user verification

Stage 30 reduced identity to a single key (`employer + normalised title + country`). That is
enough to collapse a repost across boards, but it silently merges two genuinely different
openings whose employer and title are close ("Maintenance Technician" vs "Maintenance
Technician II", "Northline Industrial" vs "Northline Industries"), and it cannot use the
canonical signals each listing actually carries. Stage 31 replaces the single key with a
layered, confidence-based identity and hands the genuinely undecidable cases to the user.

**New `data/submissions/SubmissionIdentity.kt`.**

- `SubmissionIdentity` holds three signals in order of reliability: `providerRecordId`
  (`job.id` = `providerId:externalId`), `canonicalUrl`, and `fingerprint`
  (employer + title + country, normalised). `relationTo` maps them to `SAME` / `AMBIGUOUS` /
  `DISTINCT`:
  - `SAME` — a shared record id, a shared canonical URL, or an identical fingerprint.
  - `AMBIGUOUS` — same country and both employer and title very similar (Jaro-Winkler /
    token-set similarity ≥ 0.90 and 0.85), yet not provably the same.
  - `DISTINCT` — anything else.
- `CanonicalUrl.of` strips scheme, leading `www.`, the fragment, a trailing slash and known
  tracking parameters, then sorts the surviving query parameters, so tracking noise collapses
  to one value while different hosts never collide.
- `TextSimilarity` combines Jaro-Winkler and a token-set ratio (takes the stronger), so word
  order and small suffixes do not create false differences.

**Rewritten `SubmissionRegistry.kt`.**

1. **Same posting is still refused, now via reliable signals first** — shared record id or
   canonical URL short-circuits to `BLOCKED_DUPLICATE`; the fingerprint is the fallback, so an
   Adzuna listing and the employer's own page still collapse when nothing else matches.
2. **Ambiguous postings are never merged and never allowed.** They are recorded as
   `SubmissionOutcome.NEEDS_VERIFICATION` with `pendingReview` pointing at the entry to compare
   against, and the reason names both sides. Nothing is sent.
3. **The user settles it.** `confirmSame` folds the parked entry into the original (moving its
   record id and URL into the original's `aliases`, indexed alongside the primary signals, so
   the confirmed duplicate is recognised later) and reports `BLOCKED_DUPLICATE`. `confirmDistinct`
   promotes the parked entry to a normal receipt and indexes its signals so it cannot be recorded
   twice. Both rebuild the identity index from the surviving entries.
4. **Re-submitting a parked posting stays pending, not duplicate**, so the same unresolved
   posting is never re-parked and the user is never told they "already applied" to something
   they have not confirmed.
5. **Safety posture unchanged** — `sendingEnabled` still defaults to `false`, no channel claims
   real-send support, and `SENT` remains unreachable. All mutations stay behind the registry's
   `Mutex`, and persistence keeps its atomic-write contract.

**UI.** `SubmissionHistoryViewModel` + `ui/screens/submissions/SubmissionHistoryScreen.kt`
(`SubmissionHistoryContent`, stateless for tests) list every entry with its outcome badge
(Simulated / Already applied — blocked / Needs your verification), the plain-language reason,
a registry summary (attempts, duplicates blocked, need verification), and — for parked entries —
"Same posting" / "Different" actions. Reached from **Profile → Submission history** via
`SecondaryRoutes.SUBMISSIONS`. The screen is read-only about sending: it only records the user's
verdict. `AutoApplyCenter` surfaces the registry decision (`DRY_RUN` or a duplicate refusal).

**Tests (21 new).** `Stage31DedupAuditTest` covers URL canonicalisation (tracking noise and
fragments collapse, different hosts stay different); the same opening with different URLs, a
shared canonical URL, and a shared record id are all refused; two roles at one employer and one
role in two countries stay distinct; a near-identical title and a slightly different employer
name are parked for verification (and the parked attempt is not sent); confirming "same" folds
the entry and still refuses a resubmission; confirming "different" keeps both and refuses a
second record of the new one; a re-submitted parked posting stays pending; `relationTo` is
unit-tested for SAME/AMBIGUOUS/DISTINCT; an ambiguous decision survives a restart; 12 concurrent
submissions of one opening record exactly one; and sending stays off with no channel able to
really send. `Stage31SubmissionHistoryScreenTest` renders the screen at `w320dp` and scrolls to
every important string in each state. `ScreenshotTest` gained a `submission-history` render
(light + dark, 411×891 dp). Suite **410 tests, 0 failures, 2 skipped** (the opt-in `LIVE_PROBE=1`
probes). `lintDebug`, `assembleDebug` and `assembleRelease` all succeed. No emulator run (no KVM).

**Note on the Stage 30 test.** One Stage 30 identity test shared a single default `sourceUrl`
across its fixtures; with URLs now decisive that made two "different" roles collide. The fixtures
were given their own board ids and URLs (and one an explicit cross-board case) so the test still
checks what it always did — case/punctuation collapse, distinct roles stay apart.

## Stage 32 — validation of the deduplication and history journey

Stage 31 made identity layered, but its similarity thresholds still decided *ambiguity* from
fuzzy text alone. Two genuinely different postings that share a prefix — "Maintenance
Technician" vs "Maintenance Planner" (0.895), or the same employer's "Role 1" vs "Role 2"
(0.933) — were parked for the user even though their board record ids and canonical URLs
plainly said they were different. Stage 32 validates the whole journey end to end and closes
that gap: canonical signals now establish *difference* as well as sameness.

**`SubmissionIdentity` — provable difference.**

- `matchesProvablyDifferent` returns true when the same board (`providerKey`) carries two
  different record ids (`externalId`), or the same URL host carries two different canonical
  URLs. `relationTo` consults it *after* `matchesExactly`, so a shared id/URL/fingerprint still
  wins, but a fuzzy title match can no longer override two conflicting canonical signals.
- Consequence: "Maintenance Technician" vs "Maintenance Planner" at one employer, and "Role 1"
  vs "Role 2" on one board, are now `DISTINCT` — recorded separately, never parked. Genuine
  cross-board re-posts with no canonical signal in common still fall through to `AMBIGUOUS`.

**Journey visibility.**

- `AiJobFinderApp.onCreate` loads `submissionRegistry` at start, so the persisted entries are
  in memory before either screen opens.
- `ProfileScreen` gained a `pendingVerifications: Int` parameter and shows an info `Pill`
  ("N to verify") on the Submission-history card when postings await the user's verdict;
  `AiJobFinderRoot` supplies it from `submissionRegistry.entries`. The path to the verdict is
  now visible from Profile without opening the history screen.

**Tests (14 new).** `Stage32DedupJourneyTest` drives the real registry over a real on-disk
document (reopened to prove persistence), one test per guarantee: a shared record id is
identical despite drifted metadata; a shared canonical URL collapses tracking noise (and
`CanonicalUrl` keeps real params, sorts them, drops `utm_*`/`fbclid`/fragment); four distinct
roles at one employer stay distinct; an ambiguous posting is parked (not a confirmed duplicate,
recorded, pointing at the entry to compare, nothing sent); `confirmSame` merges sources and
keeps the parked record id and URL as aliases that are honoured after a restart; `confirmDistinct`
keeps both postings across a restart and still refuses a second record; a pending posting cannot
be sent even with `allowRealSend` and sending "activated"; notifications fire once per posting
and never touch the registry; 20 concurrent submissions (8 distinct + a 12-strong storm on one
opening) record exactly the distinct ones and one of the storm, unique keys, nothing lost across
a reopen; the identity relation never calls two distinct roles the same; a blank-employer posting
does not collide with a real one; and the safety posture is unchanged (`sendingEnabled` false,
no channel able to send, a refused real send records nothing, the simulated path records a
non-sent entry). `ScreenshotTest` gained a `profile-verification` render (light + dark).
Suite **425 tests, 0 failures, 2 skipped** (the opt-in `LIVE_PROBE=1` probes). `lintDebug`
(0 errors, 52 pre-existing warnings), `assembleDebug` and `assembleRelease` all succeed. No
emulator run (no KVM here).

## Stage 33 — validation of the CV → offers → deduplication → history journey

A *validation* stage with no functional change intended, and none made: every scenario that
could be exercised off-device passed, so no defect was reproduced and nothing was "fixed".
The stage's honesty requirement is that an on-device run is never claimed when no device was
used. Here there is **no device and no emulator**: `adb devices` is empty, `/dev/kvm` does not
exist (no `vmx`/`svm` in `/proc/cpuinfo`), and the SDK has no `emulator` binary and no
`system-images`. So the twelve device scenarios are reported as *automated-covered* or
*not run on device*, never as an Android device validation.

**Tooling checked first.** `adb version` = 1.0.41 (present), `adb devices` = empty, no
`emulator`/`system-images` under `ANDROID_HOME=/home/openhands/android-sdk`, no KVM. An
x86_64 Android emulator cannot start without KVM here, and there is no ARM host for an ARM
image, so an AVD was not attempted.

**Scenario coverage (device scenarios from the brief).**

- Automated, deterministic: 6 (one posting from two sources → one application), 7 (two
  different postings stay distinct), 8 (confirm-duplicate survives a restart), 9
  (confirm-distinct survives a restart), 10 (the "N to verify" figure equals what is really
  pending), 11 (the history never shows `Sent`, and a forced real send still cannot reach it).
- Automated via existing suites: 2 (PDF with a text layer), 3 (image/scanned PDF reported as
  partial/impossible, not "analysed"), 4 (no fabricated title/skills/experience) → Stage 28/29;
  5 (filters, match scores, country/visa) → Stage 10/21/24/25; 12 (offline and API-error
  behaviour: every source still named, nothing reported as a failure, cached-only surfaced) →
  Stage 22.
- Not run on a device: none of 1–12; scenario 1 (install and launch) and the touch/scroll
  interactions are the parts that genuinely need a device or emulator and are therefore
  reported as not executed rather than assumed.

**Tests (6 new).** `Stage33JourneyValidationTest` replays scenarios 6–11 in order against the
real `SubmissionRegistry` over a real on-disk document (reopened to prove persistence), and
renders the real `SubmissionHistoryContent` at a narrow phone width (`w320dp-h640dp-hdpi`) to
prove the pending counter and the "nothing is ever sent" promise reach the screen. Real
sending stays impossible throughout (`SENT` unreachable; a forced real send is
`BLOCKED_INCOMPATIBLE`). Suite **441 tests, 0 failures, 2 skipped** (the opt-in `LIVE_PROBE=1`
probes). `lintDebug` (0 errors, 52 pre-existing warnings + 1 info), `assembleDebug` and
`assembleRelease` all succeed.

**Artifacts.** Debug APK `app/build/outputs/apk/debug/app-debug.apk`
(30,232,921 bytes, sha256 `d5d875c1…5c177`), release APK
`app/build/outputs/apk/release/app-release-unsigned.apk` (8,387,761 bytes, sha256
`da354d4e…ecb005`). The release APK is unsigned; install it with `adb install` only after
signing (`apksigner`), and the debug one with `adb install -r app-debug.apk`.

## Stage 34 — audit réel des API de recherche d'emploi et de la qualité des offres

**Objet.** Vérifier, preuves à l'appui, une seule question : l'application peut-elle réellement
trouver des offres accessibles à un candidat qui postule **depuis Madagascar**, dans les métiers
Mechanical Technician, Maintenance Technician, Pump Technician, Industrial Mechanic, Mechanical
Maintenance Technician, Mining Maintenance Technician (et métiers industriels voisins) ? Aucun
changement fonctionnel n'est apporté : cet audit constate, et ne corrige qu'un seul défaut de
sécurité latent (voir plus bas).

**Méthode (preuves réelles, aucune simulation).** Deux sondes live exécutées par le propre code de
l'application, activées par `LIVE_PROBE=1`, sans aucun secret :

- `Stage14LiveProbeTest` a interrogé le **jeu par défaut** (Arbeitnow, Remotive, Jobicy,
  Himalayas ; Adzuna non configuré). Résultat : **144 offres brutes, 144 uniques**, mais
  `score>=60 = 0` et **0** titre contenant `maintenance`, `mechanic`, `technician`, `pump`,
  `fitter`, `millwright` ou `mining`. Meilleur score = 49 % (des postes logiciels/vente :
  « Enterprise Account Director », « Senior Product Designer »…). Les 4 boards par défaut ne
  ramènent donc **aucun** métier de maintenance.
- Une sonde temporaire (créée puis **supprimée**, jamais commitée) a interrogé le **jeu complet**
  réellement assemblé dans `LiveJobRepository` : EURES (300), Workday (72), Greenhouse (108),
  Ashby (360), Lever (3). Résultat : **843 bruts → 259 uniques → 58 offres de la famille
  maintenance**, dont GE Vernova (« Maintenance Technician - Electrical », « Industrial
  Mechanical Technician »), SSR Mining (« Mine Maintenance Mechanic », « Superintendent,
  Maintenance »), Rio Tinto (« Fixed Plant Maintenance Inspector »), Form Energy (« Equipment
  Maintenance Technician »), Alcoa, Sila (« HVAC Maintenance Technician »). Éligibilité :
  `ELIGIBLE=69`, `VISA_SPONSORED=1`, `UNCLEAR=189`.

**Conclusion — l'application trouve bien de vraies offres du métier.** Oui, à condition que
l'utilisateur active EURES et les boards employeurs (Workday/Greenhouse/Ashby/Lever), qui sont
désactivés ou vides par défaut. Le pipeline réel (providers → filtre de pertinence
`RoleClassifier` → `MatchingEngine`/`EligibilityEngine`) fonctionne : il a produit 58 offres
industrielles réelles avec titres, employeurs, pays et URL source.

**Qualité des offres (salaires, visa, relocalisation).**
- Les boards employeurs (Workday/Greenhouse/Ashby/Lever) **ne publient pas de salaire** :
  `salary=0-0` sur toutes les offres du jeu complet. La qualité « salaire » est donc nulle pour
  ces sources, alors que Jobicy/Remotive/Himalayas/Adzuna la fournissent quand elle existe.
- Aucune offre des 6 métiers cibles n'a de **sponsorship de visa confirmé** (`visa=NOT_MENTIONED`
  partout dans la famille maintenance ; la seule `VISA_SPONSORED` du tirage était un poste ML).
  GE Vernova affiche `RELOCATION_PACKAGE` — c'est une **aide à la relocalisation**, pas une
  preuve de sponsoring de visa. Le garde-fou `ExpatBenefitExtractor` (phrases documentées vs
  heuristique, refus explicites) tient correctement : « none documented » n'est jamais présenté
  comme un refus, et « Not stated » n'est jamais présenté comme un accord.
- Conséquence pratique : les meilleures pistes (GE Vernova, SSR Mining, Rio Tinto) sont
  accessibles en principe, mais **la question « visa » reste à vérifier par le candidat** — ce
  que l'application signale déjà honnêtement (`NEEDS_VERIFICATION`), au lieu de fabriquer une
  promesse.

**Défaut latent corrigé (sécurité, additif).** La clé Jooble voyage dans le **chemin** de l'URL
(`https://jooble.org/api/<clé>`), et `HttpJsonClient.redactUrl` ne masquait que les paramètres de
requête. Un échec Jooble produisait donc un `IOException("HTTP … for <url>")` portant la clé en
clair ; par `failureDiagnostic(...)` ce message est copié dans `ProviderDiagnostic.message`, qui
alimente le panneau Diagnostics de l'écran Sources. La rédaction couvre désormais aussi un jeton
final de 24 caractères alphanumériques (forme d'une clé Jooble), sans toucher aux identifiants
normaux. Le garde-fou est verrouillé par `Stage34ApiAuditTest` (6 tests : clé en chemin masquée,
avec ou sans query, clé Adzuna en query toujours masquée, segments courts préservés, identifiants
Lever/Workday hyphenés préservés, URL simple intacte).

**Comportements vérifiés comme intentionnels (non modifiés).** Un lieu « Ville, région » (par
exemple « Weymouth, MA », « Weirton, WV ») se résout en pays **non résolu** (`📍`), pas en
« United States ». C'est délibéré et épinglé par `Stage24IndustrialSourceTest` (« the catalog must
not invent one ») : le catalogue ne devine jamais un pays. L'audit **ne touche pas** à ce
comportement. À noter pour le candidat : une offre industrielle américaine « Ville, ST » s'affiche
sans drapeau pays, ce qui est plus honnête qu'un pays inventé.

**Défauts reconstatés, non introduits par ce stage.**
- Jooble non authentifié renvoie **403 Cloudflare (HTML)** : classé `AUTHENTICATION`/erreur, jamais
  interprété comme « aucun poste » (`Stage17ApiConfigTest`).
- Effet de bord du garde-fou de lieu : le message d'erreur Jooble (URL avec clé) reste affiché en
  clair sur l'écran Diagnostics, la rédaction n'intervenant que dans le message de l'exception
  HTTP. Non corrigé ici (éviter un changement d'UI hors périmètre) — déjà noté.

**Rôle des 6 métiers demandés.** `TargetSearch.roles` couvre l'ensemble : les métiers demandés se
ramènent aux rôles canoniques via `searchPhrases`/`titleSignals` (« maintenance technician »,
« mechanical technician », « pump », « fitter », « mine maintenance »…). Limite honnête :
`keywordPlan(profileTitles, max=6)` envoie au plus 6 requêtes par rafraîchissement, donc les rôles
au-delà du 6ᵉ dans la liste ordonnée ne sont pas interrogés lors de ce passage.

**Vérification.** Suite **447 tests, 0 échec, 2 skipped** (+6 : `Stage34ApiAuditTest`). `lintDebug`
0 erreur, **52 warnings + 1 info** (inchangé). `assembleDebug` et `assembleRelease` réussissent.
Sonde temporaire supprimée ; seuls `HttpJsonClient.kt` (rédaction) et `Stage34ApiAuditTest.kt`
sont ajoutés.

## Stage 35 — découverte ciblée depuis les boards employeurs

**Objet.** Stage 34 a montré, preuves à l'appui, que les boards employeurs (Workday, Greenhouse,
Ashby, Lever) portent bien les métiers de maintenance du candidat, mais que **Greenhouse n'en
remontait aucun** lors de la sonde complète. Ce stage corrige la cause, sans élargir le périmètre :
le seul défaut de *récupération* mis en évidence par l'audit.

**Cause (structurelle, pas un manque de données).** L'API liste de Greenhouse n'a **pas** de
recherche serveur ; `GreenhouseProvider` lit donc la liste puis consacre un budget borné
(`DETAILS_PER_BOARD = 6`) à des appels détail concurrents, car seule la fiche détail porte le
pays et le texte d'offre dont `MatchingEngine` a besoin. Ce budget était dépensé sur les **6
premières lignes** de la liste. Or les métiers d'un industriel sont **profonds** dans son board :
« Maintenance Technician » est très au-delà des premières lignes, tandis que les premières lignes
sont des stages et des postes de labo/conception. Conséquence : les métiers n'étaient jamais lus et
le board *paraissait* dépourvu de postes du métier.

**Correctif (local, borné, sans invention).** `GreenhouseProvider.pickDetailIds` choisit désormais
les lignes à lire en priorisant les titres de la famille maintenance, via le **même
`RoleClassifier`** que le reste du pipeline (source unique de vérité sur la pertinence), puis
complète le budget avec les lignes les plus récentes restantes. Le nombre de requêtes est
**inchangé** ; seul l'ordre change. Quand aucun titre n'est un métier, le résultat est la liste
« plus récentes d'abord » d'origine — donc le comportement ailleurs est strictement préservé.
Aucun board ajouté, aucun `DEFAULT_KEYWORDS` modifié, aucun fournisseur touché (Ashby/Lever
filtrent déjà localement ; Workday/EURES cherchent déjà côté serveur).

**Limite honnête.** Le budget détail reste borné à 6/board : si un board publiait plus de 6 titres
pertinents, certains ne seraient pas lus lors de ce passage. C'est un compromis assumé contre un
téléchargement multi-mégaoctets à chaque rafraîchissement (le fichier le documente déjà).

**Vérification.** Suite **452 tests, 0 échec, 2 skipped** (+5 : `Stage35TargetedDiscoveryTest` :
métiers enfouis réellement lus, budget borné à 6 appels inchangé, board sans métier → repli
« plus récentes d'abord » identique, curation Greenhouse bien formée). `lintDebug` **0 erreur,
52 warnings** (inchangé). `assembleDebug` réussit. Seuls `GreenhouseProvider.kt` et
`Stage35TargetedDiscoveryTest.kt` sont modifiés/ajoutés.

## Stage 36 — nouvelles sources gratuites ciblées + tri « Expat fit » vérifiable

**Objet.** Trouver de **nouvelles sources gratuites** qui exposent réellement les métiers visés
(maintenance industrielle, mécanique, pompes, mines) pour un candidat appliquant **depuis
Madagascar**, dans les géographies prioritaires (Arabie saoudite, EAU, Qatar, Oman, Koweït,
Afrique du Sud, Botswana, Namibie, Zambie). Deux livrables : élargir la curation de boards
employeurs, et rendre l'ordre d'affichage **reproductible sur des faits enregistrés**.

**Reconnaissance (APIs gratuites, mesurée en direct).** Aucune source unique « Gulf + Afrique »
n'existe gratuitement :

| Source | Verdict |
| --- | --- |
| Careerjet (`public.api.careerjet.net`) | **Non viable** : exige un `Referer` déclaré (403 « Undeclared referrer ») et n'écoute qu'en HTTP en clair. |
| Adzuna | Clé gratuite, mais **16–20 pays, aucun pays du Golfe** ; dans la région, seule l'Afrique du Sud. |
| The Muse / ReliefWeb v2 | **Fermés** : appname/Referer approuvé obligatoire (403). |
| Findwork | Clé requise (401). |
| SmartRecruiters | Sans clé, recherche serveur `q=` et filtre `country=`. **Écarté en Stage 36 sur un échantillon trop étroit** (Bosch seul). **Réexaminé et retenu en Stage 43** : la curation SGS/Wabtec/Konecranes remonte de vrais postes de métier en Arabie saoudite, EAU, Mozambique, Afrique du Sud et Kenya (voir Stage 43). |
| Workday / Lever / Greenhouse / Ashby | **Sans clé**, le poste est celui de l'employeur (le plus fiable). |
| GulfTalent, Naukrigulf, Bayt, agences de main-d'œuvre | Publient bien les postes du Golfe mais **sans API gratuite** (pages web seules). |

**Décision.** Étendre la curation **Workday** (fournisseur déjà en place, format `RawJob`
traduction-seule préservé) plutôt qu'ajouter un fournisseur à faible rendement. Sites **vérifiés
en direct** via le CXS public (recherche serveur + appel détail qui porte le pays réel) :

- `weir` (Weir Group, pompes/équipements miniers) — remonte « Millwright », « Shift Leader:
  Maintenance » (Port Elizabeth) ;
- `sulzer` (Sulzer, pompes/équipement tournant) — remonte « Gas Turbine Fitter » (Abu Dhabi) ;
- `epsa` (EPSA, terrassement minier) — remonte « Maintenance Engineer (Madinah) », « Heavy
  Equipment Mechanic (Turaif) », de nombreux postes de mine (Mashonaland West) ;
- `firstquantum` (First Quantum Minerals, cuivre Zambie) — remonte « Artisan Fitter », « Artisan
  Boilermaker », « Artisan, Electrician » (Solwezi/Kalumbila).

**Preuve avant/après.** Sur les 8 métiers cibles envoyés aux boards, 4 sites existants remontent
~128 titres de métier ; les 4 sites ajoutés en remontent ~186, dont les titres exacts du candidat
(fitter, artisan, field service technician) et des localisations Golfe/Afrique. Le détail est bien
lu (pays, description, URL de candidature employeur) : First Quantum → Zambie, EPSA → Côte
d'Ivoire, Sulzer → Émirats, Weir → États-Unis.

**Tri « Expat fit » (vérifiable, pas une intuition).** Nouveau `JobSort.EXPAT_FIT` et nouvelle
propriété dérivée `MadagascarEligibility.rank` (ELIGIBLE 3 > VISA_SPONSORED 2 > UNCLEAR 1 >
RESTRICTED 0). L'ordre compare, dans cet ordre, des **faits enregistrés** : score Expat Benefits
documenté, puis verdict d'éligibilité, puis sponsorisation de visa confirmée (preuve), puis score
CV, puis date. Le tri s'affiche automatiquement dans les puces « Sort by » existantes (le rendu
itérait déjà `JobSort.entries`).

**Limite honnête.** Les boards Gulf/Afrique locaux sans API restent hors portée ; leur accès
nécessiterait scraping (hors des règles) ou une clé commerciale. Le rendement Golfe des boards
employeurs reste faible côté sièges (peu de postes dans ces pays), mais les postes de **chantier**
(Abu Dhabi, Turaif, Madinah, Solwezi) — précisément ceux du candidat — y sont bien présents.

**Vérification.** Suite **461 tests, 0 échec, 0 erreur, 2 skipped** (+9 : `Stage36TargetedDiscoveryTest` :
curation pompe/Afrique présente et sans doublon, pays Golfe et pays africain résolus depuis l'appel
détail, tri Expat fit par bénéfices puis éligibilité/sponsorisation puis score CV, rang d'éligibilité).
`lintDebug` **0 erreur, 52 warnings** (inchangé). `assembleDebug` et `assembleRelease` réussissent.
Fichiers modifiés/ajoutés : `WorkdayProvider.kt`, `ViewModels.kt`, `Job.kt`,
`Stage36TargetedDiscoveryTest.kt`.

## Stage 37 — vérifier que l'offre renvoyée est actuelle, pertinente et « applicable »

**Objet.** Stage 36 a élargi la curation de boards. Cette étape vérifie, preuves à l'appui, que
l'offre qui en sort est **réellement exploitable** par un candidat appliquant depuis Madagascar :
qu'elle est **actuelle** (pas un poste fermé présenté comme ouvert), **pertinente** pour le CV
(métier du candidat, pas seulement un employeur industriel) et **applicable** (pays résolu pour
l'éligibilité, lien de candidature employeur). La méthode est un audit live des champs que le
fournisseur Workday **jetait**.

**Reconnaissance (champs Workday mesurés en direct).** Sur Weir/Sulzer/EPSA/First Quantum, l'appel
détail (`/wday/cxs/{tenant}/{site}{externalPath}`) porte des champs non lus :

| Champ | Mesure live | Décision |
| --- | --- | --- |
| `startDate` | présent **100 %** (75/75), suit `postedOn` (« Posted Today » → date du jour) | conservé → `postedAtEpochMillis` ; la fraîcheur reste « date non indiquée » si absent |
| `canApply` | booléen présent, `true` sur 315/315 postings ouverts scannés | lu → `closedToNewApplicants` ; `false` ⇒ poste **fermé** |
| `externalUrl` + `country` | résolus | déjà utilisés (lien + pays) |

**Décisions.**

1. **Fermé ≠ ouvert.** Nouveau fait `RawJob.closedToNewApplicants` (défaut `false`, donc les autres
   fournisseurs sont inchangés), posé par `WorkdayProvider` **uniquement** quand la source dit
   `canApply = false`. Un champ **absent** vaut « non déclaré », jamais « fermé ». Le
   `RecordValidationEngine` rejette ces postings avec une raison dédiée et **comptée**
   (`CLOSED_TO_APPLICANTS`), donc jamais affichés comme ouverts. Persisté dans `JobCache`.
2. **Pays manquants (trouvé par la sonde live).** EPSA publie des postes de mine en **Côte
   d'Ivoire** et au **Zimbabwe**, First Quantum au **Panama** ; ces pays n'étaient pas dans
   `CountryCatalog`, donc l'éligibilité ne pouvait pas être jugée. Ajoutés : Zimbabwe 🇿🇼, Côte
   d'Ivoire 🇨🇮 (alias FR `côte d'ivoire` + EN `ivory coast` + `abidjan`), Panama 🇵🇦, et l'alias
   Workday « United States of America ». La fraîcheur et la pertinence n'ont **pas** été touchées.

**Preuve avant/après (sonde live, `Stage37LiveProbeTest`).** Avant : « 4601 -Yaoure Gold Mine 2,
Côte d'Ivoire (known=false) » et « Cobre Panama (known=false) ». Après : `🇨🇮 Côte d'Ivoire
(known=true)`, `🇵🇦 Panama (known=true)` ; **0 postings sans date**, **0 fermés laissés** ;
« structural checks passed for 11 kept postings ». Le meilleur résultat reste « 68 % Maintenance
Technician — Weir Group 🇺🇸 » (TARGET), avec EPSA Arabie saoudite 🇸🇦 en MAINTENANCE_FAMILY.

**Limite honnête.** Aucun `canApply = false` n'a été observé sur les boards scannés le jour de la
mesure : le filtre est correct et testé, mais son effet réel est aujourd'hui nul (les boards
retirent vite les postes fermés). `startDate` reste la date de publication, pas une date limite de
candidature. Le rendement Golfe/Afrique dépend du moment de la mesure.

**Vérification.** Suite **472 tests, 0 échec, 0 erreur, 3 skipped** (+11 : `Stage37OfferVerificationTest`
10 tests + `Stage37LiveProbeTest` 1 sonde ignorée sans `LIVE_PROBE=1`). `lintDebug` **0 erreur,
52 warnings** (inchangé). `assembleDebug` réussit. Nouveaux fichiers :
`Stage37OfferVerificationTest.kt` (10 tests : date lue et classée récente, absence de date ⇒ jamais
« frais », fermé rejeté et compté, `canApply` lu, `canApply` absent ⇒ non fermé, métier du candidat
reconnu, professions différentes écartées, pays + drapeau + lien + employeur présents, Golfe résolu,
pays Afrique/Golfe ajoutés reconnus) et `Stage37LiveProbeTest.kt` (sonde `LIVE_PROBE=1`, ignorée par
défaut). Fichiers modifiés : `JobProvider.kt` (`RawJob`), `WorkdayProvider.kt`, `JobCache.kt`,
`RecordValidationEngine.kt`, `CountryCatalog.kt`.

## Stage 38 — lecture IA optionnelle du CV (extraction structurée + repli honnête)

**Objet.** Stage 29 rendait l'extraction honnête (partielle, comptée page par page) mais restait
**purement heuristique** : le profil affiché était celui des regex locales. Cette étape branche
enfin l'analyseur IA prévu depuis Stage 16 sur l'import de CV : quand l'utilisateur a **sauvegardé
une clé NVIDIA *et* activé l'assistant**, le texte du CV est envoyé au modèle qui renvoie un profil
structuré ; sinon l'analyse reste **sur l'appareil** et le dit clairement.

**Décisions.**

1. **Un seul chemin, deux moteurs.** Nouveau `data/cv/CvAiAnalyzer.kt` implémente l'interface
   `CvAnalyzer` existante. Il exécute **d'abord** `HeuristicCvAnalyzer` (regex de contact
   fiables), puis, si `NvidiaConfig.isReady` (clé + consentement), envoie le CV et **fusionne**
   le résultat du modèle par-dessus via `CvProfileMerger` (les corrections manuelles de
   l'utilisateur gagnent toujours). Le conteneur (`AiJobFinderApp`) fournit désormais
   `CvAiAnalyzer { nvidiaConfigValue }` à la place de `HeuristicCvAnalyzer()`.
2. **Le modèle n'invente pas.** Le prompt impose un schéma JSON fixe et `null` pour tout champ
   absent ; le parseur **écarte** (jamais ne devine) ce qui ne correspond pas. Un `year` en
   chaîne non numérique est jeté, pas transformé en `0`. Les lecteurs typés (`intOrNull`,
   `optBooleanOrNull`) n'acceptent qu'un nombre, une chaîne numérique, ou un booléen.
3. **Il y a toujours une réponse cohérente.** Consentement absent, clé absente, réseau en échec,
   réponse illisible ⇒ le résultat heuristique est renvoyé, **étiqueté** `CvAiStatus`
   (`CONSENT_REQUIRED` / `NOT_CONFIGURED` / `FAILED`) + une ligne d'explication. Le CV **ne
   quitte pas l'appareil** tant que `isReady` est faux (même verrou que la rédaction assistée).
4. **Les CV longs sont découpés.** `chunk()` coupe sur les lignes vides (≤ 12 000 caractères),
   une requête par morceau, union des extractions — pour ne pas dépasser la fenêtre du modèle.

**UI.** Nouvelle carte `AiAnalysisCard` dans `CvScreen` : « Read with the AI assistant » (vert)
quand l'IA a servi, « The AI assistant is off » / « Add your NVIDIA key to use AI » (actionnable
vers Profile → AI assistant) sinon, « The AI could not be reached » en cas d'échec. Le héros
adapte son sous-titre (« Read by the AI assistant and verified on your device »).

**Limite honnête.** Aucun appel NVIDIA live n'est fait depuis la suite : tout passe par la couture
`complete` de `CvAiAnalyzer` avec des réponses figées. Le comportement réel du modèle sur le CV de
l'utilisateur (le fichier 1,9 Mo) **n'est pas** prouvé ici — seuls le prompt envoyé, le parseur et
le repli le sont. Le consentement est obligatoire : sans lui, **zéro** requête (test explicite).

**Vérification.** Suite **487 tests, 0 échec, 0 erreur, 3 skipped** (+15 : `Stage38CvAiAnalysisTest`).
`lintDebug` **0 erreur, 52 warnings** (inchangé). `assembleDebug` réussit. Nouveaux fichiers :
`data/cv/CvAiAnalyzer.kt` et `Stage38CvAiAnalysisTest.kt` (15 tests : rien n'est envoyé sans
consentement ni clé, le modèle lit le CV et ses champs gagnent, réponse sous ```json``` acceptée,
échec réseau/reponse illisible ⇒ repli on-device, `{}` ⇒ aucun champ inventé, année mal typée
jetée, pas de JSON ⇒ pas de profil, découpage des CV longs, aller-retour `aiStatus` en persistance,
et le trajet complet dépôt → analyseur IA). Fichiers modifiés : `CvAnalyzer.kt` (`CvAnalysisResult.aiStatus`),
`CandidateProfile.kt` (`CvAiStatus`, `CvRecord.aiStatus`), `CvRepository.kt` (persistance `aiStatus`,
helpers `recordToJson`/`cvRecordFromJson` rendus `internal`), `AiJobFinderApp.kt` (câblage),
`ui/screens/cv/CvScreen.kt` (`AiAnalysisCard`).

## Stage 40 — les notes du CV décrivent le profil réellement affiché

**Symptôme (appareil réel).** Un CV qui ne rendait que peu de texte produisait malgré tout un
profil riche (formation, langues) **et**, en même temps, des notes affirmant que la formation, les
langues, le nom, l'e-mail et le téléphone étaient tous absents. Les notes contredisaient donc le
profil montré juste à côté.

**Cause.** `CvAiAnalyzer`, sur le chemin IA, construisait ses notes à partir du profil
**heuristique seul** (`heuristic.warnings + extraWarnings(profile)`), jamais à partir du profil
fusionné que l'utilisateur voit. Un champ récupéré par le modèle (formation, langues) restait donc
signalé comme manquant. Un second défaut : quand l'IA échoue, l'enregistrement gardait l'étiquette
« AI analysis (NVIDIA) » alors que le profil affiché venait de l'analyseur on-device.

**Correctif.** Les notes ont **une seule source**, `data/cv/CvAnalysisWarnings.kt`
(`warningsFor(profile, presence)`), dérivée du profil qui sera affiché. Les deux analyseurs y
délèguent : si une section est présente, aucune note ne la dit absente. `presence`
(`CvSectionPresence`, sections réellement portées par le document) ne change que la **formulation**
(« section trouvée mais illisible » vs « aucune section »), jamais la présence d'une note. Le
résultat porte aussi l'analyseur **effectif** (`effectiveEngineId/Label`) : en repli, le dépôt
nomme l'analyseur on-device, pas l'IA qui n'a rien produit.

**Comptage honnête.** `CvRecord.extractedNonWhitespaceCharacters` est persisté et affiché
(« Characters read ») : `extractedCharacters` comptait tout le tampon (sauts de page/lignes), donc
un PDF multipage lisait beaucoup plus grand que le texte visible. Ancien enregistrement sans le
champ ⇒ repli sur l'ancien compteur.

**Extraction.** `PdfCvParser.recoverPageText` ne rend jamais **moins** de texte que l'ordre par
défaut de PDFBox : la passe XY-cut reste préférée, mais une page pathologique retombe sur le
stripper par défaut (la passe par défaut n'est calculée que si la passe layout semble maigre).

**Vérification.** Suite **500 tests, 0 échec** (+13 : `Stage40CvConsistencyTest`). Le probe
temporaire qui reproduisait la contradiction a été supprimé. Nouveaux fichiers :
`data/cv/CvAnalysisWarnings.kt` et `Stage40CvConsistencyTest.kt`. Modifiés : `CvAnalyzer.kt`
(`presence`, `effectiveEngine*`, délégation des notes), `CvAiAnalyzer.kt` (notes sur le profil
fusionné, repli étiqueté on-device), `CandidateProfile.kt` (compteur non-blanc), `CvRepository.kt`
(compteur + moteur effectif), `PdfCvParser.kt` (garde-fou d'extraction), `ui/screens/cv/CvScreen.kt`
(« Characters read »), `CvScreenTest.kt` (libellé).

## Stage 41 — un « fit international » vérifiable par annonce

**Besoin.** L'app tenait déjà trois nombres distincts : le score de correspondance CV
(`Job.matchScore`), le verdict d'éligibilité Madagascar (`MadagascarEligibility`) et le score
documenté des avantages expat (`ExpatBenefitsAssessment`). Aucun ne répond seul à la question du
candidat basé à Madagascar : « cette offre vaut-elle mon temps, et où en suis-je exactement ? ».

**Ajout.** Un quatrième indicateur, `InternationalFitAssessment`, replie la destination, la route
depuis Madagascar, la langue et les faits de diplôme/expérience en un seul nombre **explicable**,
tout en laissant le score d'avantages expat **visible à côté**, jamais fondu dedans.

**Moteur.** `data/jobs/engine/InternationalFitEngine.kt` calcule une somme pondérée de six
composants, chacun plafonné : route depuis Madagascar 0..35, alignement de métier 0..20,
recouvrement de compétences 0..20 (pondéré par `RoleFamily`, comme le score de correspondance),
expérience 0..10, langue 0..10, destination 0..5. Trois règles d'honnêteté, identiques au reste du
pipeline :

1. **Le silence est neutre, pas négatif.** Une exigence non mentionnée ne gagne ni ne perd ses
   points, et le composant est marqué `EvidenceStrength.UNKNOWN` (« non indiqué »), jamais un refus.
2. **Rien n'est inventé.** Métier et compétences viennent de la correspondance existante ;
   route, langue et destination viennent de faits enregistrés sur `Job`.
3. **Un profil trop maigre ⇒ `InternationalFitBand.INSUFFICIENT`**, pas un score bas confiant :
   le nombre n'est montré que s'il y a un vrai profil à confronter.

Le total est la somme exacte de ses composants (`score == components.sumOf { it.points }`) et
`fromScore` ne renvoie **jamais** `INSUFFICIENT` (réservé à « je ne peux pas juger »).

**UI.** `InternationalFitBadge` (badge, bande + score) réutilise les jetons existants ; la carte
`JobCard` l'affiche à côté du badge d'avantages expat ; la fiche de détail
(`JobExplanationSheet`) ouvre une section « Your international fit » listant chaque composant
(points/max + force de preuve), le résumé de route, puis les blocages (« Before you apply »). Un
filtre « Strong fit » (`strongInternationalFitOnly`) et un tri `JobSort.INTERNATIONAL_FIT`
exposent le classement. Aucun redesign : mêmes jetons, clair + sombre.

**Câblage.** `JobsViewModel` prend un flux `candidateProfile` (transmis par
`JobsViewModelFactory` depuis `AiJobFinderRoot.kt`) et calcule la carte dans le `combine` existant ;
`JobsUiState` expose la carte, l'accesseur `internationalFitOf(job)` (état vide honnête) et le
filtre. `clearFilters()` réinitialise le nouveau filtre.

**Vérification.** Suite **516 tests, 0 échec** (+16 : `Stage41InternationalFitTest`, moteur +
état). Lint **0 erreur / 52 avertissements** (inchangé). Nouveaux fichiers :
`data/model/InternationalFit.kt`, `data/jobs/engine/InternationalFitEngine.kt`,
`Stage41InternationalFitTest.kt`. Modifiés : `ViewModels.kt` (état, filtre, tri, usine),
`AiJobFinderRoot.kt` (profil au factory), `ui/components/Badges.kt` + `JobCard.kt` (badge),
`ui/screens/jobs/JobsScreen.kt` (fiche de détail, filtre, câblage).

## Stage 42 — une lecture vérifiable « cette offre est-elle encore réelle et joignable ? »

**Besoin.** L'app tenait déjà quatre nombres distincts : correspondance CV, éligibilité
Madagascar, avantages expat documentés et fit international. Aucun ne répond à la question que le
candidat doit régler **avant** de dépenser son temps : « cette annonce est-elle toujours ouverte,
le lien est-il utilisable, y a-t-il une route visa documentée, et de quand date la vérification ? ».

**Ajout.** Un cinquième objet, `OfferVerification`, répond à ces quatre questions **séparément**,
chaque axe avec son propre état. Il reste à part du match, de l'éligibilité, de l'expat et du fit,
donc jamais fondu dedans.

**Moteur.** `data/jobs/engine/OfferVerificationEngine.kt` (pur, horloge injectée) produit un
`OfferVerification` avec quatre axes + une fraîcheur, chacun `VerificationState` :

1. **Activité.** Seul le drapeau de la source confirme qu'un poste est ouvert (`canApply = true`
   → `CONFIRMED`). Absent ⇒ `UNKNOWN`, **jamais** « fermé » : l'âge n'est pas une preuve de
   fermeture. `canApply = false` ⇒ `EXPIRED`.
2. **Lien.** Délégué à `ApplicationUrlInspector` : lien absent/invalide ⇒ `INACCESSIBLE`, lien
   réel mais avec réserve (http, domaine gratuit…) ⇒ `POSSIBLE`, sinon `CONFIRMED`.
3. **Visa.** Preuve documentée de sponsoring ⇒ `CONFIRMED` ; « peut-être » ⇒ `POSSIBLE` ; refus
   explicite (éligibilité `RESTRICTED` ou avantage `NOT_OFFERED`) ⇒ `NOT_OFFERED` — un fait
   **distinct** de « non mentionné » (`UNKNOWN`).
4. **Relogement.** Axe séparé du visa, lu sur l'avantage `RELOCATION_SUPPORT`.
5. **Fraîcheur.** L'âge de la dernière récupération est classé : ≤3 j ⇒ `CONFIRMED`, ≤21 j ⇒
   `POSSIBLE`, au-delà ⇒ `UNKNOWN` (une vérification ancienne n'est pas une preuve de mort).

Trois règles d'honnêteté, identiques au reste du pipeline : le silence est `UNKNOWN` (jamais
négatif), rien n'est inventé (les faits sont lus, jamais devinés), et `isActionable` n'est faux
que sur **preuve positive** d'impasse (fermé, ou lien inutilisable).

**UI.** `OfferVerificationBadge` réutilise l'échelle couleur existante
(succès/info/avertissement/danger) et un « unknown » volontairement neutre ; la carte `JobCard`
l'affiche seulement quand la lecture n'est pas entièrement vide. La fiche de détail
(`JobExplanationSheet`) ouvre une section « Verified before you apply » listant chaque fait avec
son icône et sa force de preuve, puis la note « Always confirm the details on the employer's own
page before you apply. » Nouveaux filtres : « Open now », « Visa verified », « Housing verified »,
« Flights verified », « Recently checked ».

**Tri.** Dans `expatFitOrder` et `internationalFitOrder`, une offre à impasse connue est
**rétrogradée** sous toute offre vivante, quel que soit son score ; « inconnu » reste neutre et
n'est jamais traité comme mort.

**Câblage.** `RawJob.openToApplicants` (défaut `null`) est posé par `WorkdayProvider` uniquement
sur `canApply = true`, persisté dans `JobCache`, transporté jusqu'à `Job.openToApplicants`.
`JobMapper` construit `Job` puis lui attache la vérification ; `SampleJobRepository` exécute le
même moteur sur la fixture, donc le fixture et le pipeline live ne peuvent pas diverger.

**Vérification.** Suite **531 tests, 0 échec, 0 erreur, 3 ignorés** (+15 :
`Stage42OfferVerificationTest`). Lint **0 erreur / 52 avertissements** (inchangé). `assembleDebug`
réussit. Nouveaux fichiers : `data/model/OfferVerification.kt`,
`data/jobs/engine/OfferVerificationEngine.kt`, `Stage42OfferVerificationTest.kt`. Modifiés :
`Job.kt`, `JobMapper.kt`, `SampleJobRepository.kt`, `ViewModels.kt`, `Badges.kt`, `JobCard.kt`,
`JobsScreen.kt`, `JobProvider.kt`, `WorkdayProvider.kt`, `JobCache.kt`.


## Stage 43 — plus d'offres réelles et pertinentes via les boards SmartRecruiters

**Objet.** Augmenter **la quantité ET la pertinence** des offres internationales réelles pour un
candidat malgache à profil technique (maintenance industrielle/mécanique, réparation de pompes et
de garnitures mécaniques, réparation de composants, dépannage d'équipement industriel), sans
nouvelle clé et sans toucher à l'architecture ni à l'UI.

**Pourquoi SmartRecruiters, réexaminé.** Stage 36 avait écarté cette source sur un échantillon
réduit à **Bosch** (« couverture Golfe/Afrique négligeable »). L'audit Stage 43 a re-mesuré
l'API publique **sans clé** sur des employeurs d'inspection et d'équipement industriel, qui
publient précisément les métiers visés :

| Board (sans clé) | Postes métier visibles en Afrique / Golfe |
| --- | --- |
| **SGS** | Arabie saoudite (« Rotating Equipment Inspector », « Sr. Mechanical Inspectors », « NDT Technician »), EAU (« NDT Technician » Abu Dhabi), Mozambique (Maputo/Nacala), Afrique du Sud, Kenya (Mombasa « Calibration Technician ») |
| **Wabtec** | Afrique du Sud (Pretoria « Repair Technician », Rustenburg « Control Room Operator ») |
| **Konecranes** | Afrique du Sud (Durban « Service Technician ») |
| **Bosch** | Australie (« Mechanical Fitter – Hydraulic Power Units », « Field Service Technician – Hydraulics ») |

Contrairement à Stage 36, la valeur ne vient donc **pas** de la couverture globale de Bosch mais de
la curation d'employeurs de services industriels à sites africains et du Golfe.

**Ajout.** Nouveau fournisseur `SmartRecruitersProvider` (`data/jobs/provider/`), `ProviderKind.
EMPLOYER_BOARD`, **sans clé**, `capabilities.serverSideKeyword = true` (l'API accepte `q=` et
filtre réellement côté serveur). Curation : `SGS`, `Wabtec`, `Konecranes`, `BoschGroup` — chacune
vérifiée en direct. Le flux suit exactement le contrat des autres fournisseurs employeur
(Workday/Greenhouse) :

1. **Recherche serveur.** Une requête par mot-clé cible (`q=`), page de 100 lignes.
2. **Détails bornés.** La ligne de liste porte le titre et la localisation mais **pas** la
   description ; un budget de **4 appels détail concurrents par board** lit le texte du poste, le
   pays réel et l'URL. Le budget est **ciblé métier d'abord** via le même `RoleClassifier` que le
   reste du pipeline (les titres de métier sont lus avant le reste), exactement comme Greenhouse.
3. **Cartographie honnête.** `active = false` ⇒ le poste est écarté (l'employeur a retiré
   l'annonce). `descriptionHtml` = concaténation des quatre sections réelles
   (`jobDescription`, `qualifications`, `additionalInformation`, `companyDescription`).
   `location` = `fullLocation` publiée. `company` = nom réel de l'employeur (repli sur le label).
   `externalId` = `identifier-id` (déduplication inter-sources déjà en place).

**Fidélité au contrat.** Aucune invention : un champ absent reste `null`, `isRemote` lit le
drapeau `remote` de la source, `postedAtEpochMillis` lit `releasedDate`. Un board dont le plan n'a
pas de flux public répond **vide**, jamais en erreur : c'est « rien publié », pas « source cassée ».
Si **tous** les boards échouent, le fournisseur lève une `JobProviderException` classée (comme les
autres) pour que l'UI distingue « 0 offre » de « source en panne ». Rien n'a été ajouté à l'UI,
aux thèmes, à la navigation ni aux moteurs de score : le nouveau poste traverse le pipeline
existant (validation, dédup, pertinence, éligibilité Madagascar, badges).

**Limite honnête.** Le budget détail est borné à **4/board** et une seule page de recherche est
lue par mot-clé : un board publiant plus de 4 titres pertinents n'en montre qu'une partie à ce
passage, et le classement `q=` de l'API est partiel. C'est un compromis assumé contre un
téléchargement multi-mégaoctets et contre la limite de débit par IP de l'API publique.

**Vérification.** Suite **541 tests, 0 échec, 0 erreur, 3 ignorés** (+10 : `Stage43SmartRecruiters
Test` : cartographie honnête, mot-clé réellement envoyé et encodé, titres de métier lus en premier
avec budget borné inchangé, ordre source préservé quand aucun titre n'est un métier, poste fermé
écarté, board vide ≠ source cassée, panne totale → erreur classée, curation bien formée). Lint
**0 erreur / 52 avertissements** (inchangé). `assembleDebug` réussit. Nouveaux fichiers :
`data/jobs/provider/SmartRecruitersProvider.kt`, `Stage43SmartRecruitersTest.kt`. Modifié :
`AiJobFinderApp.kt` (enregistrement du fournisseur), `AGENTS.md`.

## Étape 44 — Recherche incrémentale, seuil d'alerte et reprise honnête

**Objectif.** La veille en arrière-plan doit dire *ce qui est réellement nouveau*, laisser
l'utilisateur régler la barre de pertinence des alertes, et ne réessayer que lorsque cela peut
aider. Aucune invention : le statut affiché décrit ce qui s'est **réellement** passé.

**Recherche incrémentale.** `data/discovery/JobSeenLedger.kt` — un petit registre persistant
(un fichier JSON, écrit atomiquement, plafonné à 3 000 entrées) des annonces déjà vues. Une
annonce est identifiée par `providerId:externalId` et, à défaut, rapprochée par son **URL
canonique** (`CanonicalUrl.of`), donc la même offre arrivant d'un autre board n'est pas
« nouvelle ». `observe()` renvoie un `DiscoveryDelta` : `newJobIds` (jamais vues),
`updatedJobIds` (déjà connues mais **contenu modifié** — titre, employeur, lieu, salaire ou
longueur de description via une signature bon marché), `knownCount`, `firstRun`. **Le premier
passage enregistre une ligne de base en silence** (`notifyBaseline = false` par défaut) : une
installation neuve ne doit pas annoncer tout le marché d'un coup. Un passage sans résultat
exploitable **n'avance pas** le registre.

**Statut lisible.** `data/discovery/DiscoverySummary.kt` — assemble le diagnostic du
dépôt (`ProviderDiagnostics`) et le dernier `DiscoveryDelta` : dernière tentative, dernier
succès, combien de nouveau/mis à jour, sources en échec (avec catégorie), `showingCachedOnly`.
C'est ce que la carte du Profil affiche, en clair, sans ouvrir le tableau de diagnostic complet.

**Seuil d'alerte.** `AlertThreshold` (`data/refresh/RefreshPreferences.kt`) — la barre de
pertinence d'une alerte, avec les **mêmes** paliers de score que le reste de l'app
(Excellent 90 / Strong 75 / Good 60 / Fair 45), pour que « Strong » veuille dire la même chose
sur une carte d'offre et dans une alerte. Défaut conservateur : `STRONG`
(`JobNotificationPolicy.DEFAULT_MIN_MATCH = 75`), donc une installation neuve reste calme.
La préférence est stockée (`alert_min_match_score`) et transmise au coordinateur et au worker,
si bien qu'un changement prend effet au prochain passage.

**Reprise (retry).** `work/RefreshRetryPolicy.kt` — décide si WorkManager doit réessayer :
en ligne sans réseau → oui (la contrainte réseau retiendra la tâche) ; un passage ayant produit
**quoi que ce soit** d'utilisable → non (le cache contient déjà le dernier bon flux) ; tous les
fournisseurs en échec → oui **seulement** si l'échec est transitoire (réseau, délai, HTTP,
inconnu). Une clé absente, des identifiants refusés ou une source *rate-limitée* ne sont
**jamais** réessayés : cela ne se répare pas tout seul et userait la batterie.

**UI (Profil).** La carte « Automatic job discovery » montre désormais le statut d'exploration
(dernier succès, « N new / M updated », sources en échec), un bouton **Search now** (lancé et
attendu pour afficher la progression), et le **choix du seuil** quand les alertes sont
activées. Un appui sur une notification de correspondance **ouvre directement l'annonce**
(`MainActivity` lit `JobNotificationCenter.EXTRA_JOB_ID` et le transmet à la navigation).

**Vérification.** Suite **560 tests, 0 échec, 0 erreur, 3 ignorés** (+19 : `Stage44Refresh
DiscoveryTest` : ligne de base silencieuse, nouveau vs mis à jour, même offre via URL, résumé
honnête, reprise transitoire vs permanente vs rate-limitée ; `Stage20ProfileScreenTest` :
statut, seuil, « Search now »). `compileDebugKotlin`, `testDebugUnitTest` et `assembleDebug`
réussissent. Nouveaux fichiers : `data/discovery/JobSeenLedger.kt`,
`data/discovery/DiscoverySummary.kt`, `work/RefreshRetryPolicy.kt`,
`Stage44RefreshDiscoveryTest.kt`. Modifié : `RefreshPreferences.kt`,
`UserPreferencesRepository.kt`, `JobNotificationPolicy.kt`, `JobNotificationsCoordinator.kt`,
`JobRepository.kt`, `LiveJobRepository.kt`, `SampleJobRepository.kt`, `JobRefreshWorker.kt`,
`JobRefreshRunner.kt`, `AiJobFinderApp.kt`, `MainActivity.kt`, `AiJobFinderRoot.kt`,
`JobNotificationCenter.kt`, `ProfileScreen.kt`, `ViewModels.kt`, `Stage20DiscoveryTest.kt`,
`Stage20ProfileScreenTest.kt`, `AGENTS.md`.

## Étape 45 — « Nouvelles annonces » : filtre nouveau, revue et provenance

**Objectif.** La recherche incrémentale de l'étape 44 enregistrait déjà ce qui est nouveau,
mais l'interface ne le montrait pas. Cette étape expose le résultat honnête : un filtre
**New only**, un compteur, une action **Mark reviewed**, et la provenance d'une trouvaille.

**Registre (`JobSeenLedger`).** `NewPostings` remonte désormais `jobIds`, le `discoveredAtBy
JobId` (première vue, pour dater « Discovered … »), `reviewedAtEpochMillis` et
`baselineEstablished`. `newPostings()` ne renvoie que les annonces vues **strictement après**
la ligne de base **et** après la dernière revue ; `markReviewed(at)` avance le filigrane et
efface la liste. Une installation neuve (`baselineEstablished = false`) n'affiche donc rien :
le marché initial n'est jamais présenté comme nouveau.

**Dépôt.** `JobRepository.newPostings: Flow<NewPostings>` et `markNewPostingsReviewed()`.
`LiveJobRepository` émet la liste persistée au démarrage et après chaque lecture ou revue ;
`SampleJobRepository` répond par une liste vide (pas de notion de « première vue »).

**ViewModels.** `JobsUiState` porte `newOnly`, `newJobIds`, `firstSeenAtByJobId`, avec
`newCount`, `isNew(job)` et `firstSeenAt(job)`, et `newOnly` rejoint `hasActiveFilters` et
`visibleJobs`. `HomeUiState` porte `newOpportunities` (liste **ordonnée par découverte**,
issue du registre et non de `isNew`), `newPostingCount`, `discoveryBaselineEstablished` et le
`discoverySummary`. `AppViewModel` arme une demande **one-shot** (`requestOpenNewPostings` /
`consumeOpenNewPostings`) : la carte d'accueil ouvre l'onglet Jobs déjà filtré, mais une
découverte en arrière-plan ne change jamais l'écran toute seule.

**UI.** L'onglet Jobs gagne une puce **New (N)**, une bannière de revue (avec **Mark reviewed**)
et, dans la feuille d'explication, un bloc **How this was found** (première vue, source,
dernière vérification, lien officiel). La carte d'accueil « N new since your last review »
indique le dernier succès de recherche et les sources en échec, avec **Review new** /
**Mark reviewed**. La bande de statut des sources ajoute « · N new ». `JobCard` accepte
`showNewBadge` (le registre, pas « publié récemment ») et `discoveredAtEpochMillis`.

**Vérification.** Suite **564 tests, 0 échec, 0 erreur, 3 ignorés** (+4 : `JobsFilterTest` :
`newOnly`/`newCount`/`firstSeenAt` ; `Stage44RefreshDiscoveryTest` : filigranes ligne de base
→ revue ; `Stage15ScreenTest` : bannière + puce « New (1) »). `compileDebugKotlin`,
`testDebugUnitTest` et `assembleDebug` réussissent. Modifié : `JobSeenLedger.kt`,
`JobRepository.kt`, `LiveJobRepository.kt`, `SampleJobRepository.kt`, `ViewModels.kt`,
`AiJobFinderRoot.kt`, `JobCard.kt`, `JobsScreen.kt`, `HomeScreen.kt`, `JobsFilterTest.kt`,
`Stage44RefreshDiscoveryTest.kt`, `Stage15ScreenTest.kt`, `ScreenshotTest.kt`, `AGENTS.md`.

## Étape 46 — Alertes intelligentes pour les bonnes opportunités internationales

**Objectif.** L'étape 20 décidait déjà *quoi dire* dans une notification (barre de score +
anti-doublon). Mais une annonce peut scorer haut et rester une mauvaise interruption : un
scam, un poste réservé aux locaux, un métier hors du profil, ou un lien mort. Cette étape
ajoute le filtre **qualité + nouveauté réelle** : une alerte intelligente ne concerne qu'une
opportunité internationale **authentiquement nouvelle et de bonne qualité**.

**Politique pure (`SmartAlertPolicy`).** Nouveau fichier
`data/notifications/SmartAlertPolicy.kt`. `filter(jobs, newJobIds)` / `qualifies(...)`
n'ajoutent jamais rien : ils ne font que retirer. Un poste est rejeté s'il est
`ScamRisk.HIGH`, `MadagascarEligibility.RESTRICTED`, de `RoleFamily.UNRELATED`, si
`verification.link.isNegative` (lien cassé/inaccessible), ou si `verification.isActionable`
est faux (vacance expirée). Deux modes : `NEW_ONLY` (exige aussi `id ∈ newJobIds`, utilisé en
arrière-plan) et `QUALITY_ONLY` (qualité seule). `reasonsAgainst(...)` nomme chaque raison, en
clair, pour que le silence soit explicable.

**Coordinateur (`JobNotificationsCoordinator`).** `notifyNewMatches` gagne un paramètre
optionnel `newJobIds: Set<String>? = null`. `null` → comportement étape 20 inchangé (tout
match fort peut être annoncé, p. ex. un passage manuel). Non-null → chemin intelligent :
`SmartAlertPolicy.NEW_ONLY.filter(...)` **avant** la barre de score, puis la sélection et
l'anti-doublon habituels. La porte ne fait que restreindre.

**Worker (`JobRefreshRunner`).** Après `runLiveValidation()`, le runner lit
`repository.newPostings.first().jobIds` (le registre de l'étape 44/45, partagé) et le passe au
coordinateur : une passe planifiée n'annonce donc que les annonces **vues pour la première
fois depuis la dernière revue**, jamais tout le flux. Le mode démo reste muet (inchangé).

**UI (`ProfileScreen`).** Quand les alertes sont activées, la carte *Automatic job discovery*
affiche une phrase honnête sous la description du seuil : « Smart alerts only announce
genuinely new postings, and never scams, local-only roles, unrelated trades or postings whose
link is broken. » Masquée quand les alertes sont désactivées. Aucune autre chaîne n'a changé.

**Vérification.** Suite **580 tests, 0 échec, 0 erreur, 3 ignorés** (+16 : `Stage46SmartAlertTest`
— 8 sur la porte qualité/nouveauté, 4 sur le coordinateur, 2 sur le runner via un faux dépôt ;
`Stage46SmartAlertScreenTest` — 2 sur la copie UI). `compileDebugKotlin`, `testDebugUnitTest`
et `assembleDebug` réussissent ; captures régénérées (profile-light/dark, 822×1782).
Modifié : `SmartAlertPolicy.kt` (nouveau), `JobNotificationsCoordinator.kt`,
`JobRefreshRunner.kt`, `ProfileScreen.kt`, `Stage46SmartAlertTest.kt` (nouveau),
`Stage46SmartAlertScreenTest.kt` (nouveau), `AGENTS.md`.

## Stage 47 — file d'approbation des candidatures préparées

Le mode « auto-apply » prépare un CV adapté et une lettre par offre, puis **s'arrête**. Cette
étape ajoute la pièce qui rend cet arrêt sûr : une **file d'approbation**. Une candidature
préparée n'est jamais soumise sans l'approbation explicite du contenu exact que l'utilisateur
a relu. Le vrai envoi reste **désactivé** : une soumission est toujours une **simulation**.

**Modèle (`data/model/ApplicationDraft.kt`).** `ApplicationDraft` porte tout ce que
l'utilisateur approuve — résumé CV, texte intégral de la lettre, réponses aux questions,
documents — et `contentHash`, dérivé **exactement** de ces champs par `DraftContentHash`
(SHA-256 sur un encodage préfixé par longueur, donc deux arrangements différents ne peuvent
pas entrer en collision). `approvedHash` est capturé au moment du tap ; `isApproved` exige que
le hash vivant soit **égal** au hash approuvé, et `needsResubmission` est vrai dès qu'une
édition change le contenu. `DraftStatus` sépare volontairement `SUBMISSION_PENDING` (écrit
*avant* l'enregistrement, pour survivre à un crash) de `SUBMISSION_SIMULATED` / `SUBMISSION_SENT`.

**Stockage (`data/applications/ApplicationDraftStore.kt`).** Même discipline que
`ApplicationStore` : écriture atomique (fichier temporaire puis renommage), sérialisée
derrière un mutex. Aucun envoi, aucune donnée ne quitte l'appareil.

**Centre (`data/applications/ApplicationDraftCenter.kt`).** `prepare` → `approve` → `submit`,
chaque étape sérialisée par un mutex. `prepare` recopie le contenu relu tel quel (l'app
n'invente aucun champ) et, si le contenu n'a pas changé, **ne révoque pas** une approbation
existante ; sinon il passe le brouillon en `NEEDS_RESUBMISSION`. `submit` refuse tant que
`isApproved` est faux, écrit `SUBMISSION_PENDING`, puis s'appuie sur `SubmissionRegistry`
(`allowRealSend = false`) : l'issue est toujours une **simulation**, jamais un envoi. Le
registre empêche aussi la **double soumission** de la même offre. `resumePending()` rejoue au
démarrage tout brouillon resté en `SUBMISSION_PENDING`.

**Notification (`data/notifications/DraftReviewAlert.kt`).** `DraftReviewAlertPolicy.select`
n'alerte que les brouillons **nouvellement en attente** et **jamais deux fois** ; le texte dit
toujours « Nothing has been sent — open it to review and approve ». `JobNotificationsCoordinator`
gagne `notifyDraftReviews(...)` avec son **propre historique** (`notified_draft_reviews.txt`) et
son **propre canal** (`application_reviews`), donc une relance de préparation n'est jamais
confondue avec une alerte nouveau match. Enregistrement **avant** publication, comme pour les
matchs. `JobRefreshRunner` / `JobRefreshWorker` lisent `applicationDraftCenter.awaitingApproval`
sur la même passe.

**UI (`ui/screens/applications/DraftApprovalCard.kt`, `ApplicationsScreen.kt`).** La file
d'approbation s'affiche en tête de l'écran *Applications* : carte dépliable « See what will be
submitted » (résumé CV, lettre, réponses), pastille de statut, boutons *Approve* / *Skip*. Le
`ApplicationsViewModel` observe `draftCenter.awaitingApproval`. Un tap sur la notification
« ready for review » ouvre l'onglet Applications (`MainActivity` → `EXTRA_DRAFT_ID` →
`AiJobFinderRoot`), où vit la file.

**Vérification.** `Stage47DraftApprovalTest` (9 tests) épingle le contrat de sécurité : hash
lié au contenu, refus d'une soumission non approuvée, simulation qui n'envoie jamais,
approbation révoquée par une édition, approbation conservée si le contenu ne change pas, refus
de double soumission, reprise après interruption, et formulation de la notification (jamais
« envoyé »). Ajout d'une capture `draft-approval-card-light/dark` dans `ScreenshotTest`.
`compileDebugKotlin`, `testDebugUnitTest` et `assembleDebug` réussissent.

**Fondation « opportunité/classement expat » (aussi dans cette passe).** Trois briques
explicables, exposées par le conteneur mais pas encore branchées à un écran :
`ExpatOpportunityEngine` note le **paquet expatrié documenté** d'une offre (salaire normalisé,
logement, vols, assurance) et plafonne toute offre disqualifiée (inéligible, scam, lien mort)
— un paquet séduisant ne peut jamais racheter un blocage ; `ExpatRankingEngine` classe la
shortlist en combinant *readiness* (0..60) et *opportunité* (0..40), les deux ingrédients
restant visibles à côté du total, avec des libellés qui n'apparaissent que si la preuve existe ;
`CurrencyConverter` expose la **source et la fraîcheur** de sa table de taux, pour qu'un salaire
converti ne soit jamais pris pour une cotation en direct. Le classement est surfacé à l'étape
48 (voir ci-dessous), avec ses tests dédiés.

Modifié : `ApplicationDraft.kt`, `ApplicationDraftStore.kt`, `ApplicationDraftCenter.kt`,
`DraftReviewAlert.kt`, `DraftApprovalCard.kt`, `ExpatOpportunity.kt`, `ExpatOpportunityEngine.kt`,
`ExpatRanking.kt`, `ExpatRankingEngine.kt` (nouveaux) ; `AiJobFinderApp.kt`,
`JobNotificationCenter.kt`, `JobNotificationsCoordinator.kt`, `JobRefreshRunner.kt`,
`JobRefreshWorker.kt`, `ViewModels.kt`, `ApplicationsScreen.kt`, `AiJobFinderRoot.kt`,
`MainActivity.kt`, `CurrencyConverter.kt`, `ScreenshotTest.kt`, `AGENTS.md`.

## Étape 48 — classement expatrié surfacé dans l'UI

La fondation « opportunité/classement » de l'étape 47 est désormais branchée à un écran : le
**classement expatrié**. Il ne recalcule rien. Le `ExpatRankingViewModel` observe la même
triple entrée que la shortlist (flux d'offres, CV analysé, préférences expat), fait tourner le
même `ShortlistEngine`, puis passe le résultat à `ExpatRankingEngine`. Le classement ne peut
donc pas contredire la *readiness* affichée par la shortlist — un test l'épingle.

**Écran (`ui/screens/ranking/ExpatRankingScreen.kt`).** En-tête (retour + rafraîchir), héro en
dégradé avec le décompte « prêts à traiter », le meilleur score et le nombre de plafonnés, puis
une liste de cartes classées. Chaque carte montre le **rang** (médaille), le **score combiné**
(anneau animé teinté par la bande de readiness), les **deux ingrédients** côte à côte
(readiness 0..60 et paquet expat 0..40) sous forme de mini-barres, les raisons citées, et les
pastilles `ReadinessBadge` / `MatchChip` / bande d'opportunité plus les libellés de classement
(verts pour les atouts, ambre pour les mises en garde). États : squelette, vide, liste.

**Câblage.** Nouvelle route `SecondaryRoutes.EXPAT_RANKING = "expatranking"` et bloc
`composable` avec `ExpatRankingViewModelFactory`. Point d'entrée depuis l'écran **AI** (nouvelle
`CapabilityCard` « Expat ranking ») et depuis **Home** : `ShortlistEntryCard` a été généralisé
en `EntryBannerCard` pour porter les deux bannières (shortlist + classement) sans dupliquer le
style. `HomeScreen` et `HomeContent` gagnent `onOpenRanking`, transmis depuis `AiJobFinderRoot`.

**Vérification.** `Stage48ExpatRankingTest` (10 tests) épingle le contrat : ordre déterministe
et numéroté depuis 1, readiness reprise telle quelle de la shortlist, une offre interdite aux
locaux n'entre jamais au classement (le total la compte quand même), une offre sans route
joignable est plafonnée et placée sous toute offre applicable, une offre scam reste sous le
plafond, les libellés n'apparaissent que si la preuve existe (le silence donne la mise en garde,
jamais un atout), une mise en garde remonte même quand le score est bon, et un flux vide ne
classe rien. Ajout d'une capture `expat-ranking-light/dark` dans `ScreenshotTest`.
`compileDebugKotlin`, `testDebugUnitTest` et `assembleDebug` réussissent.

Modifié : `ExpatRankingScreen.kt` (nouveau) ; `Stage48ExpatRankingTest.kt` (nouveau) ;
`AiJobFinderRoot.kt`, `AiScreen.kt`, `HomeScreen.kt`, `ScreenshotTest.kt`, `AGENTS.md`.

## Étape 48 (suite) — audit de la capacité d'envoi réel et vérification bout-en-bout

**Audit de capacité (`data/submissions/SubmissionCapability.kt`).** La capacité d'envoi réel
est dérivée de la **seule source de vérité** (`SubmissionChannel.realSendSupported`) au lieu
d'un tableau tenu à la main qui pourrait diverger de la porte que le registre applique
réellement. `statement(channel)` rend le même refus que le registre ; `sendsNothing` et
`supportedChannelCount` décrivent l'état courant (aucune route pilotable, donc envoi toujours
simulé). `ProviderCapabilities` gagne `realSubmissionSupported` (faux partout) et
`ProviderDiagnostic` le reprend, alimenté depuis `provider.capabilities` aux quatre points de
construction (`successDiagnostic`, `failureDiagnostic`, `blankDiagnostic`). L'écran
*Live search validation* et la section diagnostics de *Profile* affichent une pastille
« No auto-send » plus une phrase explicite par source.

**Vérification bout-en-bout (`Stage48PipelineReadinessTest`, 3 tests).** Fait tourner toute la
chaîne sur les vrais moteurs : enregistrement source brut → `JobMapper` (match + éligibilité)
→ `ShortlistEngine` → `ExpatRankingEngine` → brouillon préparé → approbation → soumission, et
épingle l'invariant de sécurité final : la soumission est **simulée**, `SENT` n'est jamais
atteint, une seconde tentative sur la même offre est refusée, et un brouillon non approuvé ne
peut pas être soumis. Un second test vérifie qu'une offre « local only » ne rejoint jamais le
classement ; un troisième qu'aucun canal, même avec un `realSender` câblé et `allowRealSend`,
ne produit `SENT`.

`Stage48ProviderSubmissionCapabilityTest` (4 tests) épingle l'audit : aucun provider enregistré
ne prétend pouvoir soumettre, le helper ne peut pas contredire la porte du registre, une source
sans `submissionEndpoint` n'est jamais marquée envoyable, et une offre sur un board ATS
employeur reste simulée.

Modifié : `SubmissionCapability.kt` (nouveau) ; `Stage48ProviderSubmissionCapabilityTest.kt`,
`Stage48PipelineReadinessTest.kt` (nouveaux) ; `JobProvider.kt`, `ProviderDiagnostics.kt`,
`LiveJobRepository.kt`, `LiveSearchValidationScreen.kt`, `ProfileScreen.kt`, `AGENTS.md`.

## Étape 49 — audit de la soumission officielle et frontières de sécurité

**Audit obligatoire avant tout code (`data/submissions/OfficialSubmissionAudit.kt`).** Les
sources d'offres ont été réexaminées : aucune n'expose d'endpoint de candidature pilotable.
La seule API de soumission **officielle** trouvée est la **SmartRecruiters Application API**
(`developers.smartrecruiters.com/docs/application-api`, page à jour au 2026-03-25) :
`GET /postings/:uuid/configuration` (questions, politiques de confidentialité, consentement),
`POST /postings/:uuid/candidates` (création, statut `New` uniquement, champs requis
`firstName`/`lastName`/`email` + réponses obligatoires, pièces jointes Base64 ≤ 2 Mo), et
`GET /postings/:uuid/candidates/:candidateId/status` (statuts NEW…HIRED). Tous les endpoints
sont protégés ; en OAuth 2.0 le scope `candidate_applications_manage` est requis, et les
identifiants se créent dans le *Credential Manager* d'un client SmartRecruiters ou via
l'enregistrement partenaire. L'audit est **écrit comme données** (endpoints + prérequis), pas
comme un discours : chaque prérequis porte sa raison et son remède, et
`realSubmissionAvailable` est **dérivé** (`all { it.met }`), donc il ne peut pas contredire la
liste.

**Décision : pas de première route réelle.** Les prérequis ne sont pas satisfaits et ne peuvent
pas l'être dans cette app : (1) il faut être client SmartRecruiters ou partenaire enregistré ;
(2) il faut un client OAuth avec le scope de candidature ; (3) un **secret client ne peut pas
être protégé dans un APK** — il faudrait un backend dédié, inexistant ; (4) le consentement
RGPD, les questions de sélection et la politique de confidentialité doivent être présentés ;
(5) le throttling adaptatif (8 appels simultanés, délais jusqu'à 128 s, 429) doit être respecté.
Tant que ces prérequis ne sont pas remplis, **rien n'est envoyé** et l'utilisateur postule sur
le **formulaire officiel de l'employeur**.

**Frontières de sécurité.** `SubmissionLifecycle` nomme les états d'une vraie soumission et
ajoute `SIMULATED_NOT_SENT` pour qu'une simulation ne soit **jamais** confondue avec un envoi :
`SUBMITTED_CONFIRMED` (seul `isConfirmedSent`) est **inatteignable** tant que la route n'existe
pas. `SubmissionPreflightCheck` est une porte pure qui bloque sur approbation manquante,
absence de route officielle, contenu requis manquant, **consentement manquant** et doublon —
`allowed` est dérivé des blocages. `OfficialSubmissionClassifier` classe une réponse future :
un `2xx` **avec** id est confirmé, un `2xx` sans id, un timeout, un `5xx` ou un **409**
(conflit → vérifier, jamais renvoyer) sont `Unknown` ; seul un `4xx` est `Failed`. Le modèle
`ApplicationDraft` gagne `consentGiven` (dans le hash : le changer invalide l'approbation),
`remoteApplicationId`/`confirmedAtEpochMillis` (reçus **uniquement** d'une confirmation
réelle, jamais fabriqués). UI : bandeau de canal honnête + interrupteur de consentement +
« Open official form » sur la carte de brouillon ; carte « Real application submission » avec
les prérequis dans l'écran diagnostics.

Modifié : `OfficialSubmissionAudit.kt`, `SubmissionLifecycle.kt`, `SubmissionPreflight.kt`,
`OfficialSubmissionClassifier.kt` (nouveaux) ; `Stage49OfficialSubmissionAuditTest.kt` (nouveau,
16 tests) ; `ApplicationDraft.kt`, `ApplicationDraftCenter.kt`, `ViewModels.kt`,
`ApplicationsScreen.kt`, `DraftApprovalCard.kt`, `LiveSearchValidationScreen.kt`,
`AiJobFinderRoot.kt`, `AGENTS.md`.

## Étape 50 — qualité de l'offre et vérification en direct

**Types de bénéfices manquants.** `JobBenefitTypes` ne couvrait pas tout ce qu'un poste
international documente réellement : ajout de `RELOCATION_SUPPORT`, `ACCOMMODATION`,
`MINIMUM_SALARY`, `WORK_PERMIT` et `PAID_LEAVE` (ce dernier **distinct** du visa : obtenir un
permis n'est pas la même chose que l'employeur sponsorise un visa). `ExpatBenefitExtractor`
gagne des blocs de règles `WORK_PERMIT` (après le visa) et `PAID_LEAVE` (après la santé), avec
des phrases de relocalisation restaurées. `BenefitEvidence` porte désormais `source` et
`verifiedAtEpochMillis` (défaut « non enregistré », jamais inventé) pour qu'une affirmation lue
il y a des mois puisse être détectée comme périmée (`hasProvenance`, `isStale`).

**Couverture des rôles.** `TargetSearch` reconnaît de nouveaux intitulés de métier
(mechanical fitter, plant maintenance technician, equipment service technician) et un jeu de
`equipmentTerms`, pour que les offres de maintenance industrielle réelles ne soient pas
écartées comme hors-sujet.

**La date limite comme axe à part entière.** `RawJob` portait déjà `expiresAtEpochMillis`, mais
la lecture de vérification ne l'exposait pas. `Job` et `OfferVerification` portent maintenant
`expiresAtEpochMillis` et un axe `deadline` (`VerificationAspect.APPLICATION_DEADLINE`).
`OfferVerificationEngine` lit la date **de l'employeur** : une date publiée déjà passée est
`EXPIRED` ; une offre sans date reste `UNKNOWN` — le silence n'est **jamais** une expiration.
`isActionable` exclut aussi un `deadline` négatif, donc une offre expirée ne peut pas
surclasser une offre vivante. La date est persistée (`JobCache` : clé `expiresAt`) et affichée
dans le panneau « Verified before you apply » (les faits sont rendus génériquement).

**Écran diagnostics plus honnête.** `SourceDiagnosticCard` (LiveSearchValidationScreen) affiche
désormais, sur un échec, « Kept from the last good read … » pour que les compteurs de la carte
(issus du dernier succès) ne soient pas lus comme ceux de la tentative qui vient d'échouer.
Le bandeau hors-ligne et la carte `SourceDiagnosticCard`/`SourceDiagnosticsUiState` restent les
points de lecture uniques de l'état des sources.

**Tests.** `Stage50OfferQualityTest` (18 tests : expiration, lien cassé, restriction locale lue
comme refus, route visa inconnue non refusée, provenance des bénéfices, permis de travail vs
visa, congés payés, rôles techniques, et les 4 nouveaux cas de vérification de la date limite)
et `Stage50ProviderAuditTest` (8 tests). Deux fixtures de pipeline qui se déclaraient
« entièrement documentées » ont été mises à jour pour inclure les deux nouveaux bénéfices, et
`Stage42OfferVerificationTest` attend le nouvel axe `APPLICATION_DEADLINE`.

Modifié : `ExpatBenefits.kt`, `ExpatBenefitExtractor.kt`, `TargetSearch.kt`, `Job.kt`,
`OfferVerification.kt`, `OfferVerificationEngine.kt`, `JobMapper.kt`, `JobCache.kt`,
`JobProvider.kt`, `RecordValidationEngine.kt`, `LiveSearchValidationScreen.kt`, `JobsScreen.kt`,
`Stage50OfferQualityTest.kt` (nouveau), `Stage11PipelineTest.kt`, `Stage26PipelineTest.kt`,
`Stage42OfferVerificationTest.kt`, `Stage48ExpatRankingTest.kt`, `AGENTS.md`.

## Étape 51 — validation de bout en bout et préparation à la production

**But.** Vérifier la chaîne complète (sources réelles → analyse → score → éligibilité
Madagascar → tri) sur des données vivantes, et livrer un état « prêt pour la production ».
Aucune nouvelle fonctionnalité : l'étape est une validation, plus la correction des défauts
réels qu'elle révèle.

**Environnement.** JDK 21, SDK `android-34`, Gradle 8.7. Le conteneur n'a **ni `/dev/kvm`, ni
émulateur, ni appareil** : la validation « sur appareil » est impossible ici et est déclarée
comme telle plutôt que simulée. L'UI est vérifiée sans appareil via Robolectric
(`ScreenshotTest` 33 cas, `NavigationTest` 8 cas, clair + sombre, PNG dans
`app/build/screenshots/`).

**Suite complète.** `./gradlew :app:testDebugUnitTest` → **653 tests, 0 échec, 0 erreur,
3 ignorés**. Les 3 ignorés sont exactement les sondes réseau gardées par `LIVE_PROBE=1`
(Stages 14, 17, 37) : elles ne s'exécutent jamais par défaut, donc la suite reste hors-ligne.

**Sondes réseau (`LIVE_PROBE=1`).** Exécutées pour de vrai : Stage 14 atteint les sources sans
clé (Arbeitnow 60, Remotive 13, Jobicy 50, Himalayas 20 ; Adzuna sans clé = `NOT_CONFIGURED`,
jamais inventé), Stage 37 atteint les boards Workday (Weir, Sulzer, EPSA, First Quantum).
Arbeitnow répond 301 → suivi automatiquement (`HttpJsonClient.instanceFollowRedirects = true`).

**Défaut réel trouvé et corrigé — un refus lu comme une offre.** La sonde Stage 14 a montré
une annonce dont le texte dit *« This position is **not** eligible for visa sponsorship »*
classée `VISA_SPONSORED` avec `visa_sponsorship=CONFIRMED`. Cause : « visa sponsorship » est
une phrase forte et la table de négation ne couvrait pas « not eligible », donc un **refus**
était lu comme une **confirmation**. Deux couches corrigées, car chacune suffisait à tromper :
- `ExpatBenefitExtractor.NEGATION` couvre désormais `not eligible`, `not able to`,
  `no sponsorship` (le refus prime sur la phrase forte).
- `EligibilityEngine.LOCAL_ONLY_MARKERS` liste les refus explicites (« not eligible for visa
  sponsorship », « unable to provide sponsorship », …), donc un refus devient `RESTRICTED` au
  lieu de `VISA_SPONSORED`.

Sur données vivantes, le résultat passe de `VISA_SPONSORED`/`CONFIRMED` à
`RESTRICTED`/`NOT_OFFERED` : dire à un candidat malgache qu'il peut postuler quand l'employeur
écrit l'inverse est exactement le mensonge que l'app ne doit jamais produire. Trois tests de
non-régression ajoutés (`Stage50OfferQualityTest`, désormais 21 tests), dont un garde-fou
prouvant qu'une **vraie** offre de visa reste lue `CONFIRMED` (pas de sur-négation).

**Sources (11 fournisseurs audités).** `ProviderAuditRegistry` décrit chaque source depuis le
fournisseur lui-même (pas de table tenue à la main) : `id`, `kind`, `dataSource`,
`documentationUrl`, capacités, `requiresCredentials`, `botGated`. Points clés :
Arbeitnow/Remotive/Jobicy/Himalayas = sans clé ; EURES = service public (POST) ; Workday,
Greenhouse, Ashby, Lever, SmartRecruiters = boards employeurs (l'offre est celle de
l'employeur) ; Adzuna et Jooble = agrégateurs **avec clé** (Jooble est de plus `botGated`).
`realSubmissionSupported = false` partout : aucune source ne reçoit de candidature réelle.

**Frontières de sécurité vérifiées (verts).** `Stage47DraftApprovalTest` : un brouillon non
approuvé ne peut pas être envoyé, éditer après approbation exige une nouvelle approbation,
une même offre ne part pas deux fois, une soumission interrompue est reprise et non perdue,
et un envoi est enregistré comme **simulation** sans jamais prétendre avoir envoyé.
`Stage48`/`Stage49` : aucun fournisseur ne se déclare capable d'envoyer, l'audit explique
pourquoi, et `Simulated ≠ Sent` est prouvé.

**Livrables.** `lintDebug` : **0 erreur**, 53 avertissements (ressources inutilisées /
dépréciations), 1 information. `assembleDebug` et `assembleRelease` réussissent. La clé API
NVIDIA n'étant pas fournie dans cet environnement, le chemin d'inférence IA réel (Stage 17,
Stage 18) est **ignoré**, pas contourné : il est déclaré non vérifié plutôt que faussement vert.

**Limite déclarée.** Aucune exécution sur appareil/émulateur dans ce conteneur ; l'UI est
validée sans appareil (Robolectric) et par revue des états vide / erreur / chargement
(`Skeletons.kt`, `EmptyState`, `ErrorState`) présents et rendus.

Modifié : `ExpatBenefitExtractor.kt`, `EligibilityEngine.kt`, `Stage50OfferQualityTest.kt`,
`AGENTS.md`.

## Étape 52 — fiabilité des offres internationales

**But.** Une revue sur téléphone a montré quatre mensonges possibles à l'écran. Chacun est
corrigé au niveau qui le produit, avec un test qui échoue sans le correctif. Aucune nouvelle
fonctionnalité : c'est un durcissement de la fiabilité.

**1. Une recherche de métier ne trouvait rien.** Taper « Mechanical Seal Technician » ne
renvoyait aucune offre alors que le flux contenait « Rotating Equipment Maintenance
Technician ». La barre de recherche ne faisait qu'un test de sous-chaîne littéral. Nouveau
`QueryExpansion.kt` (`data/jobs/search/`) : un groupe de synonymes par métier, plus des
**clusters** de métiers adjacents sur le marché (un monteur de joints tournants est embauché
pour de l'équipement tournant, des pompes et de la maintenance d'usine). L'expansion s'applique
**aux offres déjà chargées** ; elle ne demande jamais de nouveaux résultats à un fournisseur et
n'invente aucune annonce. La correspondance est **mot entier** pour un métier complet (« pump »
ne matche pas « pumpkin ») et par **préfixe de mot** pour une saisie partielle (« rotat » trouve
« Rotating »).

**2. La recherche portait sur un signal dérivé.** Le filtre cherchait aussi dans
`matchedSkills`, une liste produite par l'app. Une offre intitulée « Frontend Developer »
ressortait donc parce que le CV matcher lui avait attribué « Preventive maintenance ». Le
haystack est désormais le texte de l'offre (titre, employeur, lieu, résumé) ; on cherche des
postes, pas des étiquettes.

**3. Un salaire annuel étiqueté « /hr ».** Le cas réel « USD 106k–112k per hour » était affiché
tel quel, soit un facteur ~2000 d'erreur. `SalaryParser` produit maintenant deux drapeaux
d'honnêteté : `unitUncertain` (l'unité annoncée est impossible pour le montant) et
`periodInferred` (aucune unité annoncée, la période a été déduite). La carte affiche alors
« Salary to verify » au lieu d'un nombre, et **aucune conversion EUR n'est tentée** depuis une
unité non fiable. Le plancher de montant a aussi été corrigé : 1 (et non 100) pour HOUR/DAY,
sinon « $45 per hour » était jeté comme du bruit.

**4. Deux compteurs qui semblaient identiques.** L'en-tête Jobs disait « N opportunities open to
international applicants » en comptant *toutes* les offres chargées, alors que le compteur
« Matching » comptait les scores CV. Trois nombres distincts sont désormais nommés :
`visibleJobs` / `jobs` (chargées vs filtrées), `eligibleCount` (éligibles Madagascar) et
`stats.matchingJobs` (score CV). Nouveau `HomeUiState.openToMadagascarCount`, affiché dans le
héros (« X of Y postings are open to applicants in Madagascar ») et dans la rangée de stats
(« Open to MG », à côté de « CV match »).

**5. Fraîcheur et provenance, trois faits séparés.** « Published » (date de l'employeur, jamais
inventée : « Date not stated »), « Fetched » (dernier pull de l'app, montré seulement s'il
apporte une information) et « Discovered » (première vue par l'app). Le libellé de publication
est préfixé pour ne pas être confondu avec la récupération.

**6. Gardes salaire en aval.** `ScamDetector` saute le test « paie irréaliste » quand l'unité
de la source n'est pas fiable (juger la plausibilité depuis un chiffre déjà faux serait une
seconde erreur) ; `ExpatOpportunityEngine` ne convertit pas une unité incertaine ou déduite ;
`RecordValidationEngine` ne valide les montants annuels que si l'unité est fiable.

**Tests.** Nouveau `Stage52ReliabilityTest` (20 cas) couvrant les six points : synonymes de
métier, non-correspondance mot entier, préfixe de saisie, unité incertaine, période déduite,
libellés de salaire, compteurs, fraîcheur. Suite complète : **673 tests, 0 échec, 0 erreur**.
`assembleDebug` réussit.

**Décision de portée.** Un changement local non commité réécrivait entièrement
`BottomNavBar.kt` (pilule flottante → barre pleine largeur toujours étiquetée). C'est une
refonte de la navigation, interdite par les règles du projet et hors sujet pour cette étape
(les notes de session le présentaient à tort comme un simple ajout de Modifier). Il a été
annulé : la navigation existante est conservée telle quelle.

Modifié : `QueryExpansion.kt` (nouveau), `ViewModels.kt`, `TextParsing.kt`, `JobProvider.kt`,
`Job.kt`, `JobMapper.kt`, `AdzunaProvider.kt`, `RemotiveProvider.kt`, `JoobleProvider.kt`,
`Formatters.kt`, `JobCard.kt`, `ScamDetector.kt`, `ExpatOpportunityEngine.kt`,
`RecordValidationEngine.kt`, `HomeScreen.kt`, `JobsScreen.kt`,
`Stage52ReliabilityTest.kt` (nouveau), `AGENTS.md`.

## Étape 53 — refonte visuelle premium de la navigation et de l'écran d'accueil

**But.** Rendre l'application crédible sur un vrai téléphone : une barre de navigation lisible,
un écran d'accueil qui répond d'un coup d'œil, sans casser l'architecture existante.

**Barre de navigation pleine largeur, cinq libellés permanents.** L'ancienne barre flottait
au-dessus du contenu et n'affichait le libellé que de l'onglet sélectionné : « Emplois » et
« Candidatures » étaient donc invisibles pour un nouvel utilisateur. La barre est maintenant
posée **dans le flux** par `AiJobFinderRoot` (le contenu est mesuré dans l'espace restant), avec
un filet de séparation, une ombre douce en clair, et `navigationBarsPadding()` pour les gestes
système. Les cinq libellés français (« Accueil », « Emplois », « Candidatures », « IA »,
« Profil ») restent visibles en permanence, sélectionnés ou non. La pilule d'indicateur
Material 3 marque la sélection.

**Identité vs libellé.** `Destination` porte deux champs distincts : `label` (libellé français
affiché) et `contentDescription` (nom anglais stable : `Home`, `Jobs`, `Applications`, `AI`,
`Profile`). La `contentDescription` est posée sur la `NavigationBarItem` elle-même, pas sur
l'icône : les descendants sont fusionnés dans le nœud de l'onglet, donc une description posée
sur l'icône n'est pas atteignable par `onNodeWithContentDescription`. Les tests et les lecteurs
d'écran visent ainsi une identité qui ne dépend pas de la langue de l'affichage.

**Contenu non masqué.** `navBarClearance` passe de 112 dp (dégagement pour une barre flottante)
à 24 dp : la barre n'étant plus superposée, un grand dégagement ne ferait qu'ajouter un vide en
bas de chaque écran. Le helper `NavBarSpacer` (jamais utilisé) a été supprimé.

**Accueil plus lisible.** Ajout d'un point d'entrée de recherche (« Search role, company or
country ») qui ouvre l'onglet Emplois, là où vivent le vrai champ et son clavier. Les titres de
poste passent de 2 à 3 lignes avant ellipse : un intitulé de métier est un signal de décision et
ne doit pas être tronqué en « Maintenance… » sur un écran étroit.

**Vérification sans appareil.** Deux tests : `Stage53NavigationTest.kt` (7 cas) couvre les cinq
libellés visibles, la description stable de chaque onglet, l'état sélectionné, le clic, et le
rendu à 320 dp avec `fontScale = 1.5`. La géométrie des libellés a aussi été lue dans les PNG de
`ScreenshotTest` (`bottom-nav-*.png`) : chaque libellé tient dans son cinquième sans atteindre le
bord de sa zone. Suite complète : **679 tests, 0 échec**. `assembleDebug`, `assembleRelease` et
`lintDebug` (0 erreur) réussissent.

Modifié : `ui/navigation/Destination.kt`, `ui/navigation/BottomNavBar.kt`, `ui/AiJobFinderRoot.kt`,
`ui/theme/Spacing.kt`, `ui/screens/home/HomeScreen.kt`, `ui/components/JobCard.kt`,
`Stage53NavigationTest.kt` (nouveau), `AGENTS.md`.

## Étape 54 — primitives d'interface partagées sur les quatre onglets

**But.** Les quatre onglets avaient chacun réécrit le même mobilier (titre d'écran, rangée de
chiffres, bandeau de statut, barre d'onglets), ce qui les faisait diverger : une correction sur
Emplois n'atteignait jamais Candidatures. Cette étape introduit un fichier unique,
`ui/components/Premium.kt`, et branche les écrans dessus sans toucher à l'architecture, à la
navigation ni aux thèmes.

**Ce que porte le fichier partagé.** `ScreenHeader` (titre + sous-titre, une seule application du
`statusBarsPadding`, sous-titre borné à deux lignes), `HeroSurface` (surface navy avec un halo
d'accent, palette propre au mode sombre), `InfoBanner` + `BannerTone` (quatre tons sémantiques),
`StatStrip` + `StatItem` (une carte de chiffres séparés par des filets), `TabStrip` (barre
d'onglets défilante) et `TabChip`. `TabStrip` utilise `selectable(role = Role.Tab)` plutôt que
`clickable`, donc l'onglet choisi est annoncé aux lecteurs d'écran et repérable par
`assertIsSelected`.

**Écrans branchés.** Emplois, Candidatures, IA et Profil lisent tous `ScreenHeader`. Candidatures
remplace ses `FilterChip` par `TabStrip` et ajoute un `StatStrip` (Total / Preparing / Active /
Offers) ; son état vide parle vrai (« No applications yet ») au lieu d'un message d'erreur alors
que rien n'a échoué. Profil remplace son en-tête écrit à la main par `HeroSurface` et ajoute un
`StatStrip` (Skills / Years / Education / Countries). L'espace IA regroupe ses dix capacités en
trois blocs titrés (« Find the right role », « Apply with confidence », « Stay ahead ») au lieu
d'un mur de cartes.

**Vérification sans appareil.** `Stage54PremiumUiTest.kt` (9 cas) rend chaque primitive et chaque
écran, vérifie l'onglet sélectionné, le clic, et un rendu à 320 dp où un libellé risquerait d'être
tronqué. Suite complète : **688 tests, 0 échec** (3 ignorés). `assembleDebug` et
`assembleRelease` réussissent.

Modifié : `ui/components/Premium.kt` (nouveau), `ui/screens/jobs/JobsScreen.kt`,
`ui/screens/applications/ApplicationsScreen.kt`, `ui/screens/ai/AiScreen.kt`,
`ui/screens/profile/ProfileScreen.kt`, `Stage54PremiumUiTest.kt` (nouveau), `AGENTS.md`.

## Étape 55 — la veille en arrière-plan doit s'arrêter, et le dire

**Objectif.** L'audit du rafraîchissement planifié a trouvé deux mensonges par omission. D'abord,
WorkManager replanifie un `Result.retry()` **sans remettre à zéro** son compteur de tentatives :
un hôte injoignable pouvait donc être réinterrogé indéfiniment, toutes les 30 minutes. Ensuite,
quand la veille finissait par renoncer, rien ne l'enregistrait : la carte de découverte
continuait d'afficher le dernier flux en cache comme si la recherche automatique fonctionnait.
Cette étape rend le retry **fini** et le silence **explicable**.

**Plafond de tentatives.** `work/RefreshRetryPolicy.kt` gagne `MAX_ATTEMPTS = 3` (l'essai initial
plus deux reprises). Le compteur WorkManager étant celui de l'exécution *précédente*, la tentative
courante vaut `runAttemptCount + 1` ; en dessous du plafond on redemande un retry, au plafond on
**renonce**. Rien d'autre de la politique ne change : un flux exploitable, un échec permanent ou
une source *rate-limitée* restent sans reprise.

**Verdict pur.** Nouveau fichier `work/RefreshRunState.kt` : `RefreshRunClassifier.classify(
diagnostics, runAttemptCount)` renvoie un `RefreshVerdict` (`SUCCEED` / `RETRY` / `GIVE_UP` plus
l'`outcome` à enregistrer — `null` pour un simple retry, car une exécution replanifiée n'est pas
terminée). `forThrownError(...)` applique le même plafond à une exception ; seule une exception
transitoire (I/O, timeout) est reprise. `reasonFor(...)` nomme la raison en clair (« No internet
connection when the search ran. », « The job sources could not be reached. », « … took too long to
answer. », « Several job sources could not be reached. ») plutôt qu'un « échec » générique.

**État observable et honnête.** `data/discovery/RefreshRunState.kt` porte le modèle
`RefreshRunState` (`lastRunAt`, `lastSuccessAt`, `lastGiveUpAt`, `retryExhausted`, `reason`) et son
pliage `after(...)` : un succès **efface** l'alerte précédente, un abandon la pose sans perdre la
date du dernier succès. `work/RefreshRunStateStore` le persiste dans un petit JSON écrit
atomiquement, exposé en `Flow` (`observe()`) : la première émission est la valeur du disque, donc
un redémarrage dit la vérité immédiatement. Un fichier corrompu est écarté, jamais fatal.

**Câblage.** `JobRefreshWorker` classe chaque exécution, **enregistre** l'outcome quand il y en a
un, et mappe la décision : `RETRY` → `Result.retry()`, `SUCCEED` → `Result.success()`, `GIVE_UP` →
`Result.failure()`. Pour du travail périodique, `failure()` ne stoppe pas la recherche : WorkManager
réenfile la période suivante avec un compteur **neuf** (vérifié dans le bytecode : `failure()` →
`resetPeriodicAndResolve()` qui remet `runAttemptCount` à zéro). La carte lit
`RefreshRunStateStore.observe()` dans `discoverySummary` (`combine` à trois flux).

**UI (Profil).** `DiscoverySummary` porte `retryExhausted` et `backgroundFailureReason` ;
`DiscoveryStatus` affiche, quand la veille a renoncé, une phrase d'avertissement avec la raison
réelle — et un état initial honnête (« The automatic search hasn't reached the job sources yet. »)
au lieu de « No search has finished yet. » quand l'abandon a eu lieu avant le premier succès.

**Vérification.** Suite **705 tests, 0 échec, 0 erreur, 3 ignorés** (+17 : `Stage55BackgroundTest`
— 6 sur le plafond et le verdict, 2 sur les exceptions, 1 sur les raisons, 2 sur le pliage, 3 sur
la persistance/l'observation, 2 sur le résumé ; `Stage20ProfileScreenTest` : ligne d'abandon
affichée, ligne absente quand la recherche est saine). `compileDebugKotlin`, `testDebugUnitTest`,
`assembleDebug` et `assembleRelease` réussissent. Nouveaux fichiers :
`data/discovery/RefreshRunState.kt`, `work/RefreshRunState.kt`, `Stage55BackgroundTest.kt`.
Modifié : `work/RefreshRetryPolicy.kt`, `work/JobRefreshWorker.kt`,
`data/discovery/DiscoverySummary.kt`, `AiJobFinderApp.kt`,
`ui/screens/profile/ProfileScreen.kt`, `Stage20ProfileScreenTest.kt`, `AGENTS.md`.

## Étape 56 — historique des recherches : dire ce que chaque veille a fait

**Objectif.** L'étape 55 rendait le retry fini et le silence explicable, mais elle ne répondait
qu'à « la veille s'est-elle arrêtée ? ». Elle ne disait pas **ce que** chaque exécution avait
fait. Cette étape ajoute un historique persistant : un enregistrement par exécution terminée,
manuelle ou planifiée, consultable depuis la carte « Découverte » du Profil.

**Modèle pur.** Nouveau `data/discovery/RefreshHistory.kt` :
`RefreshRunRecord` (id, `RefreshTrigger`, début/fin, `RefreshOutcome`, raison, résultats par
source, compteurs) ; `RefreshHistory` (liste plus récente d'abord, plafonnée à `MAX_RUNS = 20`) ;
et `RefreshOutcomeClassifier.classify(attempted, diagnostics, delta)` — pur, donc testable sans
dépôt ni WorkManager. Deux règles d'honnêteté y sont centrales :
- `NOT_ATTEMPTED` est distinct de `FAILED` : une requête refusée parce qu'une recherche tournait
  déjà n'a **rien tenté**, l'enregistrer comme échec serait un mensonge.
- les compteurs (`newCount`, `updatedCount`, `duplicateCount`) sont **nullables** : une lecture
  échouée ne modifie pas le delta du dépôt, donc afficher « 0 nouvelle » serait une autre
  affirmation fausse. Ils ne sont renseignés que lorsque la lecture a réellement mesuré.

**Persistance.** `RefreshHistoryStore` écrit un petit JSON (écriture atomique, fichier corrompu
écarté, jamais fatal) et l'expose en `Flow` (`observe()`), première émission = valeur du disque.
Rien de personnel n'y est écrit : uniquement horaires, déclencheurs, résultats et **noms** de
sources — jamais le contenu du CV, un identifiant ou un terme de recherche.

**Un seul chemin d'exécution.** Nouveau `work/RefreshRunCoordinator.kt` : point d'entrée unique
pour un tap « Refresh now » comme pour le worker. `Mutex.tryLock` ⇒ une seule exécution à la
fois, une seconde demande est **refusée** (pas mise en file) ; le dépôt refusant silencieusement
une lecture réentrante, le coordinateur compare l'horodatage de tentative **avant/après** et, s'il
n'a pas bougé, enregistre `NOT_ATTEMPTED`. Un run manuel est repris sous `RefreshRetryPolicy` via
`runWithBoundedRetry(...)`, exactement comme un run planifié. `refreshRunRecord(...)` (fonction
interne partagée) construit le même enregistrement pour le coordinateur et le worker.

**Câblage.** `AiJobFinderApp` expose `refreshHistoryStore`, `refreshRunCoordinator` et
`refreshHistory`. `AppViewModel.searchNow()` passe désormais par le coordinateur (`_searchingNow`
reste l'état d'UI) ; il expose aussi `refreshHistory`, `refreshRunning` et `nextRefreshAt` (lu de
WorkManager via `JobRefreshScheduler.nextScheduledRunAt()`, réévalué quand les préférences
changent — jamais promis depuis l'intervalle). `JobRefreshWorker` enregistre chaque run planifié.

**UI.** `ProfileScreen` : la carte « Découverte » affiche la ligne du prochain run attendu, la
ligne du dernier run réel (refus, succès avec « N of M sources answered », ou échec avec sa
raison), et un bouton **History**. Nouvel écran `ui/screens/refresh/RefreshHistoryScreen.kt`
(`RefreshHistoryViewModel`, route `SecondaryRoutes.REFRESH_HISTORY`) : en-tête, carte de synthèse
(runs / réussis / échoués) et une carte par run (résultat, déclencheur, durée, sources ok/échouées,
compteurs mesurés, sources injoignables), avec état vide.

**Vérification.** Suite **724 tests, 0 échec, 0 erreur, 3 ignorés** (+19 : `Stage56RefreshHistoryTest`
— 6 sur la classification, 4 sur la persistance/le plafond/la corruption, 2 sur
`RefreshHistory`, 2 sur le coordinateur ; `ScreenshotTest` : rendu `refresh-history` et
`refresh-history-empty` en clair et sombre ; `Stage20ProfileScreenTest` : bouton « History » et
lignes de statut). `compileDebugKotlin`, `compileDebugUnitTestKotlin` et `testDebugUnitTest`
réussissent. Nouveaux fichiers : `data/discovery/RefreshHistory.kt`,
`work/RefreshRunCoordinator.kt`, `viewmodel/RefreshHistoryViewModel.kt`,
`ui/screens/refresh/RefreshHistoryScreen.kt`, `Stage56RefreshHistoryTest.kt`. Modifié :
`work/RefreshRetryPolicy.kt`, `work/JobRefreshScheduler.kt`, `work/JobRefreshWorker.kt`,
`AiJobFinderApp.kt`, `viewmodel/ViewModels.kt`, `ui/screens/profile/ProfileScreen.kt`,
`ui/AiJobFinderRoot.kt`, `ScreenshotTest.kt`, `Stage20ProfileScreenTest.kt`, `AGENTS.md`.

## Stage 58 — web edition parity (TypeScript port of the Kotlin engines)

**Objectif.** Une édition web de l'app vit dans `web/` (Vite + React + TypeScript + Tailwind,
PWA). Elle doit produire **exactement** les mêmes résultats que l'app Android : mêmes scores de
matching, mêmes verdicts d'éligibilité, mêmes scores de bénéfices expat, mêmes bandes de fit
international, mêmes rapports de scam, mêmes vérifications d'offre et mêmes inspections de lien.
Le portage n'est pas une réécriture « inspirée » — c'est une transcription dont la parité est
**prouvée par des fixtures**.

**Contrat de parité.** `app/src/test/java/com/aiglobal/jobfinder/Stage58GoldenParityExportTest.kt`
pousse les **vrais** moteurs Kotlin sur un jeu de scénarios partagé et sérialise entrées **et**
sorties dans `web/src/core/parity/kotlin-parity.json`. Aucune valeur du JSON n'est écrite à la
main : tout est la sortie du moteur de production qu'il documente. Le fichier contient :
- `meta` (horloge fixe `1_720_000_000_000`), `profile`, `expatPreferences` ;
- `scenarios` (9 cas : `canada-maintenance`, `saudi-pump`, `germany-fitter`, `remote-worldwide`,
  `unrelated-marketing`, `local-only-diesel`, `thin-lab-technician`, `no-salary-stated`,
  `missing-description`) avec, par cas, la sortie de `RoleClassifier`, `EligibilityEngine`,
  `MatchingEngine`, `RequirementAnalyzer`/`QualificationMatcher`, `SkillLexicon`,
  `CurrencyConverter`, `ExpatBenefitExtractor` + `ExpatBenefitsEngine`, `InternationalFitEngine`,
  `JobEvidenceEngine`, `ScamDetector`, `OfferVerificationEngine` et le `Job` complet ;
- `recordValidation` (15 cas de `RecordValidationEngine`), `deduplication` (4 enregistrements
  cross-source) et `urlInspections` (13 cas de `ApplicationUrlInspector`).

**Suite de parité.** `web/src/core/parity/parity.test.ts` (vitest) reconstruit le même `Job` à
partir des entrées du fixture en n'utilisant que les moteurs TypeScript, puis compare chaque
sortie au fixture. Un écart de moteur fait échouer le test sur la sortie de ce moteur, pas
seulement sur un composant. `npm run typecheck` (tsc strict) et `npm test` passent :
**147 assertions de parité, 154 tests web, 0 échec**.

**Piège à connaître.** Le piège classique est le mapping des objets typés : `matchedTargetRole`
est la **chaîne canonique** (`role.matchedTarget?.canonical`), pas l'objet `TargetRole` — comparer
l'objet au JSON fait échouer le test avec `[object Object]` dans un libellé de raison de fit.

**Régénérer les fixtures.** Après tout changement d'un moteur Kotlin :
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/openhands/android-sdk
export PATH=$JAVA_HOME/bin:$PATH
./gradlew :app:testDebugUnitTest --tests "com.aiglobal.jobfinder.Stage58GoldenParityExportTest"
cd web && npm test        # la parité doit rester verte
```
Le test écrit aussi une copie sous `app/build/parity/kotlin-parity.json` pour inspection.

**Portage TypeScript.** Les moteurs vivent sous `web/src/core/` en miroir de
`data/jobs/engine`, `data/jobs/search`, `data/expat`, `data/scam`, `data/apply` et `data/geo`.
`web/src/core/models.ts` est le miroir de `data/model`. Le style du portage est identique à
Kotlin : fonctions pures, horloge injectée, jamais d'invention de fait (un champ absent reste
`UNKNOWN`/`null`).

## Stage 59 — édition web : UI premium, store vivant et mode démo

**Objectif.** Donner à `web/` la même expérience que l'app Android : un tableau de bord premium,
une navigation par onglets, le suivi des candidatures, le profil — le tout branché sur les
moteurs TypeScript déjà portés, pas sur des données factices.

**Store unique.** `web/src/state/app-store.tsx` est la source de vérité (miroir de
`LiveJobRepository` + `UserPreferencesRepository`) : profil, offres classées, candidatures,
notifications, préférences expat et mode démo. Tout est persisté sous `localStorage` avec une clé
versionnée (`aigjf.store`, `STORAGE_VERSION`), donc un schéma stocké est migré plutôt que
mal relu. `AppStoreProvider` enveloppe l'app dans `web/src/main.tsx`.

**Écrans.** `web/src/ui/screens/` : `HomeScreen` (salutation, tuiles de stats, meilleures offres,
bandeau de sources, astuce IA), `JobsScreen` (filtres éligible/sponsorisé/remote),
`ApplicationsScreen` (suivi + changement de statut), `AiScreen`, `ProfileScreen` (CV, thème,
auto-apply, mode démo).

**Mode démo.** Le vrai mode démo Android (`demoMode`) est implémenté côté web : quand il est
activé depuis `ProfileScreen`, `refreshJobs(true)` sert `web/src/ui/lib/sample-jobs.ts`
(`SAMPLE_JOBS`, 6 offres curatées) au lieu des flux live, et le bandeau de sources affiche
« Demo fixtures » — les données de démonstration ne sont jamais prises pour un résultat live.
`refreshJobs(demoOverride?)` accepte l'override pour éviter une lecture périmée juste après le
basculement du réglage.

**Cohérence d'affichage.**
- `countryLine(country, city)` (`web/src/ui/lib/format.ts`) déduplique un pays répété
  (« Germany, Germany ») et un couple qui résout vers le même pays (« USA » + « United States »
  → « United States »), via `resolveCountry`/`isRecognized` du catalogue géo.
- `MATCHING_JOBS_MIN = 60` (`web/src/ui/lib/job-stats.ts`) fixe le seuil des « Jobs matching
  you » ; `HomeScreen` l'affiche sous les tuiles pour que le chiffre soit explicable.

**Vérification.** `npm run typecheck` (tsc strict), `npm run build` (Vite + PWA workbox) et
`npm test` (vitest) passent : **256 tests web, 0 échec** (providers 14, job-mapper 87, parité
147, format 6, BottomNav 2). Le service worker workbox precache le bundle ; après un rebuild, un
rechargement (ou un `?cb=<n>`) est nécessaire pour activer la nouvelle version.

## Stage 60 — préparation au déploiement de l'édition web

**Objectif.** Rendre l'édition web/PWA déployable, sans toucher à l'app Android et sans jamais
exposer de clé. Le guide complet est `web/DEPLOYMENT.md`.

**Audit du build.** `npm run build` (`tsc --noEmit && vite build`) produit `web/dist/` :
`index.html` 1.07 kB, JS 271.58 kB (85.76 kB gzip), CSS 29.16 kB, `manifest.webmanifest`,
`sw.js` + `workbox-<hash>.js`, `registerSW.js`, `favicon.svg` et trois icônes PNG
(192, 512, maskable 512). Le precache workbox liste 13 entrées (377.27 KiB).

**Base path et routage.** `base: "./"` rend le bundle indépendant de l'hôte : il fonctionne à
la racine d'un domaine comme dans un sous-dossier, sans rebuild. La navigation est en hash
(`#/home`…), donc un rechargement ou un lien profond n'atteint jamais le serveur ; la règle de
fallback SPA (`/* → /index.html`, fournie dans `netlify.toml` et `vercel.json` à la racine) n'est
qu'un filet de sécurité.

**Hébergement recommandé.** Netlify (offre gratuite) en premier choix, Cloudflare Pages et
Vercel en alternatives équivalentes : HTTPS automatique, CDN, rebuild sur push. `netlify.toml`
et `vercel.json` à la racine du dépôt (hors du module Android, ils ne touchent pas son build)
appliquent les en-têtes de cache (`sw.js` en `max-age=0, must-revalidate`, `/assets/*` en
`immutable`) et construisent `web/` puis publient `web/dist`.

**Variables d'environnement du frontend : aucune.** Le bundle ne lit aucun `import.meta.env` ni
`VITE_*`. Les clés éventuelles (Adzuna, Jooble) restent côté proxy.

**Fournisseurs en production (vérifiés le 2026-10-13).** Remotive, Arbeitnow et Jobicy envoient
`Access-Control-Allow-Origin: *` : ils fonctionnent depuis le navigateur, sans clé. Himalayas
n'envoie aucun en-tête CORS et est donc bloqué côté navigateur ; Adzuna et Jooble exigent une
clé. Ces trois sources demandent un petit proxy serveur (voir `web/DEPLOYMENT.md` §5) ; en
attendant, l'app l'indique honnêtement dans le bandeau de diagnostics.

**Mode démo.** Les fixtures (`sample-jobs.ts`) ne sont servies que si le mode démo est activé et
sont étiquetées « Demo fixtures » : jamais présentées comme des résultats live.

**Déploiement : non effectué.** Aucun jeton d'hébergement n'est présent, aucun remote Git n'est
configuré, et un push est hors périmètre. Aucune URL publique n'est donc annoncée. Tout le
nécessaire est prêt (build vérifié, configs, procédures). `.envrc-local` reste sur disque et est
désormais listé dans `.gitignore`.

## Stage 61 — paquet de publication web

**Objectif.** Produire une archive prête à téléverser, sans toucher à l'app Android ni exposer
de secret. Guide complet : `web/DEPLOYMENT.md` §10.

**Archive.** `npm run release:package` (`web/scripts/package-release.py`) empaquette `web/dist/`
à la racine de l'archive (aucun dossier enveloppe), plus un `_redirects`
(`/* /index.html 200`). Sortie : **`web/release/ai-global-job-finder-web-v1.0.0.zip`**,
**182 948 octets**, 12 entrées, accompagnée d'un `.sha256`. `release/` est dans `.gitignore` :
c'est un artefact, régénérable.

**Contenu.** `index.html`, `assets/index-<hash>.{js,css}`, `manifest.webmanifest`, `sw.js`,
`workbox-<hash>.js`, `registerSW.js`, `favicon.svg` et les trois icônes PNG. Le script refuse de
s'exécuter si un fichier requis manque. Aucun fichier source, aucun `.envrc-local`, aucune
clé : seul `dist/` est lu.

**Vérifications.** Le typecheck, les 256 tests et le build passent. L'archive extraite est
**octet pour octet identique** à `dist/` (seul `_redirects` s'ajoute). `unzip -t` : aucun
erreur. Test en aperçu statique : `/`, `/index.html`, `/manifest.webmanifest` (200
`application/manifest+json`), `/sw.js`, `/registerSW.js`, `/workbox-*.js`, les assets hachés,
les trois icônes et `/favicon.svg` répondent tous **200** avec le bon type. Rendu réel dans le
navigateur depuis l'archive extraite : cartes d'offres live (48/47/46/45/44 %), lieu « Berlin »,
navigation basse fonctionnelle. Scan de secrets de l'archive : rien.

**Déploiement : toujours non effectué.** Aucun jeton d'hébergement, aucun remote Git. Aucune URL
publique annoncée. Téléversement manuel Netlify décrit dans `web/DEPLOYMENT.md` §10.

## Stage 62 — parité fonctionnelle et visuelle exacte Android → web

**Objectif.** Faire de l'édition web (`web/`) le **contrepartenaire exact** de l'APK Android
actuel : mêmes moteurs, mêmes chiffres, même expérience. Android est la source de vérité ; on
ne reconçoit pas, on ne simplifie pas, on ne remplace pas la logique — on la porte.

**Étendre les fixtures avant de porter.** Le harnais Stage 58 ne couvrait qu'une partie du
pipeline. `Stage58GoldenParityExportTest.kt` exporte désormais, pour chaque scénario, le `Job`
complet **reconstruit depuis le JSON** (`jobFromJson`, en plus des sorties déjà exportées) puis
pousse ce `Job` dans les moteurs encore non vérifiés :
- `shortlistPipeline` — `CvCompleteness`/`profileCompleteness`, `ShortlistEngine.assess` (entrée
  complète sérialisée : readiness, blockers, orderedBlockers, highlights, rankComponents,
  expatEvidenceCoverage, benefitsMatched/Considered) et `ApplicationPlanEngine.build` en deux
  passes (`plan` sans lettre de motivation, `preparedPlan` avec lettre + 3 tentatives
  d'entretien + verdict scam `NEEDS_CAUTION` + déjà postulé) ;
- `intelligence` — `ExpatIntelligenceEngine.build` sur trois états (`single`, `duplicated` avec
  un second pays, `empty`) : pays, gaps récurrents, top opportunités et `summary`.

**Pièges rencontrés à l'export (à ne pas refaire).**
- `jobFromJson` reçoit le **`output`** du scénario et doit dépaqueter `output["job"]` — le
  passer directement provoquait un `NullPointerException` sur `salaryMin`.
- `salaryMin`/`salaryMax` sont **non-null** côté `Job` (le scénario `no-salary-stated` porte des
  sentinelles numériques) : utiliser `int(...)`, pas `intOrNull(...)`.
- `QualificationGap.met` est `Boolean?` : caster en `Boolean?`, un `as Boolean` lève un NPE sur
  les gaps indéterminés.

**Suite de parité web.** `parity.test.ts` gagne des helpers (`jobFromScenario`, `shortlistEntry`,
`shortlistState`, `planFrom`, `entryJson`, `planJson`, `intelligenceJson`) et cinq `describe`
Stage 62 (complétude profil, shortlist, plan d'application ×2, intelligence expat). Le champ
`Job.verification` est le seul non relu par ces moteurs : il reste `UNKNOWN_VERIFICATION`, avec
un commentaire qui l'explique.

**Vérifications.** Kotlin : `:app:testDebugUnitTest` **BUILD SUCCESSFUL**. Web : `npm run
typecheck` (tsc strict) OK, `npm run build` OK, `npm test` → **302 tests, 0 échec** (parité
**193**, job-mapper 87, providers 14, format 6, BottomNav 2).

**Régénérer.** Après tout changement d'un moteur Kotlin :
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/openhands/android-sdk
export PATH=$JAVA_HOME/bin:$PATH
./gradlew :app:testDebugUnitTest --tests "com.aiglobal.jobfinder.Stage58GoldenParityExportTest"
cd web && npm run typecheck && npm test
```

**Reste à faire (Stage 62, suite).** Parité **d'écran** : l'Android expose 23 écrans
(`screens/**`) contre 5 onglets web. Les parcours secondaires (CV, adaptation CV, détail de
candidature, lettre de motivation, guide expat, entretien, scam shield, alertes, auto-apply,
diagnostics de sources, classement expat, historique d'actualisation, historique de soumission)
ont leurs moteurs portés mais pas encore leurs écrans web — c'est le prochain axe, onglet par
onglet, sans régression sur l'existant.

**Stage 62 — moteurs restants et catalogue IA (fait).** Trois moteurs « on-device » encore non
portés le sont maintenant : lettre de motivation (`cover-letter-generator.ts`, éducation
sérialisée ajoutée à l'exporteur Kotlin), coach d'entretien (`interview-coach.ts`, champ
`missingFacts` ajouté au modèle), et le **catalogue de capacités IA**. `AiScreen.tsx` ne
liste plus des capacités codées en dur : il rend une `TransparencyCard` pilotée par
`AI_CAPABILITY_CATALOG` / `AI_CAPABILITY_KIND_META` / `AI_PROCESSING_LOCATION_META` (portés
depuis `AiCapabilityCatalog`, `AiCapabilityKind`, `AiProcessingLocation`). La carte est
repliée par défaut (« See the full breakdown ») et déplie les 9 capacités avec leur libellé de
type (pastille colorée par `kind`), l'icône nuage/téléphone selon `location` et la note
d'honnêteté — mêmes libellés que le Kotlin `AiCapabilityRow`. Fixtures Stage 62 ajoutées à
`Stage58GoldenParityExportTest.kt` ; `kotlin-parity.json` régénéré. **Vérifications :** web
`npm run typecheck` + `npm test` → **326 tests, 0 échec** (parité 215, job-mapper 87,
providers 14, format 6, BottomNav 2, AiScreen 1, ShortlistScreen 1) ; `npm run build` OK.

**Stage 63 — écran shortlist web (fait).** `ui/screens/ShortlistScreen.tsx` +
`ui/components/ReadinessRing.tsx` portent l'écran Android : en-tête, résumé héros, tableau de
qualité, cartes d'entrée (badges « Stated route in » via `shortlistHasOfficialRoute`),
dashboard qualité, lacunes de profil récurrentes, feuilles d'explication/`ApplyLinkSheet`, et
le `SourceFootnote`. Le champ `ShortlistState.isLive`/`sourceNote` est dérivé dans
`app-store.tsx` (via `shortlistSourceNote` dans `models.ts`) à partir des `reports` du dernier
refresh, exactement comme `ShortlistViewModel.sourceNote` — le texte du milieu reprend le
littéral `"source(s)"` de Kotlin. Tests : `ShortlistScreen.test.tsx` (rendu + note) et un test
de parité de formulation.

**Stage 63b — lien APK permanent.** L'APK release **signé** (`com.aiglobal.jobfinder` v1.0.0,
clé perso `AIGLOBAL`, 8,56 Mo) est livré **dans le bundle web** : `web/public/download.html`
(page d'atterrissage statique, hors routes SPA) + `web/public/download/AI-Global-Job-Finder-
release.apk`. Après déploiement, le lien durable est `https://<site>/download.html`. L'APK ne
matche pas `globPatterns` du service worker, donc il n'est **jamais** précaché (`download.html`
l'est). Le build web ne touche pas l'Android : l'APK est une charge statique. Un script local
`/workspace/download/serve-apk.sh --watchdog` sert aussi les APK sur le port public « work-2 »
(12001) pour la durée du sandbox. Détails : `web/DEPLOYMENT.md` §6.

**Stage 63c — préparation de la publication GitHub.** Ajout d'un `README.md` racine, d'un
workflow `.github/workflows/deploy-pages.yml` (build du PWA `web/` et publication sur GitHub
Pages — l'APK embarqué est donc servi à `/download.html`) et de cette note. **Blocage constaté :**
le jeton `GITHUB_TOKEN` est un jeton d'installation de la GitHub App `openhands-ai` avec les
permissions `contents/issues/pull_requests/actions/workflows/statuses: write` mais **sans droit
de création de dépôt** (`createRepository` → « Resource not accessible by integration »), et il
est limité aux dépôts *sélectionnés* de l'installation. Conséquence : le dépôt doit être créé par
l'utilisateur (public), puis ajouté à l'installation de l'App ; ensuite push + release + Pages
sont réalisables avec le jeton. Aucun secret ni donnée personnelle réelle dans le dépôt (scan :
clés `nvapi-*` de test, e-mails `example.mg`, téléphones factices uniquement).

**Stage 63d — publication GitHub faite.** L'utilisateur a créé le dépôt public et l'a ajouté à
l'installation de l'App. Historique complet poussé sur `main` (branche renommée de `master`).
**Release `v1.0.0`** créée avec l'APK release signé en pièce jointe (8 562 160 o, sha256
`8440b7c3…deb6df`). Lien APK **permanent** :
`https://github.com/rasonjonathan6-coder/ai-global-job-finder/releases/latest/download/AI-Global-Job-Finder-release.apk`.
Le workflow Pages est rendu résilient : il construit le PWA, puis **saute** le déploiement avec un
`::notice` tant que Pages n'est pas activé — le jeton de l'App ne peut pas créer le site Pages
(`POST /repos/.../pages` → 403). **Action manuelle restante** : Settings → Pages → Source =
« GitHub Actions », puis relancer le workflow. URL alors : `https://rasonjonathan6-coder.github.io/
ai-global-job-finder/download.html`.

## Stage 64 — architecture unifiée et parité inter-plateformes

**Objectif.** Faire évoluer ensemble Android, Web/PWA et (à terme) iOS : préserver l'existant,
rendre la logique métier réutilisable, viser une **vraie** parité de cœur partagé (pas seulement
des UI qui se ressemblent). Android reste la source de vérité.

**Audit (état réel, mesuré).** Android : **21 écrans**, ~**44 600 LOC** main, **725 tests,
0 échec** (3 sondes live opt-in *skipped*). Web : **6 écrans**, ~**14 600 LOC**,
**7 fichiers de test / 326 tests, 0 échec**. Fournisseurs : 13 côté Android (dont 8 liés à des
ATS), 3 utilisables en navigateur (CORS) côté web. Le harnais de parité (`kotlin-parity.json` +
`parity.test.ts`) couvre déjà **24 moteurs** sur 9 scénarios, dont la chaîne Stage 62
(shortlist → plan → intelligence → lettre → entretien → catalogue IA). Aucun assistant IA
distant, aucun import de CV, aucune alerte/auto-apply côté web.

**Écarts identifiés.** *Moteurs non portés :* `JobFreshnessEngine`, `ExpatRankingEngine`,
`QueryExpansion`, `SearchStrategy`, moteurs alertes/auto-apply/submissions/adaptation CV, et
l'analyse CV (le parsing PDF reste lié à la plateforme). `ExpatOpportunityEngine` existe côté
web mais n'est pas verrouillé par une fixture. *Écrans web manquants (15) :* détail candidature,
lettre, entretien, scam, alertes, auto-apply, guide expat, diagnostics, classement expat,
historiques (actualisation, soumission), import/adaptation CV, intelligence expat, assistant.
*Filtrage Jobs :* les filtres web sont une version simplifiée du `JobsUiState` Android — à
porter pour une parité stricte.

**Livrables.** `docs/ARCHITECTURE.md` (référence inter-plateformes : les 3 couches — cœur
partagé / adaptateurs / UI — la **matrice de capacités**, le **contrat de la couche 1**, le plan
Stage 65→72 et la stratégie de test) ; `docs/web-parity.md` (le fichier déjà cité par trois
sources mais absent : parité + sources CORS + proxy optionnel) ; `web/scripts/export-kotlin-fixtures.sh`
(le `npm run parity:export` déclaré dans `package.json` mais absent : exporte les fixtures Kotlin
puis relance typecheck + tests web).

**Décision d'architecture (recommandation).** Trois options : (A) SSOT Kotlin + contrat golden
— *l'état actuel, déjà vert* ; (B) **Kotlin Multiplatform** (`:core` commun → JVM/Android,
JS/Wasm web, Native iOS) ; (C) cœur côté serveur — **rejeté** (détruit la promesse on-device,
hors-ligne et privée). Recommandation : **A maintenant, B comme destination, après un spike
prouvé**. Étape 1 : figer le contrat de la couche 1 (déjà respecté par les moteurs Kotlin purs).
Étape 2 : compiler `MatchingEngine` en Kotlin/JS et faire tourner la **fixture de parité
existante** dessus depuis `web/`, en mesurant bundle et build. Étape 3 : seulement si le spike
est vert, migrer la couche 1 moteur par moteur (le portage TS reste jusqu'à ce que le binding
KMP passe la même fixture), puis iOS devient une cible supplémentaire.

**Contrat de la couche 1 (à faire respecter en revue).** Pur (fonctions pures, horloge injectée,
aucune E/S) ; honnête (un champ absent reste `UNKNOWN`/`null`, jamais inventé ; `official = true`
seulement si l'offre l'affirme) ; déterministe (même entrée + même horloge ⇒ même sortie
octet pour octet) ; E/S sérialisable (JSON, pas de type plateforme) ; fixture **versionnée**
(`meta.stage`). Les adaptateurs de couche 2 sont énumérés par plateforme dans le document.

**Vérifications.** Android `:app:testDebugUnitTest` → **BUILD SUCCESSFUL**, **725 tests,
0 échec**, 3 *skipped*. Web : `npm run typecheck` OK, `npm test` → **326 tests / 7 fichiers,
0 échec**, `npm run parity:export` exécuté de bout en bout (export Kotlin + typecheck + tests
verts).

**Reste à faire.** Stages 65–72 du plan : fraîcheur/opportunité, classement + intention de
recherche, moteurs de comportement, partage de l'analyse CV, passe de parité des écrans web,
spike KMP, IA distante web (décision), iOS.

## Stage 65 — parité fraîcheur & opportunité expatriée

**Objectif.** Étendre le harnais de parité existant à deux moteurs restés hors contrat : porter
`JobFreshnessEngine` en TypeScript et **verrouiller** `ExpatOpportunityEngine` (fichier web
présent mais non exercé). Android reste la source de vérité ; on porte la logique, on ne
redessine rien.

**Livrables.**
- `web/src/core/jobs/freshness/job-freshness-engine.ts` — port fidèle et pur du moteur Kotlin.
  `Freshness` = FRESH (< 2 j) / RECENT (< 14 j) / AGING (≤ seuil) / STALE (> seuil) / UNKNOWN.
  Horloge injectée ; **une date de publication absente ou future reste `UNKNOWN`** (aucune date
  inventée). `retrievedAtEpochMillis` (heure de collecte de l'app) ne sert qu'à la phrase
  explicative, jamais à décider la fraîcheur. Seuil par défaut 120 j, constructeur configurable.
- `Stage58GoldenParityExportTest.kt` — deux sections ajoutées, `meta.stage` passé à 65 :
  `freshness` (10 cas limites : date nulle, date future, époque 0, bornes 1/2/13/14/120/121 j,
  absence de date de collecte, plus une fenêtre personnalisée de 30 j) et `expatOpportunity`
  (9 scénarios × 5 variantes : réel, sans *fit*, `scamRisk = HIGH`, éligibilité restreinte,
  lien expiré).
- `web/src/core/parity/parity.test.ts` — deux blocs `describe` de plus : `stage 65 job freshness
  engine` et `stage 65 expatriate opportunity engine`, ce dernier vérifiant aussi le plafonnement
  + disqualification sur arrêt dur et la règle « un avantage CONFIRMED a toujours une preuve ».

**Pourquoi c'est un vrai verrou (et pas un test décoratif).** Les deux sections sont générées
par les moteurs Kotlin réels et comparées en `toEqual` profond. Vérifié par **mutation** : passer
la borne web de 2 à 3 jours fait échouer 1 test ; passer le plafond de disqualification de 35 à
40 fait échouer 6 tests. Une dérive du port échoue donc localement, sur le moteur fautif.

**Vérifications.** Android `:app:testDebugUnitTest` → **BUILD SUCCESSFUL**, **74 classes,
725 tests, 0 échec**, 3 *skipped*. Web : `npm run typecheck` OK, `npm test` → **348 tests /
7 fichiers, 0 échec** (+22), `npm run build` OK (PWA, 428,17 KiB precache), `npm run parity:export`
vert de bout en bout.

**Reste à faire (Stage 66+).** Classement expat + intention de recherche (`ExpatRankingEngine`,
`QueryExpansion`, `SearchStrategy`), moteurs de comportement (alertes, auto-apply, soumissions),
partage de l'analyse CV, passe de parité des écrans web, spike KMP, décision IA distante web, iOS.

## Stage 66 — parité recherche, stratégie de sources & classement expat

**Objectif.** Porter en web les trois capacités Kotlin restées hors contrat — expansion de
requête, stratégie de recherche pilotée par les capacités des sources, classement expatrié — et
les **verrouiller** dans le harnais de parité. Android reste la source de vérité.

**Livrables.**
- `web/src/core/jobs/search/query-expansion.ts` — port de `QueryExpansion` : taxonomie de
  synonymes de métiers (10 groupes), deux *clusters* de proximité (mécanique de maintenance ;
  équipement rotatif / industrie lourde), expansion `expand`, `isTradeQuery`, `knownQueryPhrases`
  et `queryMatches`. Règle clé : un terme de métier **complet** se matche en mot entier (« pump »
  ne doit pas matcher « pumpkin ») ; un terme **partiel** se matche en préfixe de mot (≥ 3
  caractères, « rotat » trouve « Rotating Equipment »).
- `web/src/core/jobs/search/search-strategy.ts` — port de `SearchStrategy` : `planFor` lit
  `ProviderCapabilities` pour décider si une source filtre côté serveur (jeu de mots-clés par
  rôle) ou renvoie un échantillon large filtré localement ; `coverageFrom` construit le rapport
  `SearchCoverage` (sources ciblées/échantillon, pertinents/retrouvés, phrase honnête).
- `web/src/core/jobs/provider/providers.ts` — champ `capabilities` ajouté aux 4 fournisseurs web
  (Remotive, Arbeitnow, Jobicy, Himalayas) ; `job-pipeline.ts` déclenche une recherche pilotée
  par les capacités et publie `RefreshState.coverage` dans le store.
- `web/src/core/jobs/expat-ranking-engine.ts` — logique de `reasons` corrigée (imports
  `BenefitFinding`, `ExpatOpportunityBand`, `opportunityConfidenceIsEnough`, …).
- `web/src/ui/screens/JobsScreen.tsx` — la recherche de l'écran passe par `queryMatches` sur le
  texte propre du posting (titre, entreprise, pays, ville, résumé) ; `matchedSkills` est
  **exclu** comme côté Kotlin. Bandeau `SearchCoverage` (icône loupe/globe + résumé) affiché
  sous les filtres, miroir exact de `JobsScreen.kt`.
- `Stage58GoldenParityExportTest.kt` — trois sections ajoutées, `meta.stage` passé à 66 :
  `queryExpansion` (12 cas + 70 phrases connues + tables de correspondance), `searchStrategy`
  (12 plans + rapport de couverture + couverture vide), `expatRanking` (classement complet,
  sous-ensemble inversé, état vide).
- `web/src/core/parity/parity.test.ts` — trois blocs `describe` de plus (`stage 66 query
  expansion`, `stage 66 search strategy`, `stage 66 expat ranking engine`). La stratégie est
  aussi comparée aux capacités des **vrais** fournisseurs web (`defaultProviders()`).

**Pourquoi c'est un vrai verrou (et pas un test décoratif).** Les trois sections sont générées
par les moteurs Kotlin réels et comparées en `toEqual` profond. Vérifié par **mutation** : porter
le plancher de préfixe de 3 à 4 caractères fait échouer 2 tests. Le classement vérifie en plus
que le sous-ensemble inversé donne le même ordre (déterminisme) et qu'un posting disqualifié est
plafonné à 35 et jamais placé au-dessus d'un posting actionnable.

**Vérifications.** Web : `npm run typecheck` OK, `npm test` → **381 tests / 7 fichiers, 0 échec**
(+33), `npm run build` OK (PWA). Android inchangé (le port n'altère aucune source Kotlin).

**Reste à faire (Stage 67+).** Moteurs de comportement (alertes, auto-apply, soumissions),
partage de l'analyse CV, passe de parité des écrans web, spike KMP, décision IA distante web,
iOS.

## Stage 67 — parité des moteurs de comportement (alertes, auto-apply, soumissions)

**Objectif.** Porter en web les moteurs qui *agissent* sur un flux déjà scoré — alertes de
recherche, file d'auto-apply sûre, contrat de soumission — et les verrouiller dans le harnais de
parité. Android reste la source de vérité ; aucun écran Android n'est modifié.

**Livrables.**
- `web/src/core/alerts/alert-engine.ts` — port de `JobAlertEngine` : `matches(rule, job)` (mots
  clés, pays, plancher de score, périmètre `AlertScope`), `alertsFor(rules, jobs, now)` avec
  dédoublonnage et tri par date, et la garantie qu'une règle désactivée ne produit jamais d'alerte.
- `web/src/core/autoapply/auto-apply-engine.ts` — port de `AutoApplyEngine` (seuil 70 par
  défaut) : `decide(job)` applique les barrières dans l'ordre (scam → éligibilité → seuil → lien
  manquant), `buildQueue(jobs, existing)` trie par `matchScore` décroissant, **écarte** les
  postings `SKIPPED` et **préserve** un statut `APPROVED` ou `SKIPPED` existant au rafraîchissement.
- `web/src/core/submissions/` — le contrat de soumission complet, en six modules :
  `submission-channel.ts` (`SubmissionChannel.infer(job, sourceUrl)` : ATS_API / EMAIL /
  EMPLOYER_PORTAL / UNKNOWN ; URL vide ⇒ UNKNOWN, jamais une supposition),
  `submission-capability.ts` (`SubmissionCapability.sendsNothing`, `supportedChannelCount`,
  `statement(channel)`), `submission-identity.ts` (`SubmissionKey.of(job)` — clé
  `entreprise|titre|pays` normalisée, avec repli sur le titre quand l'employeur est vide),
  `submission-classifier.ts` (`OfficialSubmissionClassifier` : un 2xx **sans** identifiant, un 409
  et un 5xx restent *inconnus*, jamais un succès), `submission-lifecycle.ts` (mapping de chaque
  `DraftStatus` vers `SubmissionLifecycle`) et `submission-preflight.ts`
  (`SubmissionPreflightCheck` : les sept cas qui gardent un envoi réel — non approuvé, pas de
  route, contenu modifié, contenu manquant, pas de consentement, déjà postulé, autorisé).
- `Stage58GoldenParityExportTest.kt` — trois sections ajoutées, `meta.stage` passé à 67 :
  `alertEngine` (matrice `matches` de 4 règles × 9 postings + liste d'alertes),
  `autoApply` (`decisions` par posting, `baseQueue`, `preservedQueue`) et `submission`
  (`channels`, `capability`, `inference`, `identity`, `blankEmployerKey`, `classifier`,
  `lifecycle`, `preflights`).
- `web/src/core/parity/parity.test.ts` — trois blocs `describe` de plus (`stage 67 job alert
  engine`, `stage 67 auto-apply engine`, `stage 67 submission engines`) ; 11 tests.
- `web/src/ui/screens/AiScreen.tsx` — la carte « Prepared, never sent for you » et la ligne
  « Honest by design » lisent `SubmissionCapability.sendsNothing` / `supportedChannelCount` :
  l'affirmation à l'écran est **dérivée du moteur**, pas écrite à la main.

**Pourquoi c'est un vrai verrou.** Les trois sections sont produites par les moteurs Kotlin réels
et comparées en `toEqual` profond. Le test de soumission vérifie explicitement qu'aucune ambiguïté
(2xx sans id, 409, 5xx) n'est convertie en succès, et que la préflight refuse chacun des six cas
interdits. Régénérer après tout changement Kotlin :

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/openhands/android-sdk
export PATH=$JAVA_HOME/bin:$PATH
./gradlew :app:testDebugUnitTest --tests "com.aiglobal.jobfinder.Stage58GoldenParityExportTest"
cd web && npm run typecheck && npm test
```

**Vérifications.** Web : `npm run typecheck` OK, `npm test` → **393 tests / 7 fichiers, 0 échec**
(+12). Android : suite inchangée (le port n'altère aucune source Kotlin).

**Reste à faire (Stage 68+).** Partage de l'analyse CV, passe de parité des écrans web (alertes,
auto-apply, historique de soumission), spike KMP, décision IA distante web, iOS.

## Stage 68 — partage de l'analyse CV (cœur partageable + port web)

**Objectif.** Découper le traitement du CV le long de la couture plateforme : d'un côté la
lecture/le décodage du fichier (lié à `Context`, `Uri`, au réseau), de l'autre un **cœur d'analyse
pur** — normalisation de texte, lexique, segmentation en sections, analyseur déterministe,
fusion de profil, notes « à vérifier ». Le second est une fonction pure de ses entrées, donc le
web le porte à l'identique et le harnais de parité le verrouille. Aucun écran Android n'est
modifié.

**Livrables.**
- `app/src/main/java/com/aiglobal/jobfinder/data/cv/analysis/` — nouveau paquet **autonome** :
  `CvAnalyzer.kt`, `CvLexicon.kt`, `CvProfileMerger.kt` (contient `CvCompleteness`),
  `CvTextNormalizer.kt`, `CvTextStructure.kt`, `CvAnalysisWarnings.kt`, plus deux fichiers
  nouveaux : `CvAnalysisLimits.kt` (extrait `CvParseResult.MIN_USABLE_CHARACTERS` ; c'est
  désormais le parseur qui lit la constante du cœur, et non l'inverse) et `CvAnalysisContract.kt`
  (point d'entrée `analyze(text, existing, protectedFields)` + `SCHEMA_VERSION = 1`). Le paquet
  n'importe plus que `data.model` : aucun `Context`, `Uri`, `Log` ni fichier.
- Importeurs mis à jour vers les nouveaux chemins (`UserProfile.kt`, `CvRepository.kt`, tests
  Stage 27/28/29/38/40, `ProductionDataTest`).
- `Stage58GoldenParityExportTest.kt` — section `cvAnalysis` ajoutée, `meta.stage` passé à 68 :
  `cases` (plein anglais, plein français, texte trop court, vide, fusion avec champs protégés,
  fusion sans protection), `normalize` (mojibake, césure, puces, tirets, espaces multiples, vide,
  blanc, fins de ligne mixtes), `fold` (accents, « anglais », « electro », ligature, majuscules,
  neutre) et `lexicon` (44 compétences, 31 langues, alias, marqueurs de diplôme/établissement/
  certification/titre, pays, villes, lieux repliés, 166 mots-clés de section).
- `web/src/core/cv/` — port TypeScript, un fichier par module Kotlin : `normalizer.ts`
  (`normalizeCvText`, `foldCvText`), `lexicon.ts` (mêmes données, même ordre), `structure.ts`
  (`segmentCvSections`), `analyzer.ts` (`HeuristicCvAnalyzer`), `merger.ts` (`mergeCvProfile`),
  `warnings.ts` (`cvAnalysisWarnings`), `limits.ts`, `contract.ts` (`analyzeCvInto`,
  `CV_ANALYSIS_SCHEMA_VERSION`).
- `web/src/core/parity/parity.test.ts` — bloc `describe("CV analysis core")` : 7 tests qui
  rejouent chaque cas, chaque normalisation, chaque repli, et comparent le lexique entier en
  `toEqual` profond.

**Pourquoi c'est un vrai verrou.** Le cœur est une fonction pure : pas d'horloge, pas de disque,
pas de réseau, donc le port doit reproduire **au caractère près** le texte normalisé, les
compétences détectées, l'ordre des langues et les notes affichées. Le lexique entier est comparé,
pas seulement quelques échantillons : une entrée ajoutée côté Kotlin sans l'être côté web fait
échouer le test. Régénérer après tout changement Kotlin :

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/openhands/android-sdk
export PATH=$JAVA_HOME/bin:$PATH
./gradlew :app:testDebugUnitTest --tests "com.aiglobal.jobfinder.Stage58GoldenParityExportTest"
cd web && npm run typecheck && npm test
```

**Vérifications.** Android : suite inchangée après le déplacement (**725 tests, 0 échec,
3 ignorés**). Web : `npm run typecheck` OK, `npm test` → **400 tests / 7 fichiers, 0 échec**
(+7). `meta.stage = 68`, `cvAnalysis.schemaVersion = 1`.

**Reste à faire (Stage 69+).** Passe de parité des écrans web (alertes, auto-apply, historique de
soumission), spike KMP, décision IA distante web, iOS.

## Stage 69 — passe de parité des écrans web (alertes, auto-apply)

**Objectif.** Donner une contrepartie web aux écrans Android adossés aux moteurs de comportement
portés au Stage 67 : recherches enregistrées (`JobAlertsScreen`) et auto-apply sûr
(`AutoApplyScreen`). L'écran Android reste la source de vérité : le web reproduit ses champs, ses
libellés et son contrat « prépare, n'envoie jamais » à l'identique, sans refonte.

**Livrables.**
- `web/src/state/app-store.tsx` — l'état persisté gagne `expatPreferences`, `applications`,
  `notifications`, `autoApplyEnabled`, `demoMode`, `alertRules`, `alerts` et les items d'auto-apply.
  Nouvelles actions : `refreshAlerts`, `addAlertRule`/`removeAlertRule`, `markAlertRead`,
  `clearAlerts`, `setAutoApplyEnabled`, `setAutoApplyThreshold`, `prepareAutoApply`,
  `approveAutoApply`, `skipAutoApply`, `setDemoMode`, `markNotificationRead`/
  `markAllNotificationsRead`. Les alertes sont **recalculées depuis le flux courant** à chaque
  changement de flux (miroir de `JobAlertsCenter`), en préservant l'état « lu ». `setDemoMode`
  sème les deux recherches curées (miroir de `JobAlertsCenter.seedDefaultRules`) et un effet
  reconstruit la file d'auto-apply quand le mode est actif — un état « on » persisté prépare donc
  au lancement.
- `web/src/ui/components/` — `SecondaryHeader.tsx` (en-tête des écrans secondaires + `HeaderIconButton`),
  `Chip.tsx` (puce de filtre alignée sur les pastilles de `JobsScreen`), `Switch.tsx` (bascule
  accessible `role="switch"`). `icons.tsx` gagne `ChevronUpIcon`, `BellIcon`, `SendIcon`, `SkipIcon`,
  `LockIcon`, `InfoIcon`, `ShieldIcon`, `SparkIcon`, `CheckCircleIcon`.
- `web/src/ui/screens/JobAlertsScreen.tsx` — miroir de `JobAlertsScreen.kt` : en-tête (titre +
  « N saved searches · M new »), carte « nouveau/à jour », éditeur de règle (nom, mots-clés, pays,
  portée, fréquence, seuil), liste des règles, section « Matched postings » avec `Clear`, squelettes,
  état vide et lignes d'alerte (drapeau, `% Match`, `Visa support`, `New`) qui ouvrent le détail
  partagé. Le bouton d'action de l'en-tête utilise l'icône `Refresh` (comme Android, et non `Search`).
- `web/src/ui/screens/AutoApplyScreen.tsx` — miroir de `AutoApplyScreen.kt` : bascule maître,
  carte de sécurité (« ce que l'auto-apply sûr fera et ne fera pas »), seuil (50/60/70/80/90),
  section « Prepared applications » avec `Prepare`, squelettes, état vide, et cartes
  `Draft ready`/`Approved`/`Blocked`/`Queued`/`Skipped`. Approuver ne fait que marquer l'élément :
  l'envoi reste sur la publication (« Open posting to send »). L'icône `Draft ready` est `FileText`
  (Description côté Android).
- `web/src/ui/App.tsx` — routes `alerts` et `autoapply` câblées en écrans secondaires (sans barre
  d'onglets). `AiScreen.tsx` ouvre les deux depuis les cartes de capacité.
- Tests : `JobAlertsScreen.test.tsx` et `AutoApplyScreen.test.tsx` (vitest + Testing Library)
  sèment le document persisté puis pilotent les vrais moteurs portés : une recherche enregistrée
  capture les six fixtures de démo, l'éditeur ajoute une règle, la file prépare un brouillon par
  correspondance et l'approbation affiche « Open posting to send » sans prétendre avoir envoyé.

**Vérifications.** Web : `npm run typecheck` OK, `npm test` → **406 tests / 9 fichiers, 0 échec**
(+6). `npm run build` OK. Contrôle visuel sur mobile (viewport étroit) en clair et sombre :
recherche enregistrée qui capture une vraie publication, approbation d'un brouillon, états vides.

**Reste à faire (Stage 70+).** Historique de soumission côté web, spike KMP, décision IA distante
web, iOS.

## Stage 70 — pertinence live des offres et correction de la shortlist

**Objectif.** L'audit live a montré que les boards sans clé renvoient un large échantillon
mondial trié du plus récent au plus ancien, quasi sans correspondance avec le métier du candidat,
et que la shortlist « actionnable » listait des offres qui n'étaient même pas son métier. Ce
stage ajoute (1) un **filtre de pertinence live** qui ne garde que le métier du candidat, et
(2) une **correction de la shortlist** qui ne liste que des rôles réellement actionnables et du
bon métier, en rapportant honnêtement combien d'offres sans rapport ont été écartées.

**Livrables Android.**
- `data/jobs/search/RelevanceFeed.kt` — transforme un flux classé en `kept`/`dropped` via le
  verdict existant `RoleClassifier`/`RoleFamily` (`TARGET`/`MAINTENANCE_FAMILY` = pertinent,
  `UNRELATED`/`UNKNOWN` = écarté). `summary(totalRetrieved, relevant)` produit la phrase de
  couverture, y compris les cas vide / aucun / tous.
- `data/repository/LiveJobRepository.kt` — le flux `jobs` passe par `RelevanceFeed.relevant(...)`
  avant le classement ; `perProviderLimit = 60`, `perProviderTimeoutMillis = 20_000`.
- `data/jobs/search/SearchCoverage.kt` — le résumé rapporte les offres sans rapport écartées.
- `data/expat/ShortlistEngine.kt` — `build()` ne garde que `eligibility != RESTRICTED` **et**
  `job.isMaintenanceRelevant`, le même verdict que le flux.
- `data/jobs/provider/ArbeitnowProvider.kt` — lit plusieurs pages (`?page=N`, `maxPages = 8`) et
  les met en commun : l'échantillon large est ce qui laisse le filtre local trouver les métiers
  quelques pages plus bas. Une page qui échoue après une réussie n'est pas une panne de source :
  les offres déjà lues sont renvoyées.

**Livrables web (miroir).**
- `web/src/core/jobs/search/relevance-feed.ts` — `relevantJobs`/`unrelatedJobs`/`relevanceSummary`,
  miroir de `RelevanceFeed` ; `jobIsMaintenanceRelevant` vit dans `models.ts` comme les autres
  prédicats de `Job`.
- `web/src/core/repository/job-pipeline.ts` — le filtre de pertinence est câblé (la couverture
  compte toujours l'échantillon complet).
- `web/src/core/shortlist/engine/shortlist-engine.ts` — filtre `jobIsMaintenanceRelevant` ajouté.
- `web/src/core/jobs/provider/providers.ts` — pagination Arbeitnow (pooling multi-pages), nouveau
  fournisseur **SmartRecruiters** (`SmartRecruitersBoard`, lecture des 4 sections de description,
  budget de détails concurrent `SMART_DETAILS_PER_BOARD`, titres de métier lus en premier) et
  helper `isTradeTitle()`.
- `web/src/core/parity/parity.test.ts` — rejoue la section `relevance` du fixture : chaque verdict
  `classifyRole` (famille, cible, pertinence, preuves) et les `keptIds`/`droppedIds`/résumés.

**Golden parity.** Le fixture `Stage58GoldenParityExportTest` gagne une section `relevance`
(`verdicts` avec `descriptionHead` + `family`/`matchedTarget`/`isMaintenanceRelevant`/`evidence`,
`feed` avec `total`/`keptIds`/`droppedIds`/`summary`/`emptySummary`/`noneMatchedSummary`/
`allMatchedSummary`), `meta.stage = 70`. La sortie Kotlin et le port TS sont **identiques octet
pour octet**.

**Correctif de test.** Trois suites historiques (`Stage11PipelineTest`, `Stage21PipelineTest`,
`Stage21ScreenTest`) construisaient `Job` à la main, laissant `roleFamily` à sa valeur par défaut
`UNKNOWN` ; la correction de la shortlist les filtrait donc à tort. Leurs fixtures renseignent
désormais `roleFamily = RoleClassifier.classify(title).family`, comme le fait le vrai mapper,
sans changer l'intention des tests.

**Vérifications.** Android : `:app:testDebugUnitTest` → **732 tests, 0 échec, 0 erreur, 3
ignorés** (+7 : `Stage70RelevanceTest`). Web : `npm run typecheck` OK, `npm test` → **428 tests /
9 fichiers, 0 échec** (+22 : `providers` 14→20, `parity` 288→304). Parité octet pour octet
confirmée sur `relevance` + correction de shortlist.

**Fichiers.** Ajoutés : `RelevanceFeed.kt`, `relevance-feed.ts`, `Stage70RelevanceTest.kt`.
Modifiés : `LiveJobRepository.kt`, `SearchCoverage.kt`, `ShortlistEngine.kt`, `ArbeitnowProvider.kt`,
`Stage58GoldenParityExportTest.kt`, `providers.ts`, `providers.test.ts`, `job-pipeline.ts`,
`shortlist-engine.ts`, `parity.test.ts`, `kotlin-parity.json`, les trois fixtures de test
ci-dessus.

## Conventions

- Every screen composable reads spacing from `LocalSpacing.current` and applies
  `navBarClearance` as bottom content padding. The navigation bar is **in flow** (laid out by
  `AiJobFinderRoot` below the content), so `navBarClearance` is a small breathing gap, not a
  reserve for an overlay — do not raise it back to the old floating-bar value.
- Dark mode is a hand-tuned palette in `Theme.kt`, not an inversion. Check both modes.
- The bottom nav always shows all five labels (`alwaysShowLabel = true`); `Destination.label`
  is the visible French name and `Destination.contentDescription` is the stable English
  identity. Put `contentDescription` on the `NavigationBarItem` (descendants merge), never on
  the icon.
- Premium design guidance comes from the workspace `frontend-design` skill
  (`.agents/skills/frontend-design`), installed from `OpenHands/extensions`. Keep Kotlin +
  Compose + Material 3; apply its principles to typography, cards, motion and loading states.
- No hardcoded user-visible strings worth extracting yet; copy lives inline in Stage 1.

## Stage 71 — validation qualité de la recherche live

**Objectif.** Lancer la **vraie** recherche live et rapporter honnêtement ce que chaque source
renvoie, ce que le filtre de pertinence retient et ce qu'il écarte, puis évaluer la couverture
pour les métiers cibles. Pas de chiffres inventés : le harnais lit le réseau réel et passe le
résultat dans les **vrais** moteurs (`RecordValidationEngine`, `RoleClassifier`, `JobMapper`,
`JobFreshnessEngine`).

**Harnais live.** `app/src/test/java/com/aiglobal/jobfinder/Stage71LiveSearchHarness.kt` est
**opt-in** : il ne s'exécute que si `LIVE_SEARCH=1` est défini (`Assume`), donc la suite et la CI
restent hors-ligne et déterministes. Il construit la même liste de fournisseurs sans clé que
l'app (`Arbeitnow, Remotive, Jobicy, Himalayas, EURES, Workday, Greenhouse, Ashby, Lever,
SmartRecruiters`), exécute le même `SearchStrategy.planFor` + boucle de fetch, déduplique via
`RecordValidationEngine`, classifie chaque titre via `RoleClassifier`, mappe via `JobMapper` et
écrit un rapport console + `web/stage71-live-search.json` (comptes par source, titres retenus,
quasi-manques, titres écartés, fraîcheur, éligibilité).

**Résultat mesuré (run réel).** 786 offres récupérées → 432 après déduplication (354 doublons) →
**110 pertinentes** (métier du candidat), 322 écartées. Par source :

| Source | ciblée | renvoyées | gardées | pertinentes | écartées |
| --- | --- | --- | --- | --- | --- |
| Arbeitnow | non | 60 | 56 | 0 | 56 |
| Remotive | non | 10 | 10 | 0 | 10 |
| Jobicy | non | 50 | 50 | 0 | 50 |
| Himalayas | non | 20 | 20 | 0 | 20 |
| EURES | oui | 300 | 151 | 19 | 132 |
| Employer career sites (Workday) | oui | 112 | 58 | 42 | 16 |
| Industrial career sites (Greenhouse) | non | 18 | 10 | 8 | 2 |
| Advanced manufacturing (Ashby) | non | 60 | 26 | 3 | 23 |
| Industrial employer boards (Lever) | non | 60 | 23 | 13 | 10 |
| Employer job boards (SmartRecruiters) | oui | 96 | 28 | 25 | 3 |

Les quatre boards « remote-first » (Remotive, Jobicy, Himalayas) et Arbeitnow renvoient **zéro**
métier du candidat : ce sont des feeds logiciels/office, et le filtre les écarte correctement. Le
rendement vient des sources ciblées (Workday/SmartRecruiters) et des employeurs industriels.

**Correctif de pertinence (langue).** Le run a montré des titres de métier **réels mais non
anglais** — « Instandhaltungsmechaniker Rotating Equipment » (Sulzer), « Onderhoudstechnieker »
(Belgique), « Vedlikeholdstekniker » (Norvège) — écartés par une taxonomie anglophone. Correctif :
`TargetSearch.strongCoreTerms` gagne les équivalents allemands/néerlandais/scandinaves
(`instandhaltung`, `instandhaltungsmechaniker`, `industriemechaniker`, `anlagenmechaniker`,
`maschinenmechaniker`, `schlosser`, `mechaniker`, `servicetechniker`, `onderhoudstechnieker`,
`onderhoudstechnicus`, `onderhoudselektricien`, `mechanieker`, `mecanicien`, `vedlikehold`,
`vedlikeholdstekniker`, `mekaniker`, `reparatør`) et `weakCoreTerms` les mots faibles
(`monteur`, `technieker`, `elektricien`). La correspondance par mot entier garde le correctif sûr
(`mechaniker` ne matche pas dans `Industriemechaniker` ; un « Schilder »/« Bakker » reste écarté).
Effet mesuré : **86 → 110** offres pertinentes (+24), sans rouvrir la porte aux faux positifs.
Porté à l'identique côté web (`web/src/core/jobs/search/target-search.ts`).

**Golden parity.** `Stage58GoldenParityExportTest` gagne 7 verdicts non anglais dans la section
`relevance` (21 verdicts au total), `meta.stage = 71`. Les deux portées Kotlin/TS sont identiques
octet pour octet.

**Tests.** `Stage71RelevanceTest.kt` (6 cas, déterministe) verrouille les mots de métier étrangers
(allemand/néerlandais/scandinave), l'exclusion d'un « technicien/électricien/peintre » isolé, la
correspondance mot-entier, et l'invariant « un mot n'est pas à la fois fort et faible ».

**Vérifications.** Android : `:app:testDebugUnitTest` → **739 tests, 0 échec, 0 erreur, 4
ignorés** (dont `Stage71LiveSearchHarness` skippé par défaut). Web : `npm run typecheck` OK,
`npm test` → **435 tests / 9 fichiers, 0 échec**, `npm run build` OK (PWA, precache 14 entrées).
Parité octet confirmée.

**Fichiers.** Ajoutés : `Stage71LiveSearchHarness.kt`, `Stage71RelevanceTest.kt`,
`web/stage71-live-search.json` (rapport). Modifiés : `TargetSearch.kt`,
`Stage58GoldenParityExportTest.kt`, `target-search.ts`, `kotlin-parity.json`.

**Déploiement (constat, non bloquant pour Stage 71).** Le workflow
`.github/workflows/deploy-pages.yml` publie `web/dist` sur **GitHub Pages**, mais **Pages n'est
pas activé** sur le dépôt : la dernière exécution a un build `success` mais l'étape « Check
whether Pages is enabled » sort `enabled=false` et le job `deploy` est `skipped`. Conséquence :
`https://<owner>.github.io/ai-global-job-finder/` renvoie **404**. Aucune URL Netlify/Vercel
n'est déclarée dans le dépôt (les fichiers `netlify.toml` / `vercel.json` sont des configurations
prêtes mais non connectées). Il n'y a donc **aucune version publiée** à comparer au commit
`1d94cf9` ; le bundle local (`npm run build`) est vert et installable (manifest + service worker,
HTTPS requis). Action manuelle restante : Settings → Pages → Source = « GitHub Actions », puis
relancer le workflow.

