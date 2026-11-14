# Standards compliance — Scan Kiwi 1.2.0

Status: **Met** · **Partial** (met with a stated limit) · **Gap** (not met, with the reason).
"Test" means an automated test in `app/src/test` that runs in CI on every push.

## Security and privacy

### OWASP MASVS v2 (with MASTG checks)

| Control group | Status | How / evidence |
|---|---|---|
| MASVS-STORAGE-1/2 (sensitive data stored securely, no leakage) | Met | Documents only in app-private `filesDir`; `allowBackup=false` and data-extraction rules exclude everything from cloud backup and device transfer; exports live in `cache/exports` and are deleted after Save and at start; atomic, fsynced writes (D24). Release builds do not log (`BuildConfig.DEBUG` guard). |
| MASVS-CRYPTO-1/2 | Met | PDF passwords use the ISO 32000-2 standard security handler R6 (AES-256, SHA-256/384/512 key derivation, `SecureRandom` salts/IVs). No custom crypto primitives. Verified by decrypting with Apache PDFBox (test). |
| MASVS-AUTH | Not applicable | No accounts or server. |
| MASVS-NETWORK-1/2 | Met (by absence) | No INTERNET / ACCESS_NETWORK_STATE permission (manifest `tools:node="remove"` guards); CI step fails if any permission other than CAMERA appears. |
| MASVS-PLATFORM-1 (IPC) | Met | Only `MainActivity` is exported (launcher + share target). Incoming shares accept foreign `content://` URIs only; `file://` and our own FileProvider are refused (test). FileProvider is not exported, exposes only `cache/exports/`, read-only grants per share. No `<queries>`. |
| MASVS-PLATFORM-2/3 (WebView, UI) | Met | No WebView. Password field uses `PasswordVisualTransformation`. |
| MASVS-CODE-1..4 | Met | Target API 36, minSdk 24; dependencies pinned and SHA-256-verified (Gradle dependency verification); R8 minification; inputs validated (URIs, file names, EXIF, PDF passwords ASCII-only for SASLprep). |
| MASVS-RESILIENCE | Gap (by choice) | No anti-tamper/obfuscation beyond R8: the app is open source and holds no secrets; resilience controls would add weight without protecting users. |
| MASVS-PRIVACY-1..4 | Met | Data minimisation (no identifiers, no telemetry, camera only); problem reports show exactly what is sent, device info is opt-in and has no identifiers, never documents/images/OCR text/file names/logs (tests); user controls every export. |

### ISO/IEC 29100 privacy principles

