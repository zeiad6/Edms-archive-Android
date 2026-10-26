# EDMS Archive — Android

Native Android client for **EDMS Archive** (نظام الأرشفة الإلكتروني), the electronic document management system whose desktop build lives in [`zeiad6/Edms-archive`](https://github.com/zeiad6/Edms-archive).

Two ways to run it, chosen on the login screen:

- **On this phone** — the whole system locally: SQLite database, file storage, users and permissions. No computer, no internet.
- **Connect to the desktop** — the paired desktop's UI served over a certificate-pinned HTTPS gateway, plus the pieces a web page cannot do well on a phone (document capture, downloads).

| | |
|---|---|
| Package | `com.zidex.edms` |
| Version | `1.0.3` |
| Min SDK | 26 (Android 8.0) — the camera scanner needs Google Play services; gallery import and barcode reading work offline |
| Language / UI | Kotlin · Jetpack Compose (Material 3) · Arabic + English |

Full Arabic documentation: **[README_ar.md](README_ar.md)**.

## Compatibility with the desktop

A database, a backup and a set of passwords move between desktop and phone in both directions without conversion:

- The database is the desktop's own schema, table for table and column for column (`src/lib/seed.ts` DDL).
- Passwords are scrypt (`scrypt$32768$8$1$salt$hash`) — Android ships no scrypt, so [RFC 7914](https://www.rfc-editor.org/rfc/rfc7914) is implemented in `data/Passwords.kt` and checked against the RFC test vectors.
- Files live under the same `storage/documents/YYYY/MM/<hex>.<ext>` layout, thumbnails under `storage/thumbnails/…`.
- Backups are ZIPs with the desktop's `manifest.json` + `database/` + `storage/` layout. `data/Backup.kt` refuses anything that is not an EDMS archive and guards against zip-slip.

## Trust model

`Link.kt` implements it:

- Pairing (`edms://pair?h=…&p=…&fp=…&c=…`) carries the SHA-256 of the desktop's self-signed certificate.
- Every native and WebView connection must present exactly that certificate, so the same pin works on a LAN IP, a DDNS name or a VPN address. `onReceivedSslError` proceeds only for the pinned certificate on the paired origin; anything else is cancelled.
- The device token is stored AES-GCM encrypted under a key that never leaves the Android Keystore.
- The WebView runs with `allowFileAccess = false` and hands unknown URLs to the system browser.

## Build

Requires JDK 17 and the Android SDK (compile SDK 35).

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:testDebugUnitTest  # JVM unit tests (scrypt, versions, storage guard)
./gradlew :app:assembleRelease    # R8 + resource shrinking, signed if a key is present
```

### Signing

The release key is **not** in this repository. `app/build.gradle.kts` reads it from environment variables first, then from an untracked `keystore.properties` at the repository root:

| Variable | `keystore.properties` key |
|---|---|
| `EDMS_KEYSTORE` | `storeFile` |
| `EDMS_STORE_PASSWORD` | `storePassword` |
| `EDMS_KEY_ALIAS` | `keyAlias` |
| `EDMS_KEY_PASSWORD` | `keyPassword` |

Every update must be signed with the **same** key, or Android refuses to install it over an existing copy. Without a key, `assembleRelease` still produces an unsigned, fully protected APK.

### Code protection

The desktop build ships JavaScript, so its logic is readable by anyone. The Android build has to resist the same inspection, so the release APK is run through R8 in **full mode** with resource shrinking:

- class and method names are replaced (`com.zidex.edms.data.Approval` → `a.Y6`), and every class is repackaged into a single flat package;
- source file names, line numbers and source-level signatures are stripped, compile-time string constants are folded, and `android.util.Log` calls are removed with their literals;
- resource names and file contents are renamed by AAPT2.

The only names that survive are the ones the platform needs: manifest components (the manifest is rewritten to match), views inflated from XML, `@JavascriptInterface` bridge methods, and ML Kit's scanner entry points. `app/proguard-rules.pro` documents each one.

Two attribute groups are deliberately **not** kept, because both write original names back into the DEX even after the class was renamed:

- `Signature` — every Kotlin callable reference (`::Document.of`, used throughout `Repo.kt`) compiles to a synthetic class whose generic signature is the method's own descriptor, e.g. `of(Landroid/database/Cursor;)Lcom/zidex/edms/data/Document;`. Keeping `Signature` leaked the name of every model class.
- `InnerClasses` / `EnclosingMethod` — both record the declaring class as a type descriptor.

What is left is `Lcom/zidex/edms/MainActivity;` and `Lcom/zidex/edms/remote/RemoteActivity;`, because AGP emits a strict `-keep class …` for every manifest component and that cannot be loosened from the app's rules file. The package name itself is public by construction (manifest, Play Store, `packageName` at runtime) — what CI enforces is that no *business-logic* class name survives.

`app/build/outputs/mapping/release/mapping.txt` turns a release stack trace back into source names. It is uploaded as a 90-day CI artifact and never published as a release asset.

## CI

| Workflow | Trigger | What it does |
|---|---|---|
| `.github/workflows/build.yml` | every push, PR, manual | unit tests → debug APK → release APK, then greps the release DEX for original `Lcom/zidex/edms` class descriptors and **fails the build** on any beyond the two manifest activities, printing the leaked ones. Uploads the APKs, the R8 mapping and the merged R8 configuration. |
| `.github/workflows/release.yml` | tag `v*` | assembles the signed release APK, verifies the signature and the obfuscation, deletes the key material from the runner, attaches the APK to the GitHub release. Requires the `EDMS_*` repository secrets. |

The in-app updater reads the newest release from `zeiad6/Edms-archive-Android`. A release is published by pushing a `v*` tag; CI signs it with the pinned release key and refuses the build if the certificate does not match the one existing installs trust.

## Layout

```
app/src/main/java/com/zidex/edms/
├── MainActivity.kt          entry point
├── Link.kt                  pairing, cert pinning, encrypted token, HTTP
├── Updater.kt               in-app update from GitHub releases
├── data/                    Db · Repo (queries + audit) · Models + Perm · Storage · Backup · Passwords
├── scan/Enhance.kt          scan post-processing
├── remote/RemoteActivity.kt desktop UI in a locked-down WebView + native capture
└── ui/                      Compose screens (login, shell, documents, approvals, admin, settings)
```
