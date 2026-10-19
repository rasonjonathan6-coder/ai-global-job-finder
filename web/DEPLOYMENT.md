# Deploying the AI Global Job Finder web / PWA edition

This document covers Stage 60: how the Vite PWA in `web/` is built, how to host it over HTTPS
for free, what works in production and what does not, and how to update and install it.

The Android application is untouched by anything in this document. The web edition is a
separate, static bundle under `web/`.

---

## 1. Production build audit

`npm run build` runs `tsc --noEmit && vite build`, then the PWA plugin generates the service
worker. The output lands in `web/dist/`:

| File | Size | Notes |
| --- | --- | --- |
| `index.html` | 1.07 kB (0.56 kB gzip) | App shell; references hashed assets |
| `assets/index-<hash>.js` | 271.58 kB (85.76 kB gzip) | Application bundle |
| `assets/index-<hash>.css` | 29.16 kB (6.01 kB gzip) | Tailwind build |
| `manifest.webmanifest` | 0.65 kB | PWA manifest |
| `sw.js` | — | Workbox service worker |
| `workbox-<hash>.js` | — | Workbox runtime, imported by `sw.js` |
| `registerSW.js` | 0.14 kB | Registers the worker |
| `favicon.svg` | 1.06 kB | Vector icon |
| `icons/icon-192.png` | 12.7 kB | 192×192 |
| `icons/icon-512.png` | 35.9 kB | 512×512 |
| `icons/icon-maskable-512.png` | 34.3 kB | 512×512, `purpose: maskable` |

The precache manifest lists **13 entries (377.27 KiB)**.

**Hosting model.** This is a purely static SPA. There is no server, no build-time secret, and
no server-side rendering. Any static host that serves files over HTTPS works.

**Base path.** `vite.config.ts` sets `base: "./"`, so every asset URL in `dist/index.html` is
relative (`./assets/...`, `./sw.js`, `./manifest.webmanifest`). The bundle is therefore
host-agnostic: it works at a domain root (`https://example.com/`) and in a subdirectory
(`https://example.com/jobfinder/`) without a rebuild. This is what makes the "simplest free
host" recommendation in section 2 safe.

**Routing.** Navigation is hash-based (`#/home`, `#/jobs`, `#/applications`, `#/ai`,
`#/profile`; see `web/src/ui/navigation/useHashRoute.ts`). A hash route never reaches the
server, so deep links and refresh already work on any host. The SPA fallback rewrite
(`/* → /index.html`) is a safety net for a hard refresh on a path-style URL, not a hard
requirement.

---

## 2. Recommended hosting

The simplest reliable free HTTPS option for this project is **Netlify** (free tier), with
**Cloudflare Pages** and **Vercel** as equally valid alternatives. All three give:

- automatic HTTPS with a managed certificate;
- a free tier that covers a personal project;
- global CDN and instant cache invalidation on deploy;
- a GitHub integration that rebuilds on push, or a CLI for one-off deploys.

`netlify.toml` and `vercel.json` at the repository root are committed so the settings below
are applied automatically instead of being re-entered in a dashboard. They build the `web/`
subproject and publish `web/dist/`; they do not affect the Android build.

> **Security note.** Do not ship any API key to the browser. This app calls only key-free,
> CORS-enabled job feeds (section 4). Providers that need a key (Adzuna, Jooble) or that block
> browser requests (Himalayas) require a small server-side proxy, described in section 5.

---

## 3. Deployment instructions

Pick one path. Each assumes the repository contains `web/` and that `web/dist/` is produced by
`npm ci && npm run build`.

### Option A — Netlify, connect the Git repository (recommended)

1. Push the repository to GitHub/GitLab (see the caution below — no push was performed in
   Stage 60).
2. In Netlify: **Add new site → Import an existing project**, pick the repository.
3. Leave **Base directory** empty. Netlify reads the root `netlify.toml`, which runs
   `npm --prefix web ci && npm --prefix web run build` and publishes `web/dist`.
   (If you prefer the UI fields instead of the file: base `web`, build command
   `npm ci && npm run build`, publish directory `dist`.)
4. Deploy. Netlify assigns a `https://<name>.netlify.app` URL.

### Option B — Netlify, manual deploy from this machine

```bash
cd web
npm ci
npm run build
npx netlify-cli deploy --prod --dir=dist
```

The first run opens a browser to authenticate, or accepts `NETLIFY_AUTH_TOKEN`. The command
prints the live URL.

### Option C — Cloudflare Pages

```bash
cd web
npm ci
npm run build
npx wrangler pages deploy dist --project-name ai-global-job-finder
```

Or connect the Git repo in the Cloudflare dashboard with build command `npm run build`, build
output directory `dist`, and root directory `web`. Add a `_redirects` file with
`/* /index.html 200` if you want the SPA fallback (the hash router makes this optional).