| Principle | Status | How |
|---|---|---|
| Consent and choice | Met | Nothing leaves the phone without an explicit Share/Save/Report action. |
| Purpose specification, collection limitation, data minimisation | Met | No collection at all; camera frames are processed in memory. |
| Use, retention and disclosure limitation | Met | Local storage only; user deletes documents; exports cleaned. |
| Openness, transparency | Met | Privacy text in app, README; open source; network statement on the update path (GitHub sees the IP address when the user's browser/Obtainium contacts it). |
| Information security | Met | See MASVS above. |
| Accountability | Partial | SECURITY.md with coordinated disclosure; no formal DPO (no personal data is processed by FastKiwi). |

## Accessibility (WCAG 2.2 AA via EN 301 549 / Android guidelines)

| Criterion | Status | How |
|---|---|---|
| 1.4.3 Contrast (minimum) | Met | Material 3 colour roles chosen for ≥ 4.5:1 text contrast in light and dark themes. |
| 2.5.8 Target size / Android 48 dp | Met | Material components (48 dp minimum); custom rows use `heightIn(min = 48–56 dp)`; crop handles have a 72 dp grab area. |
| 4.1.2 Name, role, value (TalkBack) | Met | Content descriptions on icons, roles on custom controls, headings, live regions for camera status and warnings; page/document actions also exposed as accessibility actions. |
| 1.4.4 Resize text (200 %) | Partial | Layouts use `sp` text and scroll; long Arabic labels wrap. Not verified on a device at 200 % font size. |
| 1.4.1 Use of colour | Met | Invalid crop shape: red outline **plus** a warning bar with text; selection states have text/state descriptions. |
| Manual check with TalkBack on a phone | Gap | No device or emulator available to the build; to be done before a store listing. |

## Platform quality

| Standard | Status | How |
|---|---|---|
| Android core app quality | Partial | Back navigation, state survives recreation, no ANR-prone work on the main thread (share lag fixed and tested), offline behaviour, permissions requested in context. Not run through the full device test list (no device). |
| Material 3 | Met | Compose Material 3 components, dynamic-free brand colour scheme, light/dark. A redesign spec is pending (owner request). |
| Target API policy | Met | targetSdk 36 (Google Play requirement since 31 Aug 2026). |
| 16 KB page size | Met | All 64-bit native libraries have 16 KB ELF segment alignment (checked on every release). |

## PDF

| Standard | Status | How |
|---|---|---|
| ISO 32000-1/-2 structure | Met | Own writer: correct xref (20-byte entries), trailer `/ID`, streams with exact `/Length`, UTF-16BE text strings, ToUnicode CMap; parsed and text-extracted by Apache PDFBox in tests (incl. a 200-page file). |
| ISO 32000-2 §7.6.4 encryption (R6, AES-256) | Met | PDFBox opens with the password, refuses without (test). |
| PDF/A-2b (ISO 19005-2) — «أرشيفي» option | Met | sRGB output intent (CC0 ICC profile, 456 bytes), XMP metadata mirroring the Info dictionary, embedded glyphless font for the text layer, no encryption. **Validated with veraPDF** (PDFA_2_B profile) for pages with and without OCR text; a non-archival file is rejected by the same check (control test). |

## Internationalisation

| Standard | Status | How |
|---|---|---|
| BCP 47 language tags | Met | `locales_config.xml` (`ar`, `en`), `LocaleList.forLanguageTags`, `Locale.toLanguageTag()` in reports. |
| Unicode bidi (UAX #9) | Met | Compose/Android text layout handles mixed Arabic/English; the PDF text layer stores RTL runs in visual order using `java.text.Bidi` so readers return logical order (PDFBox extraction test with Arabic). |
| CLDR digits | Met | Numbers are formatted through the platform locale (ICU/CLDR), so Arabic UI shows the digits CLDR specifies for the user's Arabic locale; file names use ASCII digits on purpose (portability). |

## Versioning and release

| Practice | Status | How |
|---|---|---|
| SemVer 2.0.0 | Met | 1.2.0 = new features, no breaking change; tag `vX.Y.Z` = `versionName`; `versionCode` strictly increasing. |
| Keep a Changelog 1.1.0 | Met | `CHANGELOG.md`, Arabic then English. |
| Published SHA-256 per APK | Met | `dist/SHA256SUMS.txt`, README instructions. |
| Reproducible builds | Partial | Pinned toolchain (JDK 21.0.13+9, Gradle 8.14.3 with wrapper checksum, AGP 8.13.2, build-tools 36.0.0), verified dependencies, `dependenciesInfo` blob excluded. The **unsigned** release APK is expected to be reproducible with the same toolchain; not yet independently verified bit-for-bit, and the signature naturally differs. |

## Supply chain (OpenSSF)

| Practice | Status | How |
|---|---|---|
| Pinned dependencies (actions by full SHA) | Met | Every `uses:` pinned to a 40-character commit SHA. |
| Token permissions | Met | Top-level `permissions: contents: read`; only the Scorecard job gets `security-events: write` and `id-token: write`. |
| No dangerous workflows | Met | No `pull_request_target`, no secrets, no signing in CI, `persist-credentials: false`. |
| Dependency update tool | Met | Dependabot for `gradle` and `github-actions`, monthly, max 2 open PRs. |
| Scorecard | Met | `.github/workflows/scorecard.yml` (weekly + on push to main, results published). |
| Wrapper validation | Met | `gradle/actions/wrapper-validation` + `distributionSha256Sum`. |
| SBOM | Met | CycloneDX 1.6 JSON from the CycloneDX Gradle plugin over `releaseRuntimeClasspath`, plus hand-added native libs (Tesseract, Leptonica, libjpeg-turbo, libpng), OCR models, font and ICC profile with SHA-256 hashes. |
| Gradle dependency verification | Met | `gradle/verification-metadata.xml` (SHA-256). Platform-specific artifacts (aapt2 for Linux) added so CI verifies too. |
| Provenance | Partial | Unsigned in-toto SLSA v1 statement, Build L1, local builder (stated honestly). No CI attestation because release signing is local by design. |
| CodeQL | Gap (council decision) | Not enabled for now. |

## Licensing

| Practice | Status | How |
|---|---|---|
| REUSE / SPDX | Met | `REUSE.toml` (defaults + overrides for Tesseract data, Gradle wrapper, ICC profile, notices), `LICENSES/` texts, SPDX headers in every Kotlin source; `reuse lint` runs in CI (`fsfe/reuse-action`). Not run locally (no Python `reuse` tool on the build PC). |
| THIRD_PARTY.md complete | Met | Shipped, test-only and build-only components with licences and model/font hashes. |

## Community

| File | Status |
|---|---|
| `SECURITY.md` (GitHub private vulnerability reporting, fallback support@fastkiwi.app) | Met |
| `CODE_OF_CONDUCT.md` (adopts Contributor Covenant 2.1 by reference) | Met |
| `CONTRIBUTING.md` (issues welcome, PRs not accepted for now; release format) | Met |
| Issue templates (`bug_report.yml`, `feature_request.yml`, `config.yml`, Arabic first) | Met |
