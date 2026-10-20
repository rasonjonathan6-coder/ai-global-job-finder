# Web edition — parity and the network seam

The web / PWA edition (`web/`) is a faithful mirror of the Android app, not a separate
product. This note covers the two things the web edition's source comments point here for:
how parity is proved, and which job sources a browser can actually reach (and the optional
proxy for the rest). For the cross-platform architecture and the staged plan to a shared
core, see `docs/ARCHITECTURE.md`.

## Parity, proved not asserted

The Android engines are the source of truth. Their behaviour is frozen into a fixture by
`Stage58GoldenParityExportTest.kt`, which runs the **real** Kotlin engines over a shared
scenario set and writes inputs **and** outputs to `web/src/core/parity/kotlin-parity.json`.
Nothing in that file is hand-written.

`web/src/core/parity/parity.test.ts` replays the same inputs through the TypeScript engines
and deep-equals each engine's output against the fixture. A drifted engine fails on that
engine's output, so a regression is localised to the engine that caused it. Stage 65 added two
sections this way: `freshness` (`JobFreshnessEngine`, 10 boundary / missing-data cases) and
`expatOpportunity` (`ExpatOpportunityEngine`, 9 scenarios × 5 variants including the hard-stop
cap). Both were confirmed to fail under a deliberate mutation, so the lock is real.

Stage 66 added three more, porting the search and ranking layer the jobs screen runs on:

- `queryExpansion` (`QueryExpansion`, 12 cases) — the trade synonym taxonomy, its two cluster
  links, and the whole-word vs word-prefix matching rule ("pump" must not fire inside
  "pumpkin", but "rotat" must still narrow to "Rotating Equipment").
- `searchStrategy` (`SearchStrategy.planFor` / `coverageFrom`, 12 provider plans + one coverage
  report) — the plan is driven by each provider's real `ProviderCapabilities`, so a source that
  cannot filter server-side is fetched once and filtered locally. The web `JobPipeline` and
  `JobsScreen` read the same `SearchCoverage`, so the "broad sample" wording is identical on
  both editions.
- `expatRanking` (`ExpatRankingEngine`, full / reversed-subset / empty states) — the 60/40
  readiness-opportunity blend, the 35-point disqualified cap, and the evidence-gated labels and
  reasons.

Stage 67 added the **behaviour engines** — the layers that turn a scored feed into saved-search
alerts, a safe auto-apply queue, and an honest submission contract:

- `alertEngine` (`JobAlertEngine`, 4 rules × 9 postings) — the per-rule `matches` matrix, the
  full alert list, and the guarantee that a disabled rule never fires.
- `autoApply` (`AutoApplyEngine`, threshold 70) — one decision per posting (the scam/eligibility/
  threshold/link gates in order), the best-match-first queue with skipped postings dropped, and
  the preservation of an `APPROVED` or `SKIPPED` entry across a refresh.
- `submission` — the whole submission contract: `SubmissionChannel` facts + `SubmissionCapability`
  (zero drivable routes today), URL→channel inference, `SubmissionKey` identity (including the
  blank-employer fallback), the `OfficialSubmissionClassifier` over 12 status/id cases (a 2xx
  without an id, a 409 and a 5xx all stay *unknown*, never a success), the `SubmissionLifecycle`
  mapping for every `DraftStatus`, and `SubmissionPreflightCheck` over the seven cases that gate
  a real send.

The `AiScreen` reads `SubmissionCapability.sendsNothing` directly, so the "prepared, never sent
for you" claim on screen is derived from the same gate the registry enforces, not a hand-written
sentence.

Stage 68 added the **CV analysis core** — the shareable half of CV handling, split from the
platform-bound file/parse adapter along one seam:

- `cvAnalysis` (`CvAnalysisResult` + `CvTextNormalizer` + `CvLexicon`) — six analysis cases
  (full English, full French, too-thin text, empty, merge-preserving-protected,
  merge-unprotected), each pinned as the full profile, the "worth checking" notes, the
  confidence score, the per-field evidence map and the section-presence flags; the normaliser
  cases (mojibake repair, hyphenated line break, bullets, dashes, runs of spaces, empty, blank,
  mixed newlines); the fold cases (accents, "Anglais", "electro", the `œ` ligature, upper case,
  plain); and the entire lexicon (skills with aliases + categories, languages, language aliases,
  degree/institution/certification/title markers, countries, cities, folded places, and every
  section keyword).

