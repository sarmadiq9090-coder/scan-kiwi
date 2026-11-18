# Changelog / سجل التغييرات

All notable changes are recorded here. The format follows
[Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html).
Each release section is written in Arabic first, then English.

## [Unreleased]

## [2.0.2] - 2026-11-18

### العربية

#### أُصلح
- **تصدير PDF على الهاتف**: كان كل تصدير PDF يفشل برسالة «تعذّر التصدير: bad xref subsection». الملف نفسه كان سليماً، لكن فاحص الملف استعمل نمط بحث (`\G`) يعمل في Java على الحاسوب ولا يعمل في محرك أندرويد (ICU)، فرفض كل ملف على الهاتف فقط. استُبدل بقارئ بسيط بلا أنماط بحث.

#### تغيّر
- **«قراءة النص بعد التصوير» صار مطفأً افتراضياً** (حتى لمن ثبّت 2.0.0 أو 2.0.1)، فلا تزاحم قراءة النص في الخلفية التصويرَ ويصبح الالتقاط أسرع. يُقرأ النص عند التصدير مع OCR، ويمكن تشغيل الخيار من الإعدادات.
- فحص جديد يمنع استعمال `\G` في كود التطبيق، وفحص أن الفاحص يقرأ جدول xref كما يكتبه التطبيق.

### English

#### Fixed
- **PDF export on phones**: every PDF export failed with "Export failed: bad xref subsection". The file was fine; the
  file checker used a `\G` regex anchor, which Java on a PC honours at a search start index and Android's ICU engine
  does not, so it rejected every file on a phone only. It is now a plain hand-written reader.

#### Changed
- **"Read text after capture" is now off by default** (also for 2.0.0 and 2.0.1 installs), so background reading no
  longer competes with capture and capture is faster. Text is read at export with OCR; the option is in Settings.
- A new test keeps `\G` out of app code, and checks that the validator reads the xref table the app writes.

## [2.0.1] - 2026-11-17

### العربية

#### أُصلح
- **التصدير والمشاركة والحفظ**: لا يُكتب ملف PDF ناقص أبداً. في 2.0.0 كانت الصفحة التي تتعذّر قراءتها تُترك خارج الملف بصمت، فقد يخرج PDF بلا صفحات يراه العارض «تالفاً». الآن تُعاد الصفحة من صورتها الأصلية، وإن تعذّر ذلك يتوقف التصدير برسالة «تعذّر التصدير: تعذّرت قراءة الصفحة N».
- كل فشل في التصدير أو المشاركة أو الحفظ يظهر داخل نافذة التصدير نفسها («تعذّر التصدير: …»)؛ كانت الرسالة تظهر تحت النافذة فيبدو أن «لا شيء يحدث».
- قبل المشاركة يُعاد فتح الملف والتحقق منه (PDF كامل، JPEG كامل، نص غير فارغ)، وبعد «حفظ في الجهاز» يُقرأ الملف المحفوظ ويُقارن بالأصل (الطول وSHA-256)؛ لا يُشارك ولا يُحفظ ملف تالف.
- إن لم تُفتح نافذة المشاركة (مثل عدم وجود تطبيق) تظهر رسالة بدل الصمت.

#### تغيّر
- فحص جديد في CI يشغّل نسخة الإصدار المصغّرة بـ R8 نفسها (لا نسخة التطوير): تصدير PDF وJPG وTXT ومشاركتها وحفظها على أندرويد 8 حتى 16 مع فحص PDF بمكتبة Apache PDFBox، والتحقق من التحديثات وتنزيلها من خادم GitHub محلي مزيّف، وفحص أن R8 لم يحذف ما يحتاجه التطبيق بالاسم.

### English

#### Fixed
- **Export, Share and Save**: a PDF is never written with pages missing. In 2.0.0 a page that could not be read was left out
  without a word, so a PDF could come out with no pages, which viewers call damaged. The page is now made again from its
  photo; if that fails too, the export stops with "Export failed: page N could not be read".
- Every export, share or save failure is shown inside the export sheet ("Export failed: …"); the message used to appear
  under the sheet, so it looked as if nothing happened.