### Option D — Vercel

```bash
cd web
npm ci
npx vercel --prod
```

The root `vercel.json` sets the build command, output directory and cache headers. When you
run `vercel` from `web/`, Vercel detects the Vite framework; the committed file applies when
the project root is the repository root.

### Environment variables

**Required for the frontend: none.** The bundle contains no `import.meta.env` reads and no
`VITE_*` variables; it is self-contained. Nothing has to be configured in the host's dashboard
for the app to run.

**Optional, only if you add the proxy stage in section 5.** Those variables live on the
proxy/function, never in the frontend bundle:

| Variable | Purpose |
| --- | --- |
| `ADZUNA_APP_ID` / `ADZUNA_APP_KEY` | Adzuna aggregation (keyed provider) |
| `JOOBLE_API_KEY` | Jooble aggregation (keyed provider) |

Do not create `VITE_`-prefixed variables for these: Vite inlines `VITE_*` values into the
JavaScript bundle, where every visitor can read them.

---

## 4. Which live job providers work in production

The web pipeline calls providers directly from the browser. A provider only works if its feed
sends `Access-Control-Allow-Origin`, so the browser is allowed to read the response.

Verified live on 2026-10-13 with an `Origin` header:

| Provider | Endpoint | CORS | Status in production |
| --- | --- | --- | --- |
| Remotive | `https://remotive.com/api/remote-jobs` | `Access-Control-Allow-Origin: *` | **Works** — key-free |
| Arbeitnow | `https://www.arbeitnow.com/api/job-board-api` | `Access-Control-Allow-Origin: *` | **Works** — key-free |
| Jobicy | `https://jobicy.com/api/v2/remote-jobs` | `Access-Control-Allow-Origin: *` | **Works** — key-free |
| Himalayas | `https://himalayas.app/jobs/api` | *(no ACAO header)* | **Blocked in the browser** — needs a proxy |

So the deployed app searches **Remotive, Arbeitnow and Jobicy** for real postings. In a local
check these three returned roughly 12 / 60 / 50 postings respectively.

**Requires a secure backend (not wired for the browser):**

- **Himalayas** — no CORS header, so a browser fetch is refused. Its feed is otherwise
  key-free, so a thin server-side proxy (below) would unlock it.
- **Adzuna** — requires an application id/key. Shipping the key to the browser would expose it
  to every visitor, so it must be proxied.
- **Jooble** — requires an API key. Same reasoning as Adzuna.

**Not live results, by design:** the demo fixtures (`web/src/ui/lib/sample-jobs.ts`) are only
served when demo mode is switched on, and the source strip labels them "Demo fixtures". They
are never presented as live job results.

---

## 5. Optional backend proxy (for Himalayas, Adzuna, Jooble)

If you want those sources, add a single serverless function that fetches the feed server-side
and returns it with `Access-Control-Allow-Origin` set to your site's origin. It is small
because the app already isolates the network behind one seam, `JsonFeed`
(`web/src/core/jobs/provider/feed.ts`).

- The function holds the Adzuna/Jooble keys in its own environment; the keys never reach the
  browser.
- For Himalayas, the function simply relays the key-free feed and adds the CORS header.
- Point a provider's `JsonFeed` at `/api/jobs/<provider>` instead of the source URL.

This is the one feature in this stage that genuinely requires server infrastructure. Until it
exists, the app is honest about it: a blocked source appears in the diagnostics strip with a
plain-words reason rather than silently returning nothing.

---

## 6. Deployment status

**Not deployed.** No hosting token (`VERCEL_TOKEN`, `NETLIFY_AUTH_TOKEN`,
`CLOUDFLARE_API_TOKEN`, `FIREBASE_TOKEN`, `SURGE_TOKEN`) is present in this environment, the
repository has no Git remote configured, and pushing to a remote is out of scope for this
stage. There is therefore **no public URL**, and none is claimed here. Everything needed to
deploy is in place: the verified build, `netlify.toml`, `vercel.json`, the release ZIP
(section 10) and the steps above.

To go live, run one of the Option A–D commands; the host will print the real URL. Report that
URL back to have it verified.

---

## 7. Updating the site

Because assets are content-hashed and the service worker uses `registerType: "autoUpdate"`, a
deploy is picked up without user action — but there is a nuance worth knowing:

1. Make the change under `web/`.
2. Run `npm run typecheck && npm test && npm run build`.
3. Deploy (`git push` for Git-connected hosts, or the CLI command from section 3).
4. The new `sw.js` calls `skipWaiting()` and `clientsClaim()`, so an open tab takes control of
   the new worker and serves the new bundle on the next navigation. If a tab still shows old
   content, one reload completes the swap. The host headers set `Cache-Control:
   max-age=0, must-revalidate` on `sw.js` and `registerSW.js` so the CDN never pins a stale
   worker.

