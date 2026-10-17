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
| SmartRecruiters | Sans clé, recherche serveur `q=` et filtre `country=`, mais **couverture Golfe/Afrique négligeable** (Bosch : Arabie saoudite 2, EAU 0, Qatar 0 ; Afrique du Sud 1). |
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