- Before sharing, the file is opened again and checked (complete PDF, complete JPEG, non-empty text); after "Save to device"
  the saved file is read back and compared with the export (length and SHA-256). A damaged file is never shared or saved.
- When the share sheet cannot open (for example, no app to receive the file) the app says so instead of staying silent.

#### Changed
- New CI checks run the R8-shrunk release program itself, not the debug build: PDF, JPG and TXT export, share and save on
  Android 8 to 16 with each PDF checked by Apache PDFBox, the update check and download against a local fake GitHub, and a
  check that R8 kept everything the app reaches by name.

## [2.0.0] - 2026-11-15

### العربية

#### أُضيف
- **قراءة نص عربية أدق («دقيق»)**: كشف PP-OCRv6 وتعرّف PP-OCRv5 العربي على المعالج، مع إعادة قراءة Tesseract؛ خطأ الحروف 9.0٪ بدل 29.0٪ على صفحات اختبار محجوزة، والجداول صفوفاً بترتيب القراءة. «سريع» يبقى بديلاً تلقائياً.
- **البحث في كل المستندات** بالأسماء والنص المقروء (عربي بلا حركات أو همزات، أرقام هندية)، مع تظليل الكلمة على صورة الصفحة.
- **«عرض النص وتصحيحه»**: نسخ النص وتصحيحه؛ البحث وطبقة نص PDF يأخذان التصحيح. تصدير **TXT** من نافذة التصدير.
- **المجلدات والمفضلة وترتيب القائمة** في الرئيسية.
- **أدوات PDF**: دمج، تقسيم، استخراج صفحات، إعادة ترتيب بالسحب، تدوير وحفظ، صفحة فارغة، استخراج النص، صفحات إلى JPG، **حجم محدد** للملف، **ترقيم الصفحات** و**علامة مائية** (اختياريتان)، **كلمة مرور AES-256** وإزالتها بكلمة المرور الصحيحة. الملف الأصلي لا يتغير أبداً، والمعالجة في عملية منفصلة.
- **الكاميرا**: كشف حواف أفضل في الإضاءة الخافتة والخلفيات المزدحمة، تنبيه الاهتزاز وعدم الوضوح والصفحة الناقصة والوهج، **وضع الكتاب** (صفحتان تُقسمان عند الكعب، من الكاميرا أو المعرض)، **إزالة الوهج** بدمج لقطتين، تسوية الميل تلقائياً، وتقليل الضجيج.
- **التحقق من التحديثات من GitHub** (أمر المالك): فحص يدوي، أو تلقائي مرة كل 7 أيام على الأكثر (يمكن إيقافه)، والتنزيل عبر Wi-Fi فقط افتراضياً. يُثبَّت التحديث فقط إن طابق SHA256SUMS وشهادة التوقيع نفسها.
- وضع الذاكرة المنخفضة، ودعم أندرويد 7.0 فما فوق.

#### تغيّر
- **الإنترنت للتحديثات فقط**: كل الميزات تعمل دون اتصال. الاتصال الوحيد بخوادم GitHub للتحقق من التحديثات وتنزيلها، ولا يُرسل أي مستند أو صورة أو نص. الأذونات: الكاميرا، الإنترنت، حالة الشبكة، وتثبيت الحزم (للتحديث).
- الالتقاط التلقائي ينتظر ثانيتين ونصفاً بعد فتح الكاميرا أو تغيير الوضع، ليتسع الوقت لاختيار «كتاب» أو «بطاقة هوية».
- عند الخط الكبير (150٪ فأكثر) تصبح خيارات نافذة التصدير قائمة رأسية فلا تُقص التسميات؛ زرّا «مشاركة» و«حفظ في الجهاز» ثابتان ومتساويان.
- أسماء المستندات التلقائية تتبع لغة الواجهة.

