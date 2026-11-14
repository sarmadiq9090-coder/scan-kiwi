# Scan Kiwi — competitor research

Written 2026-11-14, before building. Facts marked **(verified)** were checked against
public sources on that date; the rest come from general product knowledge and should be
re-checked before being quoted publicly, because free-tier limits change often.

## 1. The apps we compared

| App | Who | Core idea |
|---|---|---|
| CamScanner | INTSIG (China) | The original mass-market scanner; freemium, cloud account. |
| Adobe Scan | Adobe | Scanner front-end for Adobe Document Cloud / Acrobat. |
| Microsoft Lens | Microsoft | Free scanner tied to OneDrive/OneNote/Office. **Retired** (see below). |
| Google Drive scan / ML Kit Document Scanner | Google | Scan button in Drive; the same engine is offered to developers as the ML Kit Document Scanner API (runs inside Google Play services). |
| Genius Scan | The Grizzly Labs (France) | Privacy-minded freemium scanner; advanced features in paid plans. |
| SwiftScan | Maple Media (ex-Scanbot) | Freemium scanner; OCR and cloud features in Pro. |
| Others | TapScanner, Clear Scan, "Document Scanner – PDF Creator" clones; FOSS: OpenScan, OpenNoteScanner | Mostly ad- or subscription-driven; FOSS ones are GPL and English-only. |

## 2. Features

| Feature | CamScanner | Adobe Scan | MS Lens | Drive / ML Kit | Genius Scan | SwiftScan |
|---|---|---|---|---|---|---|
| Live edge detection + auto capture | Yes | Yes | Yes | Yes | Yes | Yes |
| Manual corner crop | Yes | Yes | Yes | Yes | Yes | Yes |
| Filters (colour, gray, B&W, shadow) | Yes | Yes | Yes | Yes (some ML "clean-up") | Yes | Yes |
| Multi-page, reorder, retake | Yes | Yes | Yes | Yes | Yes | Yes |
| Import photos | Yes | Yes | Yes | Yes (ML Kit: gallery import) | Yes | Yes |
| Import / merge existing PDFs | Paid / limited | Merge is Premium | No | No | Limited | Pro |
| OCR / searchable PDF | Limited free, more paid | Yes, cloud-assisted; 25 pages free, 100 Premium **(verified, Adobe docs)** | Yes (cloud, Microsoft account) | Drive OCR in cloud | Paid plan **(verified)** | Pro |
| Arabic OCR | Yes (cloud) | Weak / not listed | Some | Drive only, cloud | Paid | Pro |
| Password-protected PDF | Paid | Acrobat Premium | No | No | Paid **(verified)** | Pro |
| ID-card two-sides-on-one-page | Paid | Yes | No | No | No | Yes/Pro |
| Arabic UI, proper RTL | Yes | Yes | Yes | Yes | Yes | Partial |

## 3. Free-tier limits

- **CamScanner**: free exports carry a "Scanned by CamScanner" watermark; frequent full-screen ads; many tools behind a
  subscription; account needed for sync. Reviews in 2024–2026 still complain about intrusive ads and the
  watermark **(verified, user reviews on Capterra/GetApp)**.
- **Adobe Scan**: needs an Adobe ID to use the Premium features **(verified, Adobe docs)**, and in practice to use the
  app at all. Scans go to Adobe Document Cloud by default. OCR is capped at 25 pages for free users
  **(verified)**; export to Word/compress/merge are Premium.
- **Microsoft Lens**: was free with no ads or watermark, but **Microsoft retired it**. It started retiring on
  15 Sep 2025, was removed from the stores on 15 Nov 2025, and new scans stopped working after 15 Dec 2025.
  Users are sent to the Microsoft 365 Copilot app, which lacks several Lens features **(verified, Microsoft
  support page)**. Millions of free-scanner users lost their tool. **This is an opening.**
- **Google Drive scan**: needs a Google account; files land in Drive (the cloud). The ML Kit Document Scanner
  is free for developers but needs Google Play services: it does not work on phones without Google services
  (e.g. Huawei, some Chinese-market and de-Googled phones), and the UI belongs to Google and cannot be adapted.
- **Genius Scan**: the free version is ad-free with the essential scanning tools **(verified, help centre)**, but
  OCR, PDF encryption and smart naming are paid **(verified, App Store listing)**.
- **SwiftScan**: free basic scanning; OCR, cloud upload targets and several export options are Pro
  (not verified in this pass).
- **Clones (TapScanner etc.)**: aggressive subscription walls straight after install, watermarks, ads.

