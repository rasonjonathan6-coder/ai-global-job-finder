# AI Global Job Finder

An AI-powered job assistant for someone job-seeking from **Madagascar**. It imports your CV,
reads your skills, experience and education, searches **real** international job postings from
legitimate sources, checks whether you can realistically apply **from Madagascar**, and then
scores the match, helps tailor your CV, drafts cover letters, tracks applications, warns about
scams, coaches you for interviews, and offers an optional (human-in-the-loop) assisted apply.

This repository holds **two editions of the same product**:

| Edition | Path | Stack |
| --- | --- | --- |
| Android app | repository root (`app/`) | Kotlin, Jetpack Compose, Material 3, MVVM, DataStore |
| Web / PWA edition | `web/` | Vite, React, TypeScript, Tailwind, PWA (Workbox) |

The web edition mirrors the Android architecture, ranking/eligibility/expat logic, navigation,
themes and features. Their shared engines are proven equal by **golden parity fixtures** exported
from the Kotlin code (`web/src/core/parity/`).

---

## Download the Android app

The signed release APK (`com.aiglobal.jobfinder`, v1.0.0) is published on the
[Releases](../../releases/latest) page. Install it on Android 8.0+ (minSdk 26) by allowing
"Install unknown apps" for your browser.

A copy also ships inside the web bundle at `download.html`, so the PWA host can serve it too.

## Run the web edition

```bash
cd web
npm ci
npm run dev        # http://localhost:5173
npm run build      # production PWA bundle in web/dist
npm test           # Vitest (parity + component tests)
```

See `web/DEPLOYMENT.md` for hosting (Netlify / Vercel / Cloudflare Pages, or GitHub Pages).

## Build the Android app

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # release APK (sign it before installing)
./gradlew :app:testDebugUnitTest  # unit tests
```

## How it is built

Development is organised in numbered **stages**, each recorded in `AGENTS.md` — the project's
living design log. It documents the scoring engines, the eligibility rules for Madagascar, the
expat benefit model, the scam shield, and the parity guarantees between the two editions.

## Notes

- The Android app stores your CV locally and encrypts any API key with the Android Keystore.
- The web edition runs entirely in the browser; no CV or credential is uploaded to any server.
- The "auto-apply" mode is **assisted** and always human-in-the-loop: it prepares, it never
  silently submits.