#### أُصلح
- الشريط الداكن المطبوع على حافة الورقة (رأس فاتورة مثلاً) يبقى داخل الصفحة بدل أن يُقص مع نصه.
- صورة الكتاب المختارة من المعرض تفتح شاشة التقسيم؛ والصورة الثانية لإزالة الوهج من المعرض تُدمج في الصفحة بدل إضافة صفحة.
- الصفحتان المقسومتان من الكتاب لا تُظهران تنبيه «جزء من الصفحة مقصوص» خطأً.
- رسالة الحفظ تذكر الحجم مقرّباً تقريباً صحيحاً وتظهر مرة واحدة؛ لا «لا توجد نتائج» مضللة بعد نقل مستند إلى مجلد.
- نوافذ إدخال النص (مجلد جديد، إعادة التسمية…) تستقر على الهواتف العريضة.

### English

#### Added
- **More accurate Arabic text recognition ("Accurate")**: PP-OCRv6 detection and PP-OCRv5 Arabic recognition on the CPU,
  with a Tesseract re-read; character error 9.0 % instead of 29.0 % on held-out test pages, tables as rows in reading
  order. "Fast" stays as the automatic fallback.
- **Search across all documents** by name and recognised text (Arabic without diacritics or hamza variants, Arabic-Indic
  digits), with the word highlighted on the page image.
- **"View and correct text"**: copy and correct the text; search and the PDF text layer use the correction. **TXT** export
  in the export sheet.
- **Folders, favourites and sorting** on Home.
- **PDF tools**: merge, split, extract pages, drag to reorder, rotate and save, blank page, extract text, pages to JPG,
  an **exact target size**, optional **page numbers** and **watermark**, **AES-256 password** and its removal with the right
  password. The original file is never changed; the work runs in a separate process.
- **Camera**: better edge detection in dim light and on busy backgrounds, warnings for shake, blur, a cut-off page and
  glare, **book mode** (two pages split at the spine, from the camera or the gallery), **glare removal** from two shots,
  automatic deskew, and noise reduction.
