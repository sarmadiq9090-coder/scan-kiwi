<!-- SPDX-FileCopyrightText: 2026 FastKiwi -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# Scan Kiwi 2.0.0 — QC (gate 6.5), 2026-11-15

Against `C:\BahthTest\scan-kiwi-design\DESIGN-SPEC.md`, `C:\BahthTest\scan-kiwi-design\PRD-2.0.md` and
`docs/FEATURES-2.0.md`, on `release/2.0.0` after the black-box re-test fixes (commit 0ca517f and the QC commit after it).

**Grades.** Q1 = a spec violation that blocks the release. Q2 = a deviation to fix before the release. Q3 = cosmetic,
recorded for 2.1. Every Q1 and Q2 below is fixed and re-checked on a new screenshot.

**Result: Q1 = 1, Q2 = 3 (all fixed); Q3 = 4 (recorded for 2.1).**

## Method

Sampled screenshots, not a sweep (manager's cost rule): `gates/QcShotsTest` drives the app through the black-box
driver (the same app code and seams as the tester) and writes `app/build/qc/<name>.png` plus the TalkBack tree as
`.txt`. It runs only with `KIWI_QC=1`. Shots:

| Set | Screens |
|---|---|
| Arabic, light, 100 % (each main screen once) | Home empty, Home with documents, document (page grid), page editor, crop, signature, text screen, export sheet, search, camera, book split, settings, updates, feedback, PDF tools |
| English, dark | Home, document, export sheet, updates |
| Arabic, light, 200 % text | Home, document, export sheet (top and scrolled), updates |

## Findings

| # | Grade | Screen | Finding | Spec | Fix | Re-check |
|---|---|---|---|---|---|---|
| 1 | **Q1** | Signature (draw step) | «حفظ», the primary, sat directly under the pad in the top half of the screen. | §3 global rule: one primary per screen, always in the bottom thumb zone; §3.10 footer | Footer (مسح · إلغاء · حفظ) pushed to the bottom of the screen (`SignaturePad` in a `ColumnScope`, weighted spacer). | `05-signature-ar`: footer at the bottom |
| 2 | **Q2** | Export sheet, English, 100 % | Quality label cut: "Exact si…" (four segments). | §2.2 never clip text; §3.6 | Segment labels may take two lines, centred; all segments of a row share one height (`ChoiceSegmentedRow`). | `22-export-en-dark`: "Exact size" whole, segments even |
| 3 | **Q2** | Export sheet, 200 % (tester task 7) | Format labels cut («نص T…», «صور J…»); page-size field looked clipped behind the fixed footer; Save taller than Share. | §2.2; §3.6 | From 150 % text every choice row is a vertical radio list; Share and Save have one height; a divider above the fixed footer shows that options continue under it. Test: `BlackBoxRetestTest.theFormatsAtDoubleTextSizeAreAVerticalList`. | `32-export-200-ar`, `32b-…-scrolled` |
| 4 | **Q2** | Export, add-pages and PDF-result sheets | Material's drag handle carries a tooltip anchor and a tap/dismiss node with no name: TalkBack reaches two unlabelled controls (found by the monkey's naming check). | WCAG 2.2 4.1.2 (STANDARDS) | Handle drawn only (`KiwiSheetHandle`, no semantics; `dragHandle = null`); the scrim «إغلاق الورقة» and Back close the sheet. | `TortureMonkeyTest.everyActionableControlHasAName` passes |
| 5 | Q3 | Updates | Every row has the same leading icon (update/history), so the icons say nothing. | §2.4 (icons per action) | — (2.1) | — |
| 6 | Q3 | Document top bar and Home rows, 200 % | Long default names are cut with an ellipsis («مسح 2026-11-…»). Allowed by §2.2 (one-line titles, ellipsis); tapping the title shows the whole name and TalkBack reads it whole. | §2.2 | — (2.1: two-line title at large text) | — |
| 7 | Q3 | Arabic default names | The ISO date in «مسح 2026-11-15 14.36» is shown in RTL order (15-11-2026) because the name is not bidi-isolated where it is shown. | §2.2 bidi | — (2.1) | — |
| 8 | Q3 | Camera, glare second shot | Tester note (BLACKBOX re-test, task 4): «تخطَّ» («Skip») slightly overlaps the hint text. Not reproduced in the sampled shots. | §3.2 | — (2.1) | — |

Checked and in line with the spec (no finding): one primary per screen in the thumb zone (Home FAB, document
Export, crop Done, export Share, feedback Send, PDF tools Pick); paired buttons secondary-first with the primary at
the end in RTL and LTR; stacking primary-on-top at 200 % in the document bar; the export footer stays side by side
at 200 % (a recorded black-box decision, §0b of the hand-off, so the options keep most of the sheet; labels wrap
instead of stacking); grouped settings rows; Arabic comma «، » in Arabic metadata and ` · ` in English; locale
digits (٢:٣٦ م, ٦٧ ك.ب, «الصفحة ١ من ٢») with Latin technical identifiers (PDF, JPG, TXT, A4, 2.0.0); RTL mirroring
of back arrows and switches; camera always dark with the accent `#B6F293`; dark scheme tones; no clipped Arabic
descenders in the sampled shots; the page image now keeps the invoice's dark title bar (`03-page-ar`, `06-text-ar`).

## PRD and feature list

| Item | Result |
|---|---|
| Android 7+ | `minSdk 24` (Android 7.0); 9/9 flows on API 24/26/28 (`OldAndroidFlowTest`) |
| Size 15–30 MB | release APK measured at the build (release notes, final report) |
| Internet "Not Required" | Every feature works offline. The only network use is the update check and download from GitHub (owner's order D30): HTTPS to four GitHub hosts only, automatic check at most every 7 days (switch in Updates), Wi-Fi-only download by default, the home and camera privacy line says so. Accepted deviation from the PRD table, stated in the release notes. |
| Permissions | CAMERA, INTERNET, ACCESS_NETWORK_STATE, REQUEST_INSTALL_PACKAGES only (manifest; CI checks the list) |
| Arabic first, English, RTL/LTR | both languages and directions in the sampled shots |
| No accounts, ads, subscriptions or forced watermarks | none (the watermark is an export option, off by default) |
| `docs/FEATURES-2.0.md` | every feature marked done is reachable in the app; the black-box re-test findings and the header-band fix are added there |

## Re-run of the torture classes that cover the changed code

App code changed after the torture gate (camera gallery path, export sheet choices and footer, dialogs' button rows,
snackbars, Home empty text, the header-band step). Run once, only the classes that cover it: `TortureChaosTest`
(process death, disk full, blocked writes, permission revoked, memory pressure: chaos/IO) and `TortureMonkeyTest`
(random taps over all screens, including the export sheet and dialogs). Result: see the hand-off §0c.
