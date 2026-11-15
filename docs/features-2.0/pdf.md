# ميزات PDF في الإصدار 2.0.0 — PDF features in 2.0.0

Owner: PDF agent, branch `feat/2.0-pdf`. Roadmap: ROADMAP-2.0 §5 "1.4.0", §5 "1.6.0" item 4 (§7.4), §5 "2.0.0"
item 2 (PDF-14). All tests are JVM/Robolectric on this PC; nothing here was measured on a phone.

## ملخص بالعربية

- وحدة PDF مستقلة (`com.fastkiwi.scan.pdf`) بواجهة `PdfEngine` واحدة لكل العمليات: الدمج والتقسيم والاستخراج
  وإعادة الترتيب والتدوير وإدراج صفحة فارغة. مستندات ماسح كيوي تُبنى من صورها الأصلية ويكتبها كاتبنا الخاص؛
  ملفات PDF القادمة من تطبيقات أخرى تُنسخ كما هي دون إعادة ضغط عبر PdfBox-Android داخل عملية منفصلة (`:pdf`)
  بمهلة ٦٠ ثانية.
- كلفة PdfBox-Android على حجم التطبيق ٠٫٦٣ م.ب (الحد المسموح ٢٫٠ م.ب)، فاعتُمدت.
- ضغط إلى حجم محدد: ٥٠ تجربة، وصل ٤٩ إلى الحجم، وواحدة لم تصل وأخبرت بالحجم الحقيقي، ولا نجاح كاذب واحد.
- ترقيم الصفحات بأرقام عربية أو غربية، وعلامة مائية نصية؛ ملفات PDF/A-2b معها تجتاز veraPDF.
- سلامة الملفات: ملف مؤقت ثم تحقق ثم إعادة تسمية ذرية؛ ١٠ قطعات عشوائية للتصدير لم تغيّر الأصل في أي مرة.
- اختبار ٢٠٠ ملف PDF تالف: لا انهيار، وأبطأ ملف أجاب في ٠٫٤ ثانية.
- تراجع وإعادة في المحرر، وسحب الصفحات وإفلاتها لتغيير الترتيب مع بقاء أوامر القائمة وأوامر TalkBack.
- فتح ملف محمي بكلمة مرور عند الاستيراد، وإزالة الحماية فقط بكلمة المرور الصحيحة.

## Feature table

