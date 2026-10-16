# Stage 27 — install and verify on a real Android phone

This is the practical checklist for putting **AI Global Job Finder** on your own
Android phone and checking that it does what it claims. It is written for manual
testing: you install the app, tap through each area, and compare what you see with
what is written here.

The app is for one user (you), so nothing here needs a second account or a test
device farm.

---

## 0. What is automated and what is not

Be clear about the difference, because "it passed" means different things in each row.

| Layer | What it proves | How you run it | Status |
| --- | --- | --- | --- |
| Unit / pipeline tests | Scoring, eligibility, benefits, shortlist ranking, scam rules, persistence and the repository race fix behave as specified | `./gradlew :app:testDebugUnitTest` | 346 tests, 0 failures, 2 intentional skips (opt-in live-network probes) |
| Screenshot tests | The real Compose screens render, in light and dark, at a narrow width | `./gradlew :app:testDebugUnitTest --tests "*ScreenshotTest"` | Pass; images under `app/build/screenshots/` |
| Lint | There are no lint errors | `./gradlew :app:lintDebug` | 0 errors, 52 pre-existing warnings |
| Build | Installable artifacts are produced | `./gradlew :app:assembleDebug` / `:app:assembleRelease` | Both succeed |
| Emulator test | The app runs on Android | `./gradlew :app:connectedDebugAndroidTest` | **Not run** — this container has no KVM, so no emulator can boot |
| Physical-device test | The app works on your actual phone with your network and your CV | You, following the checklist below | **Not done** — no phone is attached to the build machine |

So the automated layers prove the logic and the rendering. Only sections 1–10 below
prove the *real-world* experience, and only you can do those.

---

## 1. Install the app

The debug build is the one to install while testing:

- File: `app/build/outputs/apk/debug/app-debug.apk`
- Package: `com.aiglobal.jobfinder.debug` (the `.debug` suffix lets it sit beside a
  future release build without clashing)
- Version: name `1.0.0-debug`, code `1`
- Signing: signed with the standard Android **debug** key. That is fine for your own
  phone, and it is **not** suitable for sharing or publishing.

Install it one of these ways:

1. **ADB (USB debugging on).** Connect the phone, then:
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. **Copy the file.** Copy the APK to the phone and tap it. Android will ask you to
   allow "install from unknown sources" for your file manager or browser — allow it,
   install, then you can turn the permission back off.

The release APK (`app/build/outputs/apk/release/app-release-unsigned.apk`) is
**unsigned** and will be refused by Android's package installer. It is a build
artifact only; do not try to install it as-is. To make a distribution build you would
first create a signing key and add a `signingConfig` — that is deliberately out of
scope here.

---

## 2. First launch and CV import

- Open the app. The Home screen greets you and shows empty/stat placeholders rather
  than inventing data. On a fresh install there is no profile yet, which is correct.
- Go to **Profile**. It should show its empty state (no name, no skills) because no CV
  has been imported — the app never fabricates a candidate.
- Import a CV (PDF). After import, confirm:
  - Your name and current title appear.
  - Skills, experience and education are populated from the document, not made up.
  - If a field is missing from the CV, it stays blank rather than being guessed.
- Importing a CV should re-score the list without a network call.

## 3. Real job refresh and source attribution

- Go to **Jobs** (or the Shortlist/refresh entry) and trigger a refresh.
- Confirm each posting shows its **source** (Canada Job Bank, Greenhouse, Lever, etc.)
  and that a source is never credited with a job it did not return.
- Turn on airplane mode and refresh again:
  - The list keeps the last good postings (nothing is wiped).
  - Sources read "waiting for network", **not** "failed".
  - A cached-only note appears, so cached data is never shown as fresh.
- Turn airplane mode off and refresh; sources return to success.

## 4. Technical job matching and shortlist quality

- Open the **Shortlist** screen.
- Check the *Shortlist quality* card: it states how many postings were screened, how
  many are open to you, the share, the best readiness, and the leading country — and it
  ends with "verify before you invest time". It must never call a thin list strong.
- Confirm the list is split into **"Ready to act on"** and **"Worth preparing for"**,
  with the preparation section showing a few cards and a "Show N more" button.
- Sanity-check a match: the skills shown as matched should be ones you actually have.

## 5. Eligibility from Madagascar and unknown visa/benefit evidence

This is the most important honesty check.

- For a posting whose text never mentions visa or relocation, the app must say the
  route is **not stated** ("Eligibility not stated" / "Check eligibility first"), and
  must **not** claim the employer refused.
- For a posting that documents sponsorship, it should show it as stated, and where the
  wording is thin, ask you to confirm the terms rather than promising them.
- A posting restricted to local applicants must be shown as **not open to you** — and
  must not be listed as "worth preparing for".
- Nothing that is unknown may be displayed as if it were known.

## 6. Next-step guidance for each job

- Open a posting's explanation/sheet.
- Confirm it names **one** concrete next step ("Tailor your CV", "Run the scam check",
  "Complete your profile", "Check eligibility first", "Ask about the package").
- The advice must match the blocker shown on the same card — never contradict it.

## 7. Opening the original employer application link

- Tap **Apply**. The app opens the employer's real URL in the browser.
- Confirm the domain is the employer's, not an aggregator trap.
- Opening the link must **not** mark the application as submitted. An application is
  only "submitted" when you record that you actually did it.

## 8. CV and motivation-letter editing

- Edit your CV in the app; confirm changes persist after closing and reopening.
- Generate a motivation letter for a job; confirm it is specific to that job and that
  you can edit and save it.
- Confirm the app never auto-submits an application anywhere. Auto-apply, if you turn
  it on, prepares material only — you stay in control of sending.

## 9. Offline refresh and recovery

- Covered partly in section 3. Additionally: kill the app while offline, reopen it, and
  confirm it shows the cached postings with an honest age, then recovers on the next
  successful refresh.

## 10. Notifications, permissions and themes

- Grant the notification permission when asked; trigger a refresh with new postings and
  confirm a notification appears. Deny it and confirm the app still works, just quietly.
- Switch the system between light and dark mode. Both themes must stay readable: cards,
  text, icons, buttons, inputs and the bottom nav all remain legible and consistent.
- Rotate the phone or use a small screen; confirm nothing is clipped or overlapping.

---

## What is still not verified

- **No emulator or physical-device run** happened on the build machine, so touch
  behaviour, real fonts, notification delivery and the actual network path are verified
  only by you, on your phone.
- The **release APK is unsigned**; it is not install-ready for distribution.
- The two skipped tests are opt-in live-network probes (`LIVE_PROBE=1`); they are not
  failures.
