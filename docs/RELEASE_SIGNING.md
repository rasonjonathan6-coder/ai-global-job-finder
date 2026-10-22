# Release signing — AI Global Job Finder

Status: **no release keystore is present in this environment.** The key that signed the
currently published APK was created in an earlier session, stored **outside** the repository,
and has been **lost** (the ephemeral home directory that held it was wiped). This document is
the permanent record of what was lost, what the published artifact expects, and how to store a
key durably so it never happens again.

## 1. The published artifact (what the key must match)

The APK published for download is signed. Its exact identity is recorded here so any future key
can be checked against it.

| Field | Value |
| --- | --- |
| Published file | `web/public/download/AI-Global-Job-Finder-release.apk` (also `web/dist/download/…`, `web/release/stage72-1/…PUBLISHED-unchanged.apk`, `/workspace/download/…`) |
| APK SHA-256 | `8440b7c374b90a086754ab46cbb5f2a30d2e98294bc7e828636a7fa4f0deb6df` |
| Signer certificate DN | `CN=AI Global Job Finder, OU=Personal, O=AI Global Job Finder, L=Antananarivo, C=MG` |
| Certificate SHA-256 | `6f6cc6d01648920b1287f0719b78ff627965a0bac618bfb54081103eca59a932` |
| Certificate SHA-1 | `85d519b986779ead2c5394abf02bd696b8e7c479` |
| Public key SHA-256 | `e475268ff2347f620c76e4df318eb230e3bc5f52d82d1f1dc694a7dad87cb8aa` |
| Key algorithm / size | RSA 2048 |
| Signature schemes | APK Signature Scheme v2 + v3 (v1 JAR signing off) |

Re-check at any time with:

```
ANDROID_HOME=/home/openhands/android-sdk
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs --verbose \
  web/public/download/AI-Global-Job-Finder-release.apk
```

## 2. Recovery search result (Stage 85, read-only)

The original keystore was searched for exhaustively and **was not found**. Every plausible
location and source was checked:

- `$HOME` candidates — `/home/openhands/keys/`, `/home/openhands/aigjf-signing/`,
  `/home/openhands/AndroidSigning/`, `/root` — all absent; no external mounts (`/mnt`, `/media`,
  `/srv`, `/opt` all empty).
- Content scan for the Java KeyStore magic header (`FE ED FE ED`) across `/home`, `/workspace`,
  `/var`, `/opt`, `/srv`, `/mnt`, `/media`, `/root` — no match.
- Git: no `*.jks` / `*.keystore` ever added to history; all 2 465 objects scanned — no keystore
  blob; reflog shows no keystore commit.
- Backups: `project-backup-20261019-063643.tgz`, `conversation-4ba2e8ff-backup-20261019-090313.tgz`
  and `backups_20261015/stage25-history.bundle` — no keystore by name or by magic bytes.
- Shell histories (`~/.bash_history`, `~/.zsh_history`, `/root/.bash_history`) — absent/empty.
- Secret registry holds **names only** (`KEYSTORE_PASSWORD`, `TANGO_KEYSTORE_PASSWORD`,
  `TANGO_KEY_PASSWORD`); no keystore file is registered and the generic password cannot
  reconstruct a key.

Two keystores were generated in earlier sessions. Only the second one matches the published APK:

| Keystore (historical path) | Alias | `-dname` used | Matches published APK? |
| --- | --- | --- | --- |
| `/home/openhands/aigjf-signing/aigjf-release.jks` | `aigjf` | `CN=AI Global Job Finder, OU=Personal, O=Personal, L=Antananarivo, C=MG` | No |
| `/home/openhands/keys/aiglobal-release.jks` | `aiglobal` | `CN=AI Global Job Finder, OU=Personal, O=AI Global Job Finder, L=Antananarivo, C=MG` | **Yes** |

Both files are gone. Because a keystore's private key cannot be derived from its password or
from the signed APK, the published artifact **cannot be re-signed or updated** without the
original file. Anyone who installed the published APK cannot receive an in-place update under
the same identity unless the original keystore is supplied from an external backup (personal
cloud drive, password manager, or offline copy).

## 3. Permanent storage policy

The repository must never hold signing material. Keys live **outside** the working tree and are
ignored defensively:

- `.gitignore` now excludes `*.jks`, `*.keystore`, `*.p12`, `*.pfx`, `keystore.properties`,
  `signing.properties`.
- Recommended on-disk location (outside the repo, survives `git clean`):
  `$HOME/keys/aiglobal-release.jks`.
- Passwords are read from environment variables, never hard-coded:
  `RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEY_PASSWORD`.
- **Durability requirement:** the keystore file **and** its password must be backed up to a
  location that outlives this environment (personal cloud storage / password manager). A key
  that exists only in an ephemeral container will be lost again — exactly what happened here.

### Creating the key (only when authorised)

```
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mkdir -p "$HOME/keys" && cd "$HOME/keys"
keytool -genkeypair -v \
  -keystore aiglobal-release.jks \
  -alias aiglobal \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass "$RELEASE_KEYSTORE_PASSWORD" \
  -keypass  "$RELEASE_KEY_PASSWORD" \
  -dname "CN=AI Global Job Finder, OU=Personal, O=AI Global Job Finder, L=Antananarivo, C=MG"
```

Immediately back up `aiglobal-release.jks` and its password outside the environment, then record
the new certificate SHA-256 in this file. Use a strong, unique password; never commit it.

### Signing a release build

```
ANDROID_HOME=/home/openhands/android-sdk
BT=$ANDROID_HOME/build-tools/34.0.0
$BT/apksigner sign --ks "$RELEASE_KEYSTORE_PATH" --ks-key-alias "$RELEASE_KEY_ALIAS" \
  --ks-pass env:RELEASE_KEYSTORE_PASSWORD --key-pass env:RELEASE_KEY_PASSWORD \
  --out app-release-signed.apk app/build/outputs/apk/release/app-release-unsigned.apk
$BT/apksigner verify --print-certs app-release-signed.apk
```

## 4. Decision required

Because the original key is unrecoverable, the project must choose one of:

1. **Supply the original keystore** from an external backup, restoring update continuity for the
   published APK; or
2. **Rotate to a new key** (create, store and back up as in section 3). The published APK keeps
   its current identity but cannot be updated in place; a new key signs all future releases.

Wiring a release `signingConfig` into `app/build.gradle.kts` (the Stage 84 proposal) should be
done only once one of the above is chosen.