| Arabic | English | ID | Status | Acceptance criterion | Measured result | Test |
|---|---|---|---|---|---|---|
| وحدة PDF وواجهة المحرّك | PDF module + `PdfEngine` interface | PRD §13, 1.4 #1 | done | one engine interface; our writer for our documents | `PdfEngine<D>` with `KiwiPdfEngine` (our documents) and `ExternalPdfEngine` (PdfBox, `:pdf` process); every operation is `assemble` + a `PagePlans` plan | `PagePlansTest`, `PdfOpsAcceptanceTest` |
| دمج ملفات PDF | Merge | PDF-01 | done | 3 PDFs (ours + 2 external, 1–50 pages): count = sum, order kept, untransformed content byte-identical (PDFBox check) | 3 rounds, all pages: count, order, raw content-stream and image bytes, rotations identical; inputs' sha256 unchanged | `PdfOpsAcceptanceTest.mergeOfOursAndTwoExternalKeepsCountOrderAndBytes`; documents: `AppViewModel.merge` (existing) |
| تقسيم | Split (ranges / every N / selected) | PDF-02 | done | exactly the selected pages, in order | ranges «١-٣، ٥، ٩-» and every 4 pages correct; editor split makes new documents, original kept | `PdfOpsAcceptanceTest.splitAndExtractGiveExactlyTheSelectedPages`, `PdfEditorTest.blankRotateDeleteExtractAndSplit` |
| استخراج صفحات | Extract selected pages | PDF-03 | done | 30 random selections exact (PDFBox) | 30/30 exact; no unselected page object leaks in through links (annotations re-pointed) | same tests |
| إعادة الترتيب بالسحب | Reorder, drag and drop | PDF-04 | done | drag-reorder state tests; menu + TalkBack actions kept | `ReorderState` live order/drop/cancel; a real long-press drag in the Arabic (RTL) editor moves page 1 to position 3; menu (earlier/later/first/last) and the same four TalkBack custom actions kept; external files: typed order | `EditHistoryTest.drag*`, `PdfEditorTest.dragAPageOntoAnotherReordersIt` |
| تدوير وحفظ | Rotate and save | PDF-05 | done | rotation saved | documents: one Undo step for several pages; external: `/Rotate` changed, content bytes identical | `PdfOpsAcceptanceTest.rotateAndBlankPage`, `PdfEditorTest` |
| ضغط ذكي وحجم محدد | Smart compression, custom target size | PDF-06, PRD §6.2 | done | 10 synthetic docs × 200 KB–5 MB: ≤ target or "not reached" with the true size; zero false successes | 50 runs: 49 reached, 1 not reached (true size reported), 0 false successes; 39 s on the JVM | `TargetSizeTest.tenDocumentsFiveTargetsNoFalseSuccess` |
| صور إلى PDF | Images to PDF | PDF-07 | done (existing) | — | unchanged path (import images, export PDF) | existing `AppUiTest`, `Qc120Test` |
| صفحات PDF إلى JPG | PDF pages to JPG | PDF-08 | done | — | documents: JPG export (existing); external: PdfRenderer at 150/200/300 dpi, each image via temp file + rename | not JVM-testable (PdfRenderer is PDFium; Robolectric has no renderer) → phone check |
| ترقيم الصفحات | Page numbers (Arabic-Indic/Western digits, position, start) | PDF-09 | done | PDF/A-2b with numbers passes veraPDF | 6 positions land where asked; «١٢», «٣ / ٧»; veraPDF: compliant | `DecoratedExportTest` |
| علامة مائية | Text watermark (opacity, diagonal) | PDF-10 | done | same | translucent across the centre; veraPDF compliant with numbers + watermark | `DecoratedExportTest` |
| إدراج صفحة فارغة | Insert blank page | PDF-11 | done | — | before/after any page or at the end (documents), before page N (external); sized like the neighbour | `PdfEditorTest`, `PdfOpsAcceptanceTest.rotateAndBlankPage` |
| استخراج النص | Text extraction | PDF-12 | done | our docs: OCR words in logical order; external: PdfBox stripper + bidi fix | ours: lines top→bottom, RTL lines right→left (`DocumentText`, API for TXT export); external: «فاتورة رقم 2026» extracted in logical order (PDFBox 2.0 gives visual word order; fixed), presentation forms folded | `PdfOpsAcceptanceTest.textExtractionExternalAndOurs`, `arabicPresentationFormsAreFolded` |
| إزالة الحماية | Remove protection with the password | PDF-14 | done | only with the correct password; never crack | wrong/missing password → error, no output; right password → unprotected copy, original untouched | `PdfOpsAcceptanceTest.unlockNeedsTheRightPassword` |
| كلمة مرور عند الاستيراد | Password prompt for protected PDFs on import | PDF-14 | done | prompt instead of failing | `ImportPasswordPrompt`: decrypted copy made in `:pdf`, imported, deleted; wrong password asks again | engine path tested; dialog: phone check |
| سلامة الملف | Integrity (temp → validate → atomic rename, free space × 1.2, sweep) | PRD §6.3, 1.4 #7 | done | kill at 10 random points: original sha256 unchanged 100%, no partial file shared | 10/10 (5 cancels, 5 injected I/O errors): document files and previous export unchanged, no `.part`, nothing returned; invalid file never committed; low space refused before writing; leftovers swept at start | `ExportIntegrityTest` |
| PdfBox-Android معزول | PdfBox-Android isolated in `:pdf` | 1.4 #3 | done | R8-shrunk cost ≤ 2.0 MB | **0.63 MB** (dex +0.21, assets +0.41) on the release APK; see below | size gate |
| اختبار الملفات التالفة | Fuzz gate | 1.4 acceptance | done | 200 malformed PDFs: no main-process crash; error within the timeout | 200/200 answered; 126 errors, 74 read after PDFBox's repair (output re-validated); slowest 414 ms (limit 60 s) | `PdfFuzzTest` |
| المهلة والانهيار | Timeout / crash of the worker | 1.4 #3 | done | — | hung worker → TIMEOUT after the limit, worker killed; unbindable service → CRASHED; real service protocol end to end | `IsolatedPdfEngineTest` |
| تراجع وإعادة | Undo and Redo | PRD §7.3, 1.4 #8 | done | Redo tests | redo after undo both ways; a new edit clears Redo; button state follows the history; 20 steps each way | `EditHistoryTest`, `PdfEditorTest.redo*` |
| لوحة التصدير كاملة | Export panel complete | §7.4 (1.6 #4) | done | format, page size, quality, OCR, password, estimated size, final preview | all in the existing sheet design + «حجم محدد», numbers, watermark, preview of the first 3 pages (paper size, numbers, watermark drawn by the export code) | `PdfEditorTest.exportSheetShowsTheNewOptionsAndHomeHasPdfTools` |
| أدوات PDF | PDF tools screen (external files) | split doc | done | — | Home entry; merge, split, extract, reorder, rotate, blank, unlock, text, JPG; results shared or saved via the system picker | `PdfEditorTest.pdfToolsScreenOpens` (render); operations via `ExternalPdfEngine` tests |

## PdfBox-Android decision (measured)

Release APK (unsigned, same build settings), `size_gate.py --version 1.4.0`:

| Build | APK | dex | assets/com (PdfBox) |
|---|---|---|---|
| before (branch base) | 14.55 MB | 1.74 MB | — |
| PdfBox-Android used from `PdfService` | 15.19 MB | 1.95 MB | 0.41 MB |

PdfBox cost **0.63 MB** ≤ 2.0 MB → adopted. BouncyCastle excluded (certificate-encrypted PDFs report
"unsupported"); CJK CMaps and `Scripts.txt` not packaged (`ignoreAssetsPatterns`). The LiberationSans
last-resort font stays (PdfBox's font mapper always loads it; OFL-1.1). R8 `-dontwarn` for BouncyCastle and
the optional JPEG 2000 decoder. Verification metadata: 2 new entries (aar, pom), sha256 checked against
Maven Central's published checksum (`verify_new_entries.py`: all confirmed).

Isolation: `PdfService` (`android:process=":pdf"`, not exported) parses every external file; requests on a
64 MB-stack thread; after a fatal error the worker process ends itself. `IsolatedPdfEngine` binds, waits at
most 60 s, kills the worker on timeout or cancel, and maps a crash to an error. Only app-private file paths
cross the boundary.

## Arabic text in page numbers and watermarks: raster, not an embedded font

Numbers and watermark are drawn into the page pixels before encoding, with Android's text stack and the
bundled Noto Sans Arabic Bold (OFL-1.1). Reasons: Android shapes Arabic correctly (HarfBuzz: joining forms,
lam-alef, bidi with digits and Latin), which a hand-written PDF font subsetter would have to reimplement
(GSUB) and could get wrong; no font goes into the file, so PDF/A-2b has no font rules to break (veraPDF
passes) and AES encryption works unchanged; zero APK cost. Trade-off: the numbers and watermark are not
selectable text (the OCR text layer is separate and unaffected). On black-and-white pages the watermark is
drawn as an outline so it survives the 1-bit conversion.

## Integrity design (PRD §6.3)

`SafeOutput`: free-space check (needed × 1.2, "لا توجد مساحة كافية" with the amount) → write to `<name>.part`
in app storage → `PdfValidator` (strict: header, startxref, every xref offset at "N G obj", trailer
/Root, catalog /Pages, /Count = expected pages) → atomic rename. The previous export is removed only after
the new one is committed; a cancel during the last page still discards the file. Originals are never a
target: external files are copied into app storage and only read; results are new files; "Save to device"
deletes its half-written target on failure. `.part` files and PDF-tools temp folders are swept at the next
start (only those older than the start time).

## Touchpoints edited (marked `// 2.0-pdf:`)

- `ui/AppViewModel.kt`: `Screen.PdfTools`; Undo/Redo history (`EditHistory`) replacing the undo stack;
  start-up sweep; `job()` maps `NoSpaceException`/`PdfOpException` to messages; import of protected PDFs
  (`passwordRequest`, `importProtected`); PDF operations for documents (blank page, rotate/delete several,
  extract, split, `documentText`); export uses `exportPdfResult` (target-size message, fitting phase);
  "Save to device" cleans up on failure.
- `ui/HomeScreen.kt`: one "PDF tools" icon button in the top bar.
- `MainActivity.kt`: route for `Screen.PdfTools` and the import password prompt (two lines).
- `export/Exporter.kt`: the OCR call is unchanged in shape (moved into `PdfRun.write`); constructor gains
  `openStream` before `newOcr` (the trailing-lambda OCR seam still works).
- API for the Documents agent (TXT export): `com.fastkiwi.scan.pdf.DocumentText.pages(store, doc)` /
  `.plain(store, doc)`.

## Not done / limits (with reasons)

- PDF→JPG and the password dialogs are not exercised in JVM tests (PdfRenderer has no Robolectric
  implementation; dialogs need a real picker) → phone checklist.
- External text extraction assumes the common visual-order storage; PDFs that store Arabic in logical order
  come out word-reversed (PDFBox 2.0 limitation; no reliable detection).
- Process isolation itself (a crash really staying in `:pdf`) can only be shown on a device; the JVM tests
  run the same worker code in-process.
- Licences: PdfBox-Android's bundled Adobe AFM metrics (APAFML) and Unicode BidiMirroring.txt (Unicode data
  licence) are permissive but not on the ROADMAP §0 list; recorded in THIRD_PARTY.md for a council decision.
  Removing them would break text extraction for the 14 standard fonts.

## Phone checklist (owner)

1. Home → «أدوات PDF»: merge a PDF from WhatsApp with an exported scan; open the result.
2. Split it «١-٢، ٣-»; extract page 2; rotate page 1; insert a blank page.
3. Export a scan with «حجم محدد» 500 ك.ب and send it; check the message shows the real size.
4. Export with page numbers in Arabic digits and a watermark «نسخة»; open in a PDF reader.
5. Import a password-protected PDF: the prompt appears; wrong password asks again; right one imports.
6. Swipe the app away during a long export; reopen: the document is intact.
7. Long-press a page and drag it; Undo, then Redo.
