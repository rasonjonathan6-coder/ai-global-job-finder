# AI Global Job Finder — unified architecture and cross-platform parity

This document is the reference for keeping the **Android**, **Web/PWA** and (future) **iOS**
editions of AI Global Job Finder evolving as one product. It records the current state, the
parity contract that already holds, the gaps that remain, and a staged plan to move from
*"two editions that agree because they are tested against each other"* to *"one shared core
with thin per-platform shells"*.

Android is the source of truth. Web mirrors it; it does not redesign it.

---

## 1. Where the product stands

| | Android | Web / PWA |
| --- | --- | --- |
| Path | repository root (`app/`) | `web/` |
| Stack | Kotlin, Jetpack Compose, Material 3, MVVM, DataStore | Vite, React, TypeScript, Tailwind, Workbox |
| Main code | ~44,600 LOC Kotlin | ~14,600 LOC TS/TSX |
| Tests | 74 result classes, **725 tests, 0 failures** (3 opt-in live probes skipped) | 7 files, **348 tests, 0 failures** |
| Screens | 21 screen files + 5 top-level tabs | 6 screens (5 tabs + shortlist) |
| Job providers | 13 (Adzuna, Arbeitnow, Ashby, Eures, Greenhouse, Himalayas, Jobicy, Jooble, Lever, Remotive, SmartRecruiters, Workday) | 3 key-free, CORS-enabled (Remotive, Arbeitnow, Jobicy) + Himalayas (proxy-only) |
| Remote AI | NVIDIA (opt-in, consent-gated) | none |
| CV import | PDF/DOCX/TXT parsers on-device | none (profile is hand-entered / demo) |
| Alerts, auto-apply, submissions, adaptation, ranking | full engines + screens | absent |

Both editions ship **v1.0.0**. The Android release APK is signed and is bundled inside the
web build at `download.html`.

### The parity contract that already exists

The single strongest asset is the **golden-fixture harness**:

- `app/src/test/java/com/aiglobal/jobfinder/Stage58GoldenParityExportTest.kt` pushes the
  **real Kotlin engines** over a shared scenario set and serialises inputs **and** outputs to
  `web/src/core/parity/kotlin-parity.json`. No value in that JSON is hand-written; every byte
  is production-engine output.
- `web/src/core/parity/parity.test.ts` rebuilds the same `Job` from those inputs using only
  the TypeScript engines and deep-equals each engine's output against the fixture. A drifted
  engine fails on *that engine's* output, not on a whole-screen assertion.
- Regenerate after any Kotlin engine change:

  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/openhands/android-sdk
  export PATH=$JAVA_HOME/bin:$PATH
  ./gradlew :app:testDebugUnitTest --tests "com.aiglobal.jobfinder.Stage58GoldenParityExportTest"
  cd web && npm run typecheck && npm test
  ```

The fixture covers 26 engines across 9 job scenarios plus `recordValidation` (15),
`deduplication` (4), `urlInspections` (13), `shortlistPipeline`, `intelligence`,
`coverLetters`, `interview`, `aiCapabilities`, `freshness` (10) and `expatOpportunity`
(9 scenarios × 5 variants).

---

## 2. Target architecture — three layers

The product is best understood as three layers. The goal of Stage 64+ is to make layer 1
genuinely **shared** rather than **twice-written**.

```
┌──────────────────────────────────────────────────────────────────────────┐
│ LAYER 1 — SHARED CORE (pure, platform-free, deterministic)                │
│                                                                           │
│  models · matching · eligibility · international-fit · evidence ·         │
│  offer-verification · record-validation · role-relevance · requirements · │
│  skill-lexicon · currency · country-catalog · expat-benefits ·            │
│  expat-opportunity · shortlist · application-plan · expat-intelligence ·  │
│  cover-letter · interview · scam · url-inspector · freshness · ranking ·  │
│  query-expansion · alert-policy · auto-apply-planner · submission         │
│                                                                           │
│  Contract: pure functions, injected clock, no I/O, no platform types.     │
│  A field that is absent stays UNKNOWN/null — never invented.              │
└──────────────────────────────────────────────────────────────────────────┘
        ▲ Kotlin bindings            ▲ TS bindings            ▲ Swift bindings
        │                            │                        │