Job feeds are deliberately **not** cached by the service worker, so a refresh always reaches
the live sources; only the app shell is precached.

---

## 8. Install on a phone (Add to Home Screen)

The app is a standalone PWA: once installed it opens without browser chrome and has its own
icon.

### Android (Chrome / Edge)

1. Open the deployed `https://…` URL in Chrome.
2. Tap the **⋮** menu → **Install app** (or **Add to Home screen**).
3. Confirm. The icon appears in the app drawer and launches in standalone mode.

Chrome may also show an automatic "Install" prompt in the address bar.

### iPhone / iPad (Safari)

1. Open the URL in **Safari** (Add to Home Screen is not available in Chrome on iOS).
2. Tap the **Share** button → **Add to Home Screen**.
3. Name it and tap **Add**. It launches full-screen from the Home Screen.

`index.html` already provides `apple-touch-icon`, a themed `theme-color` for light and dark,
and `viewport-fit=cover` for notch-safe layout. iOS requires HTTPS for install and for the
service worker, which every host in section 2 provides.

---

## 9. Verification performed (Stage 60)

Commands were run from `web/`:

- `npm run typecheck` (`tsc --noEmit`) — **passed, no errors**.
- `npx vitest run` — **256 tests passed, 5 files, 0 failures**
  (providers 14, job-mapper 87, parity 147, format 6, BottomNav 2).
- `npm run build` — **succeeded**; assets 271.58 kB JS / 29.16 kB CSS; PWA precache
  13 entries (377.27 KiB); `sw.js` and `workbox-<hash>.js` generated.

Serving the production bundle with `vite preview` and requesting each path returned the
expected status and content type:

| Path | Status | Content-Type |
| --- | --- | --- |
| `/` | 200 | `text/html` |
| `/jobs` | 200 | `text/html` (app shell — SPA fallback works) |
| `/manifest.webmanifest` | 200 | `application/manifest+json` |
| `/sw.js` | 200 | `text/javascript` |
| `/registerSW.js` | 200 | `text/javascript` |
| `/icons/icon-192.png` | 200 | `image/png` |
| `/favicon.svg` | 200 | `image/svg+xml` |
| `/assets/index-<hash>.js` | 200 | `text/javascript` |

The `dist/` output was scanned for common secret patterns (API-key prefixes, JWTs,
`supabase.co`, and the names of this workspace's registered secrets) and **none were found**.
The Android project was not modified.

## 10. Release package (Stage 61)

A clean, upload-ready archive is produced by:

```bash
cd web
npm run build            # tsc --noEmit && vite build
npm run release:package  # python3 scripts/package-release.py
```

Output: **`web/release/ai-global-job-finder-web-v1.0.0.zip`** (182,948 bytes, 12 entries).
A companion `…zip.sha256` file records the digest. `release/` is git-ignored: it is a build
artifact, regenerated on demand.

The archive contains the deployable output **at its root** (no wrapper folder), so Netlify's
"Deploy manually" drop zone accepts it directly:

| Entry | Purpose |
| --- | --- |
| `index.html` | App shell |
| `assets/index-<hash>.js`, `assets/index-<hash>.css` | Hashed, immutable assets |
| `manifest.webmanifest` | PWA manifest |
| `sw.js`, `workbox-<hash>.js`, `registerSW.js` | Service worker + runtime |
| `favicon.svg`, `icons/icon-192.png`, `icons/icon-512.png`, `icons/icon-maskable-512.png` | Icons |
| `_redirects` | `/* /index.html 200` SPA fallback for Netlify |

It contains **no source files, no `.envrc-local`, and no credentials** — the packager reads
only `dist/` and adds `_redirects`. `scripts/package-release.py` refuses to run if a required
file (manifest, service worker, Workbox runtime, icons, hashed assets) is missing.

### Uploading the ZIP to Netlify and verifying HTTPS

1. Sign in at <https://app.netlify.com>.
2. Open the target site (or create one: **Add new site → Deploy manually**).
3. Go to **Deploys**, then drag `web/release/ai-global-job-finder-web-v1.0.0.zip` onto the
   **"Drag and drop your site output folder here"** area. Netlify unpacks it and publishes it.
4. Wait for the deploy to reach **Published**. Netlify provisions a TLS certificate
   automatically; the site is served at `https://<site-name>.netlify.app`.
5. Verify: open the HTTPS URL. The app should load, the Jobs tab should show live postings
   (Remotive / Arbeitnow / Jobicy), and the browser should offer **Install** / **Add to Home
   Screen**. Confirm `https://<site-name>.netlify.app/manifest.webmanifest` and
   `https://<site-name>.netlify.app/sw.js` both return 200.

To redeploy after a change: rebuild, re-run `npm run release:package`, and drop the new ZIP on
the same Deploys page. The service worker auto-updates (`skipWaiting` + `clientsClaim`).