- **Update check from GitHub** (owner's decision): by hand, or automatically at most once every 7 days (can be turned off),
  downloads over Wi-Fi only by default. An update is installed only if it matches SHA256SUMS and the same signing
  certificate.
- Low-memory mode; Android 7.0 and later.

#### Changed
- **Internet for updates only**: every feature works offline. The only connection is to GitHub to check for and download
  updates; no document, image or text is ever sent. Permissions: camera, internet, network state, and install packages
  (for updates).
- Auto capture waits 2.5 s after the camera opens or the mode changes, so there is time to choose Book or ID card.
- At large text (150 % and more) the export sheet's choices become a vertical list, so no label is cut; Share and Save
  stay fixed and equal.
- Automatic document names follow the app language.

#### Fixed
- A dark band printed along the paper's edge (an invoice title bar) stays inside the page instead of being cut away with
  its text.
- A book photo picked from the gallery opens the split screen; a second glare photo from the gallery is fused into the
  page instead of adding a page.
- The two split book pages no longer warn "Part of the page is cut off".
- The save message rounds the size correctly and appears once; no misleading "No results" after moving a document into
  a folder.
- Text-field dialogs (new folder, rename, …) settle on wide phones.

## [1.2.0] - 2026-11-14

### العربية

#### أُضيف
- الاسم الإنجليزي الجديد **Scan Kiwi** (الاسم العربي «ماسح كيوي» كما هو). التطبيق مفتوح المصدر بترخيص Apache-2.0.
- «أبلغنا عن مشكلة أو اقتراح»: من الإعدادات، وصفحة «حول»، ومن رسائل الأخطاء. يفتح بريدك أو GitHub بنص جاهز تراه قبل الإرسال؛ معلومات الجهاز اختيارية ومطفأة افتراضيًا.
- قسم «التحديثات» في الإعدادات: «حدّثه تلقائياً عبر Obtainium» و«ابحث عن تحديث». التطبيق نفسه لا يتصل بالإنترنت.
- خيار «أرشيفي (PDF/A)»: ملفات PDF/A-2b للحفظ الطويل، متحقَّق منها بأداة veraPDF.
- شريط تقدم وزر «إلغاء» حقيقي أثناء تجهيز الملف للمشاركة أو الحفظ.
- عدّاد دائري حول زر التصوير أثناء الالتقاط التلقائي.

#### تغيّر
- **تصميم جديد أكثر احترافية**: زر رئيسي واحد في كل شاشة وفي متناول الإبهام، أزرار ثابتة المواضع، خط Noto Sans Arabic موحّد، ألوان كاملة من هوية الكيوي، سمة داكنة اختيارية وألوان خلفية الهاتف (أندرويد ١٢+)، أيقونات Material Symbols، أرقام بحسب لغة الهاتف.
- زر التصوير في المنتصف دائمًا، وزر «تم» يعرض آخر صفحة وعددها؛ الإعدادات مجمّعة في مجموعات؛ التحديثات في شاشة خاصة.
- المشاركة أسرع وأخف: تقدير الحجم لم يعد يعيد ضغط الصفحات، ويُعاد استخدام آخر ملف إن لم يتغير شيء، والتعرّف على النص يعمل على صورة أصغر (بكسلات أقل بنحو 45٪).
- الالتقاط التلقائي أثبت: يعتمد على الزمن لا عدد الإطارات، يتحمّل فقدان إطار أو اثنين، ولا يصوّر الصفحة نفسها مرتين.
- كشف الحواف أفضل للورق الأبيض على سطح فاتح ملوّن.
- الهواتف ذات الذاكرة القليلة تحفظ الصور بدقة 2400 بكسل بدل 3200 لتجنّب نفاد الذاكرة.
- النص المقروء من كل صفحة يُحفظ ويُعاد استعماله عند المشاركة أو الحفظ مرة أخرى؛ لا تُقرأ الصفحة من جديد إلا إذا تغيّرت (قص أو تدوير أو مرشّح) أو تغيّرت لغة القراءة.

#### أُصلح
- التوقيع يبقى في مكانه على الورقة بعد إعادة القص أو التدوير.
- استيراد ملفات PDF من تطبيقات تقدّم الملف كتدفق فقط (بعض تطبيقات السحابة والبريد).
- تدوير المعاينة في شاشة القص لم يعد يثقل الواجهة.
- زرّا «مشاركة» و«حفظ في الجهاز» ثابتان أسفل نافذة التصدير ولا يختفيان تحت الخيارات (فحص الجودة المستقل).
- تغيير لغة التعرّف على النص يعيد قراءة الصفحات بدل استعمال نص محفوظ بلغة أخرى.
- ملفات PDF التي تصل بلا نوع ولا امتداد تُعرف من بدايتها وتُستورد.
- لصق نص أطول من الحد في «أبلغنا عن مشكلة» يُقصّ بدل أن يُتجاهل.
- إطار مفقود لا يُظهر «اثبت قليلًا…» عندما لا يكون العدّ التنازلي جارياً.
- إشعار ترخيص libyuv (داخل CameraX) أُضيف إلى التراخيص، والخطوط والأيقونات إلى قائمة المكوّنات.
- النص المخفي في ملف PDF القابل للبحث يقع فوق كلماته في الصفحة بكل مستويات الجودة، حتى حين يُعاد استعمال نص قُرئ بجودة أخرى (كان يُزاح حتى 1.4 مرة ويخرج بعضه عن الصفحة).
- زر «إلغاء» يوقف قراءة النص في الصفحة الجارية بدل انتظار انتهائها.

#### الأمان
- لا إذن إنترنت، ولا تثبيت حزم، ولا حالة شبكة؛ وفحص في CI يرفض أي إذن غير الكاميرا.
- قائمة مكوّنات (SBOM) بصيغة CycloneDX وبيان مصدر (in-toto/SLSA) وبصمات SHA-256 لكل إصدار.

### English

#### Added
- New English name **Scan Kiwi** (Arabic name «ماسح كيوي» unchanged). Open source under Apache-2.0.
- "Report a problem or suggestion": from Settings, About and error messages. Opens your mail app or GitHub with
  a ready text you review before sending; device details are optional and off by default.
- Settings "Updates" section: "Auto-update with Obtainium" and "Check for updates". The app itself never goes online.
- "Archival (PDF/A)" export: PDF/A-2b files, validated with veraPDF.
- Progress bar and a real "Cancel" while a file is prepared for sharing or saving.
- Countdown ring around the shutter during auto capture.

#### Changed
- **Professional redesign**: one primary action per screen within thumb reach, buttons in fixed places, one
  Arabic UI font (Noto Sans Arabic), a complete kiwi colour scheme, optional dark theme and wallpaper colours
  (Android 12+), Material Symbols icons, numbers in the phone's digits.
- The shutter is always centred and a "Done" pill shows the last page and the count; Settings are grouped;
  Updates has its own screen.
- Sharing is faster and lighter: the size estimate no longer re-encodes pages, an unchanged export is reused,
  and OCR reads a smaller image (about 45% fewer pixels).
- Steadier auto capture: time based instead of frame based, tolerates dropped frames, never captures the same page twice.
- Better edge detection for white paper on light coloured surfaces.
- Low-memory phones store pages at 2400 px instead of 3200 px to avoid running out of memory.
- Recognised text is cached per page and reused on the next share or save; a page is read again only after it
  changes (crop, rotation, filter) or the OCR language changes.

#### Fixed
- A signature stays on the same spot of the paper after re-cropping or rotating the page.
- Importing PDFs from apps that only provide a stream (some cloud and mail apps).
- Rotating the crop preview no longer runs on the main thread.
- "Share" and "Save to device" stay fixed at the bottom of the export sheet instead of scrolling out of sight
  (independent QC).
- Changing the OCR language reads the pages again instead of reusing text recognised in another language.
- PDFs that arrive without a MIME type or a ".pdf" name are recognised by their header and imported.
- Pasting more than the limit into "Report a problem" cuts the text instead of ignoring the paste.
- A dropped camera frame no longer shows "Hold steady…" when no countdown is running.
- The libyuv licence notice (inside CameraX) is in the in-app licences; the SBOM now lists the Noto Sans Arabic
  fonts and the Material Symbols icons.
- The invisible text layer of a searchable PDF sits on its words at every quality, also when text recognised at
  another quality is reused (it was up to 1.4x off, partly outside the page). See DECISIONS.md D33.
- "Cancel" stops text recognition on the page being read instead of waiting for it to finish.

#### Security
- No INTERNET, REQUEST_INSTALL_PACKAGES or ACCESS_NETWORK_STATE permission; CI fails on any permission except CAMERA.
- CycloneDX SBOM, in-toto/SLSA provenance statement and SHA-256 checksums published with each release.

## [1.1.1] - 2026-11-14

### العربية
- إصلاحات فقط: حماية من فقدان البيانات عند التعديلات المتزامنة، تراجع أدق، كتابة ذرّية للملفات، تصدير PDF صفحة بصفحة لتقليل الذاكرة، ورفض المشاركات الواردة غير الآمنة.

### English
- Fix-only release: no data loss under concurrent edits, correct undo, atomic file writes, page-by-page PDF
  streaming, safer incoming shares. See DECISIONS.md D24.

## [1.1.0] - 2026-11-14

### العربية
- شاشة قص جديدة، توقيع، تراجع، التقاط متتابع، معاينة الحجم، A5 وLegal، اختصار «مسح سريع».

### English
- New crop editor, signature stamp, undo, batch auto capture, size preview, A5/Legal, quick-scan shortcut.

## [1.0.0] - 2026-11-14

### العربية
- الإصدار الأول: مسح بالكاميرا مع كشف الحواف، فلاتر، PDF مع OCR اختياري وكلمة مرور، دون إنترنت.

### English
- First release: camera scanning with edge detection, filters, PDF with optional OCR and password, fully offline.

[Unreleased]: https://github.com/sarmadiq9090-coder/scan-kiwi/compare/v2.0.1...HEAD
[2.0.1]: https://github.com/sarmadiq9090-coder/scan-kiwi/compare/v2.0.0...v2.0.1
[2.0.0]: https://github.com/sarmadiq9090-coder/scan-kiwi/compare/v1.2.0...v2.0.0
[1.2.0]: https://github.com/sarmadiq9090-coder/scan-kiwi/releases/tag/v1.2.0
[1.1.1]: https://github.com/sarmadiq9090-coder/scan-kiwi/releases/tag/v1.1.1
[1.1.0]: https://github.com/sarmadiq9090-coder/scan-kiwi/releases/tag/v1.1.0
[1.0.0]: https://github.com/sarmadiq9090-coder/scan-kiwi/releases/tag/v1.0.0