┌───────┴───────────┐   ┌────────────┴──────────┐  ┌──────────┴───────────┐
│ LAYER 2 — ADAPTERS│   │ LAYER 2 — ADAPTERS    │  │ LAYER 2 — ADAPTERS   │
│ DataStore, Room,  │   │ localStorage, fetch,  │  │ UserDefaults,        │
│ Keystore, pdfbox, │   │ IndexedDB, Service    │  │ URLSession, PDFKit,  │
│ WorkManager, FCM  │   │ Worker, Web Push      │  │ Keychain, BGTask     │
└───────────────────┘   └───────────────────────┘  └──────────────────────┘
        │                            │                        │
┌───────┴───────────┐   ┌────────────┴──────────┐  ┌──────────┴───────────┐
│ LAYER 3 — UI      │   │ LAYER 3 — UI          │  │ LAYER 3 — UI         │
│ Compose, Material3│   │ React, Tailwind, PWA  │  │ SwiftUI              │
└───────────────────┘   └───────────────────────┘  └──────────────────────┘
```

**Layer 1 must never import a platform type.** Today the Kotlin engines already satisfy this
(they are pure functions of their inputs); the discipline to preserve is that the boundary is
the *engine function signature*, not the repository or the ViewModel.

---

## 3. Capability matrix

`✅` shared/ported and parity-tested · `🟡` ported, parity not yet pinned · `⬜` Android-only

| Capability | Android | Web | Parity fixture |
| --- | --- | --- | --- |
| Job mapper | ✅ | ✅ | ✅ `job` |
| Matching engine | ✅ | ✅ | ✅ `matching` |
| Eligibility engine (Madagascar) | ✅ | ✅ | ✅ `eligibility` |
| International-fit engine | ✅ | ✅ | ✅ `internationalFit` |
| Job evidence engine | ✅ | ✅ | ✅ `evidence` |
| Offer verification engine | ✅ | ✅ | ✅ `verification` |
| Record validation engine | ✅ | ✅ | ✅ `recordValidation` |
| Skill lexicon | ✅ | ✅ | ✅ `skills` |
| Role relevance / classifier | ✅ | ✅ | ✅ `role` |
| Requirement analyzer + qualification matcher | ✅ | ✅ | ✅ `requirements` / `gaps` |
| Currency converter | ✅ | ✅ | ✅ `salaryEurPerYear` |
| Country catalog | ✅ | ✅ | ✅ `country` |
| Expat benefit extractor + benefits engine | ✅ | ✅ | ✅ `expatBenefits` |
| Scam detector | ✅ | ✅ | ✅ `scam` |
| Application URL inspector | ✅ | ✅ | ✅ `urlInspections` |
| Shortlist engine | ✅ | ✅ | ✅ `shortlistPipeline` |
| Application plan engine | ✅ | ✅ | ✅ `preparedPlan` |
| Expat intelligence engine | ✅ | ✅ | ✅ `intelligence` |
| Cover letter generator | ✅ | ✅ | ✅ `coverLetters` |
| Interview coach | ✅ | ✅ | ✅ `interview` |
| AI capability catalog | ✅ | ✅ | ✅ `aiCapabilities` |
| Profile completeness | ✅ | ✅ | ✅ `profileCompleteness` |
| Provider error classifier | ✅ | ✅ | ✅ (providers tests) |
| **Job freshness engine** | ✅ | ✅ | ✅ `freshness` |
| **Expat opportunity engine** | ✅ | ✅ | ✅ `expatOpportunity` |
| **Expat ranking engine** | ✅ | ⬜ | ⬜ |
| **Query expansion** | ✅ | ⬜ | ⬜ |
| **Search strategy / target search** | ✅ | 🟡 (`target-search.ts`) | ⬜ |
| **Job alerts engine + policy** | ✅ | ⬜ | ⬜ |
| **Auto-apply engine** | ✅ | ⬜ | ⬜ |
| **Submissions (classifier, preflight, lifecycle, registry)** | ✅ | ⬜ | ⬜ |
| **CV adaptation** | ✅ | ⬜ | ⬜ |
| **CV parsing (PDF/DOCX/TXT) + analyzer + lexicon** | ✅ | ⬜ | ⬜ |
| **Remote AI client (NVIDIA) + grounding** | ✅ | ⬜ | n/a (network) |
| **Refresh scheduler / history / retry policy** | ✅ | 🟡 (basic refresh) | n/a (platform) |

### Parity gaps to close (engines)

1. ~~**Job freshness** — unported.~~ **Closed in Stage 65**: `web/src/core/jobs/freshness/job-freshness-engine.ts`,
   pinned by the `freshness` fixture (10 boundary / missing-data cases).
2. ~~**Expat opportunity** — web file exists but was not exercised by a fixture.~~ **Closed in
   Stage 65**: `expatOpportunity` fixture pins the port over 9 scenarios × 5 variants, including
   the hard-stop and cap behaviour.
3. **Expat ranking** — entirely unported (the `ExpatRankingScreen` has no web counterpart).
4. **Query expansion + search strategy** — the search-intent layer; unported.
5. **Alerts, auto-apply, submissions, CV adaptation** — behaviour engines, unported.
6. **CV parsing/analysis** — platform-bound (pdfbox), so only the *analysis heuristics* are
   candidates for sharing; the file I/O stays an adapter.

### Parity gaps to close (UI)

Web has 6 of Android's 21 screens. Missing web screens, in rough priority order:
application detail, cover letter, interview, scam shield, alerts, auto-apply, expat guide,
diagnostics/live-search validation, expat ranking, refresh history, submission history,
CV import, CV adaptation, expat intelligence, application assistant.

---

## 4. Architecture options

Three viable ways to reach true shared-core parity. They are not mutually exclusive; the
recommendation is a **staged migration** that keeps the shipped editions working at every step.

### Option A — Single source of truth + golden contract (current)

Kotlin is the SSOT; TypeScript is a transcription; a fixture proves equality.

- **Pros** — already shipped and green; zero new toolchain; parity is *provable* and
  falsifiable; no runtime shared dependency; each edition can still be optimised natively.
- **Cons** — every engine change is written twice; the drift risk is managed by discipline
  (regenerate + re-run parity) rather than by the type system; a third platform (iOS) means a
  *third* transcription; the fixture only proves the *scenarios it contains*.

### Option B — Kotlin Multiplatform (KMP) shared core

Move Layer 1 into a `:core` KMP module (common Kotlin) compiled to JVM/Android, **JS/Wasm**
for the web, and **Native** for iOS.

- **Pros** — one implementation, three consumers; the compiler guarantees the engines agree;
  the existing Kotlin engines move almost verbatim; the golden fixtures become a *regression
  net* rather than the *only* guarantee.
- **Cons** — the web edition currently consumes plain TS modules directly; adopting KMP means
  the web build gains a Kotlin/JS (or Wasm) artifact and an interop boundary. That is a real
  architectural change to `web/` (bundle size, build step, dev ergonomics). Kotlin/Native iOS
  is a separate build with its own learning curve. JSON serialisation must be kept in common
  code (`kotlinx.serialization` is already a plugin in `app/build.gradle.kts`).
- **Fit** — best long-term answer for "Android, Web and iOS evolve together".

### Option C — Server-side core

Host the engines behind an API and let all clients call it.

- **Pros** — one implementation, thin clients.
- **Cons** — destroys the product's core promise: **on-device, offline, private**. The AI
  catalog explicitly states most capabilities run on-device and the CV file is never uploaded.
  Rejected on principle, not just on cost.

### Recommendation

**Option A now, Option B as the destination, gated by a proof step.** Concretely:

1. Finish the **contract** so Layer 1 has an explicit, versioned interface (see §5). This is
   cheap, valuable regardless of option, and makes the KMP migration mechanical.
2. Run a **spike** that compiles one already-parity-tested engine (MatchingEngine) to Kotlin/JS
   and drives the *existing* parity fixture through it from `web/`. If the spike keeps the web
   bundle and test suite healthy, adopt KMP for Layer 1 engine-by-engine, always leaving the TS
   port in place until the KMP binding passes the same fixture.
3. Only after the web spike is green does a **native iOS** client become a small extra target
   rather than a third transcription.

Option C is rejected outright.

---

## 5. The Layer-1 interface contract

To make the shared core reusable across platforms — whether transcribed (A) or shared (B) —
the following contract is fixed. It is already honoured by the Kotlin engines and should be
made explicit in code review.

**Purity**
- Every engine is a pure function of its inputs. No network, no disk, no globals, no clock
  reads except through an **injected clock**.
- Side effects (fetch, persist, notify) live in Layer 2 only.

**Honesty**
- A field that is absent from the input stays `UNKNOWN`/`null`. The core never fabricates a
  salary, a visa fact, a benefit or a name.
- Eligibility/benefit/verification reasons carry an `official` flag: `true` only when the
  posting *states* the fact.

**Determinism**
- Same input + same clock ⇒ same output, byte-for-byte. This is what makes the fixture a valid
  oracle.

**Serialisable I/O**
- Inputs and outputs are plain data (JSON-serialisable). No platform types cross the boundary.
  `kotlinx.serialization` (already enabled) is the Kotlin side; the TS side uses the mirrored
  interfaces in `web/src/core/models.ts`.

**Versioned fixture**
- `kotlin-parity.json` carries `meta.stage`. Any engine change that alters an output must bump
  the stage and regenerate the fixture; the parity suite then proves both editions moved
  together.

**Adapter interface (Layer 2)** — each platform implements the same small set:

| Concern | Android | Web | iOS (future) |
| --- | --- | --- | --- |
| Key/value prefs | DataStore | `localStorage` | UserDefaults |
| Job feed fetch | OkHttp (`JsonFeed`) | `fetch` (`JsonFeed`) | URLSession |
| CV file read | pdfbox-android + parsers | *(server or client parse)* | PDFKit |
| Secret storage | Android Keystore (`SecretCipher`) | *(no secrets shipped)* | Keychain |
| Background refresh | WorkManager | Service Worker | BGTaskScheduler |
| Notifications | FCM/NotificationManager | Web Push | APNs |

---

## 6. Staged plan (Stage 65 → 72)

Each stage leaves both editions building and both suites green.

- **Stage 65 — contract + freshness/opportunity parity.** ✅ **Done.** Ported `JobFreshnessEngine`
  to `web/src/core/jobs/freshness/job-freshness-engine.ts` and pinned `ExpatOpportunityEngine`;
  the `freshness` and `expatOpportunity` fixture sections now fail on drift (verified by
  mutation). Android 725 tests, web 348 tests, web build green.
- **Stage 66 — ranking + search-intent parity.** ✅ **Done.** Ported `ExpatRankingEngine`,
  `QueryExpansion` and `SearchStrategy`; pinned each in the fixture (`queryExpansion`,
  `searchStrategy`, `expatRanking`). Completes the "which jobs, ranked how" core. Web 382 tests,
  web build green.
- **Stage 67 — behaviour engines.** ✅ **Done.** Ported the layers that act on a scored feed:
  `JobAlertEngine` (`web/src/core/alerts/alert-engine.ts`), `AutoApplyEngine`
  (`web/src/core/autoapply/auto-apply-engine.ts`), and the submission contract
  (`web/src/core/submissions/`: `SubmissionChannel`, `SubmissionCapability`, `SubmissionKey`,
  `OfficialSubmissionClassifier`, `SubmissionLifecycle`, `SubmissionPreflightCheck`). Pinned as
  the `alertEngine`, `autoApply` and `submission` fixture sections; the `AiScreen` reads
  `SubmissionCapability.sendsNothing` so the "prepared, never sent" claim is engine-derived.
  Web 393 tests, web build green.
- **Stage 68 — CV analysis sharing.** ✅ **Done.** Split CV handling into a platform-bound
  *file/parse* adapter (`data/cv`, outside the new package) and a shareable *analysis* core in
  `data/cv/analysis/`: `CvTextNormalizer`, `CvLexicon`, `CvTextStructure`, `CvAnalyzer`,
  `CvProfileMerger` (with `CvCompleteness`), `CvAnalysisWarnings`, plus `CvAnalysisLimits` and
  `CvAnalysisContract` (`SCHEMA_VERSION = 1`). The package imports only `data.model` — no
  `Context`, `Uri`, `Log` or file I/O — so it is a pure function of its inputs. Ported to
  `web/src/core/cv/` (`normalizer`, `lexicon`, `structure`, `analyzer`, `merger`, `warnings`,
  `limits`, `contract`) and pinned as the `cvAnalysis` fixture section (cases, normalise, fold,
  full lexicon), `meta.stage = 68`. Android 725 tests, web 400 tests, web build green.
- **Stage 69 — web UI parity pass.** ✅ **Done.** Port the remaining Android screens to web one
  tab at a time (application detail, cover letter, interview, scam, alerts, auto-apply, expat
  guide, ranking, diagnostics, refresh/submission history, CV import/adaptation). No redesign —
  port the Kotlin structure and wording.
- **Stage 70 — live job relevance and shortlist correction.** ✅ **Done.** The live audit showed
  the key-free boards returning a broad newest-first global sample with near-zero relevant
  matches, and the actionable shortlist carrying postings that were not the candidate's trade.
  Added a live relevance filter (`RelevanceFeed` / `relevance-feed.ts`) that keeps only the
  candidate's trade via the existing `RoleClassifier` verdict, and corrected the shortlist
  (`ShortlistEngine.build` / `shortlist-engine.ts`) to list only actionable, own-trade roles.
  Widened the sample where it helps (Arbeitnow multi-page pooling) and added the key-free
  **SmartRecruiters** employer boards web-side. The coverage summary now reports how many
  unrelated postings were set aside. Pinned as the `relevance` fixture section, `meta.stage = 70`.
  Android 732 tests, web 428 tests, web build green.
- **Stage 71 — live search quality validation.** ✅ **Done.** Ran the *real* search through the
  production engines and measured per-source quality instead of guessing. 786 postings retrieved
  → 432 after dedup (354 duplicates) → **110 trade-relevant** (322 set aside). The four
  remote-first boards (Remotive, Jobicy, Himalayas) and Arbeitnow returned **zero** of the
  candidate's trade; the yield comes from the targeted sources (Workday 42, SmartRecruiters 25,
  EURES 19, Greenhouse 8, Lever 13). The run surfaced a real false-negative class: genuine trade
  titles named in German/Dutch/Norwegian ("Instandhaltungsmechaniker", "Onderhoudstechnieker",
  "Vedlikeholdstekniker") were dropped by an English-only taxonomy. Widened `strongCoreTerms`
  (and the weak `monteur`/`technieker`/`elektricien`) with those equivalents, whole-word matched
  so an isolated foreign technician/electrician/painter is still excluded — **86 → 110** relevant,
  no false-positive regression. Pinned by `Stage71RelevanceTest`, the widened `relevance` fixture
  (21 verdicts, `meta.stage = 71`), and the opt-in `Stage71LiveSearchHarness` (runs only with
  `LIVE_SEARCH=1`). Android 739 tests, web 435 tests, web build green.
- **Stage 72 — live eligibility, application links and release validation.** ✅ **Done.** Ran the
  real sources again (opt-in `Stage72LiveValidationHarness`, `LIVE_SEARCH=1`), then validated each
  trade-relevant posting the way a candidate would: employer identity, title, location, publication
  date, and a real HTTP check on every application link (101/101 reachable, all 2xx). Every record
  had a real employer, title and date — the fraud/low-quality classes were empty. The run exposed a
  genuine mislabel: **94 of 101** links pointed at applicant-tracking hosts (Workday tenants,
  Greenhouse, Lever, Ashby, SmartRecruiters) that the inspector called "unverified host", so the
  Apply sheet warned on exactly the genuine employer pages. `ApplicationUrlInspector` now classifies
  a known ATS suffix as `EMPLOYER_SITE` (suffix-matched, so a look-alike path like
  `careers.example.com/lever.co/…` still reads `UNKNOWN_HOST`) — the field went from 7 unverified
  to 0. Mirrored byte-for-byte in `application-url-inspector.ts`; the `urlInspections` fixture grew
  13 → 20 cases and `meta.stage` moved to 72. Android 740 tests, web 443 tests, web build green.
- **Stage 73 — KMP spike.** Compile `MatchingEngine` to Kotlin/JS; drive the parity fixture
  through it from `web/`; measure bundle + build impact. Go/no-go on Option B.
- **Stage 74 — remote AI parity (optional, web).** Decide whether the NVIDIA assistant appears
  in the web edition at all. If yes, it must reuse the Kotlin *grounding* contract
  (candidate facts + posting text only; never the CV file, email, phone, address) and be
  consent-gated and off by default. If the web edition stays fully local, say so in the AI
  transparency card rather than implying parity.
- **Stage 75 — iOS.** If the KMP spike is a go, stand up a SwiftUI shell over the KMP core
  (Layer 2 adapters + Layer 3 UI only). The web PWA remains the zero-install option.

---

## 7. Test strategy

- **Golden parity is the spine.** Every shared engine gets a fixture section; the web suite
  deep-equals each engine's output. Add a scenario *before* porting an engine so the port has
  an oracle.
- **Engine unit tests on both sides.** Android tests exercise engines directly
  (`JobsFilterTest`, `Stage19HardeningTest`, …). Web tests mirror the pure derivations.
- **UI tests target `Content` composables / presentational components**, state-driven and free
  of ViewModel/store references, so they survive refactors.
- **Headless verification on Android** stays via `ScreenshotTest` (PNG per screen, both
  themes) and `NavigationTest` (click every bottom-nav tab) — no emulator in this container.
- **Web verification** stays via `npm run typecheck` (tsc strict) + `npm test` (vitest) +
  `npm run build` (Vite/PWA). Browser-check the mobile viewport for layout regressions.
- **A port is done** only when: fixture regenerated, `tsc` clean, `npm test` green, Android
  suite green, and (for screens) the screen renders in both themes at a phone width.

---

## 8. iOS / iPhone

- **Today:** the web edition is already an installable PWA. On iPhone, Add to Home Screen in
  Safari gives a full-screen, offline-capable app with its own icon
  (`web/DEPLOYMENT.md` §8). This is the pragmatic iOS story *now*, and it costs nothing extra.
- **Later:** a native iOS client only makes sense once Layer 1 is genuinely shared (Option B).
  Then iOS is Layer 2 adapters (UserDefaults, URLSession, PDFKit, Keychain, BGTaskScheduler)
  plus a SwiftUI Layer 3 — no engine re-implementation.
- **Do not** start a native iOS port while the core is still transcribed three times; that
  would multiply the drift surface instead of shrinking it.

---

## 9. Risks and open questions

- **Drift risk (Option A).** Managed only by the regenerate-and-retest discipline. The
  mitigation is Option B.
- **KMP web bundle cost.** Kotlin/JS/Wasm adds weight to a PWA that is currently ~471 KiB
  precached. The Stage 73 spike must measure this before committing.
- **Remote AI in the browser.** Adzuna/Jooble keys must never reach the browser; NVIDIA
  likewise. If the web edition ever offers the assistant, it needs a proxy that holds the key,
  exactly like the documented job-provider proxy. Until then the web edition is honestly
  fully local.
- **CV parsing on the web.** pdfbox is Android-only. The web edition either accepts a
  hand-entered profile, parses server-side (contradicting "no upload"), or uses a client-side
  parser (WASM). This is a product decision, flagged not solved.
- **Provider parity.** The web can only reach CORS-enabled feeds directly; Adzuna/Jooble/
  Himalayas need the documented proxy. The diagnostics strip already names a blocked source
  instead of silently returning nothing — keep that behaviour.

---

## 10. Reference commands

```bash
# Android
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/openhands/android-sdk
export PATH=$JAVA_HOME/bin:$PATH
./gradlew :app:testDebugUnitTest          # 725 tests, screenshots in app/build/screenshots
./gradlew :app:assembleDebug              # debug APK

# Web
cd web
npm ci
npm run typecheck                         # tsc strict
npm test                                  # 326 tests (parity + components)
npm run build                             # production PWA bundle in web/dist

# Regenerate the parity oracle after any Kotlin engine change
./gradlew :app:testDebugUnitTest --tests "com.aiglobal.jobfinder.Stage58GoldenParityExportTest"
cd web && npm run typecheck && npm test
```