## 4. Privacy problems

- **CamScanner malware (Aug 2019)**: Kaspersky found that recent CamScanner builds shipped an advertising library
  containing a malicious dropper, detected as *Trojan-Dropper.AndroidOS.Necro.n*. It decrypted and ran code
  from a `mutter.zip` file in the app resources, which could download more modules, push intrusive ads or sign
  users up to paid subscriptions. The app had more than 100 million Google Play downloads; Google removed it
  after Kaspersky's report, and the developer blamed a third-party ad SDK (reported as AdHub) and shipped a fixed
  version **(verified, Kaspersky blog and press coverage)**. Lesson: **ad SDKs are a supply-chain risk.**
- **India banned CamScanner in June 2020** with 58 other apps over data-security concerns (general knowledge).
- **Cloud by default**: Adobe Scan (Document Cloud), Google Drive scan (Drive), Microsoft Lens (OneDrive) and
  CamScanner (CS cloud) all push documents (IDs, bank statements, medical papers, contracts) to servers in
  other countries. OCR is often done server-side, which means the full image leaves the phone.
- **Accounts and tracking**: sign-in walls, analytics and ad identifiers in most free scanners.
- **Backups**: even "local" apps usually let Android back their data up to the cloud by default.

## 5. Size and speed (approximate, from store listings; varies by device)

| App | Download / installed size |
|---|---|
| CamScanner | ~100–150 MB installed |
| Adobe Scan | ~80–120 MB installed |
| Microsoft Lens (before retirement) | ~50–80 MB |
| Google Drive (scan is part of it) | ~100+ MB |
| Genius Scan | ~40–60 MB |
| **Scan Kiwi v1.0.0** | **14.2 MB APK (all three ABIs, incl. Arabic + English OCR models)** |

Big apps start slowly on cheap phones, which are common in our Arabic-speaking market. Cloud OCR is also slow
or impossible on weak connections.

## 6. Where we win (and what we built for it)

1. **No watermark, no account, no ads, ever.** No ad SDK at all, so a CamScanner-style supply-chain incident is
   impossible by design.
2. **Fully offline — no data can leave the phone.** The APK does not request the `INTERNET` permission (it is
   actively stripped from merged manifests). Cloud backup and device transfer of documents are disabled. Files
   leave only when the user taps Share or Save (Storage Access Framework).
3. **Free features that others charge for:** searchable PDF (OCR), password-protected PDF (AES-256), PDF import and
   merge, ID-card copy, JPG export, small-file preset for WhatsApp and e-mail.
4. **Arabic first.** Arabic is the default UI language with full RTL, English included; per-app language switch.
   **Offline Arabic OCR** (Tesseract) — rare among free scanners — with an honest accuracy note in the UI.
5. **Small APK, minimal permissions.** One permission (camera); no storage permission on any Android version;
   works on phones without Google Play services; our own edge detection and filters in Kotlin (no OpenCV).
6. **Small files.** Black-and-white pages are stored as 1-bit images (far smaller than JPEG); the "Small" preset
   targets messaging limits.
7. **Fills the Microsoft Lens gap**: a free, no-ads, no-watermark scanner for the users Microsoft left behind.

## 7. Honest gaps versus the big apps (v1)

- No cloud sync or multi-device access (by design).
- Edge detection is classical computer vision, not a neural model: it can miss white paper on a white table or
  heavily occluded pages; manual crop always works.
- Arabic OCR quality from Tesseract `tessdata_fast` is clearly below Latin OCR, especially on photos of
  handwriting or low-contrast prints. We ship it, but label it as lower accuracy.
- Imported PDF pages are rasterised (~200 dpi), so their original text layer is not kept (OCR can add one).
- No handwriting/signature stamp, no Word export, no AI summaries.

## Sources (checked 2026-11-14)

- Google Play target API requirement: https://developer.android.com/google/play/requirements/target-sdk
- Microsoft Lens retirement: https://support.microsoft.com/en-gb/topic/retirement-of-microsoft-lens-fc965de7-499d-4d38-aeae-f6e48271652d
- Kaspersky on CamScanner: https://www.kaspersky.com/blog/camscanner-malicious-android-app/28156/
- Adobe Scan subscriptions and OCR limits: https://www.adobe.com/devnet-docs/adobescan/ios/en/managingsubscriptions.html
- Genius Scan plans: https://help.geniusscan.com/subscription-plans-and-account-management
- CamScanner reviews: https://www.capterra.com/p/210426/CamScanner/reviews/