The core is a pure function of its inputs — no clock, no disk, no network — which is exactly why
it can be shared and why the port must match character for character. The lexicon is compared in
full, not sampled: a skill added on the Kotlin side without the TypeScript side fails the suite.

Regenerate after any Kotlin engine change:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/openhands/android-sdk
export PATH=$JAVA_HOME/bin:$PATH
./gradlew :app:testDebugUnitTest --tests "com.aiglobal.jobfinder.Stage58GoldenParityExportTest"
cd web && npm run typecheck && npm test
```

## Which sources work in the browser, and why

The web pipeline calls providers directly from the browser (`JsonFeed` → `fetch`). A provider
only works if its feed sends `Access-Control-Allow-Origin`, because the browser refuses to let
the page read a response without it.

| Provider | CORS | In the browser |
| --- | --- | --- |
| Remotive | `Access-Control-Allow-Origin: *` | Works — key-free |
| Arbeitnow | `Access-Control-Allow-Origin: *` | Works — key-free |
| Jobicy | `Access-Control-Allow-Origin: *` | Works — key-free |
| SmartRecruiters | `Access-Control-Allow-Origin: *` | Works — key-free (employer boards) |
| Himalayas | no ACAO header | Blocked — needs the proxy |
| Adzuna | key required | Needs the proxy (key must not ship) |
| Jooble | key required | Needs the proxy (key must not ship) |

So the deployed web app searches **Remotive, Arbeitnow, Jobicy and the SmartRecruiters employer
boards**. `HimalayasProvider` exists and is tested, but is not in `defaultProviders()` because a
browser fetch is refused. A blocked or failed source is named in the diagnostics strip with a
plain-words reason, never silently dropped.

## Stage 70 — the live sample is filtered to the candidate's trade

The key-free boards return a broad, newest-first global sample that is dominated by software and
office roles, so a raw feed is mostly irrelevant to a Madagascar-based industrial candidate. Stage
70 adds a **live relevance filter** rather than widening the query: `RelevanceFeed` (Android) /
`relevantJobs` + `unrelatedJobs` (web) keep only postings whose title reads as the candidate's
trade, reusing the same `RoleClassifier`/`classifyRole` verdict the rest of the pipeline already
uses. The **shortlist correction** applies the same verdict in `ShortlistEngine.build` /
`shortlist-engine.ts`, so the actionable list holds only roles the candidate can actually act on.

Two sample-widening changes support the filter. Arbeitnow now pools several pages (`?page=N`) so
trade roles sitting past the first page are found instead of missed, and the web adds the key-free
**SmartRecruiters** employer boards (SGS, Wabtec, Konecranes, Bosch), which publish maintenance,
fitter and millwright roles in the Gulf and Africa. Both are pinned by the `relevance` fixture
section (`meta.stage = 70`) and replayed field-for-field in `parity.test.ts`, so the Kotlin and
TypeScript sides stay byte-identical.

## The optional proxy

A single serverless function unlocks the rest, and it is small because the network is already
isolated behind one seam (`web/src/core/jobs/provider/feed.ts`):

- It holds the Adzuna/Jooble keys in its own environment; the keys never reach the browser.
- For Himalayas it relays the key-free feed and adds the CORS header.
- Point a provider's `JsonFeed` at `/api/jobs/<provider>` instead of the source URL.

The trade-offs and hosting steps are in `web/DEPLOYMENT.md` §5. Until the proxy exists, the
app is honest: the source appears as unavailable rather than returning nothing.

## No secrets in the browser

No credential of ours is ever shipped to the browser (`web/vite.config.ts`). The web edition
runs entirely client-side; no CV and no key is uploaded anywhere. This is why the web edition
has no remote AI assistant today — adding one would require the same proxy that holds the key
server-side.
