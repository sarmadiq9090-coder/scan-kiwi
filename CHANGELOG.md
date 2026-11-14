# Changelog / سجل التغييرات

All notable changes are recorded here. The format follows
[Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html).
Each release section is written in Arabic first, then English.

## [Unreleased]

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
- المشاركة أسرع وأخف: تقدير الحجم لم يعد يعيد ضغط الصفحات، ويُعاد استخدام آخر ملف إن لم يتغير شيء، والتعرّف على النص يعمل على صورة أصغر (بكسلات أقل بنحو 45٪).
- الالتقاط التلقائي أثبت: يعتمد على الزمن لا عدد الإطارات، يتحمّل فقدان إطار أو اثنين، ولا يصوّر الصفحة نفسها مرتين.
- كشف الحواف أفضل للورق الأبيض على سطح فاتح ملوّن.
- الهواتف ذات الذاكرة القليلة تحفظ الصور بدقة 2400 بكسل بدل 3200 لتجنّب نفاد الذاكرة.

#### أُصلح
- التوقيع يبقى في مكانه على الورقة بعد إعادة القص أو التدوير.
- استيراد ملفات PDF من تطبيقات تقدّم الملف كتدفق فقط (بعض تطبيقات السحابة والبريد).
- تدوير المعاينة في شاشة القص لم يعد يثقل الواجهة.

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
- Sharing is faster and lighter: the size estimate no longer re-encodes pages, an unchanged export is reused,
  and OCR reads a smaller image (about 45% fewer pixels).
- Steadier auto capture: time based instead of frame based, tolerates dropped frames, never captures the same page twice.
- Better edge detection for white paper on light coloured surfaces.
- Low-memory phones store pages at 2400 px instead of 3200 px to avoid running out of memory.

#### Fixed
- A signature stays on the same spot of the paper after re-cropping or rotating the page.
- Importing PDFs from apps that only provide a stream (some cloud and mail apps).
- Rotating the crop preview no longer runs on the main thread.

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

[Unreleased]: https://github.com/sarmadiq9090-coder/scan-kiwi/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/sarmadiq9090-coder/scan-kiwi/releases/tag/v1.2.0
[1.1.1]: https://github.com/sarmadiq9090-coder/scan-kiwi/releases/tag/v1.1.1
[1.1.0]: https://github.com/sarmadiq9090-coder/scan-kiwi/releases/tag/v1.1.0
[1.0.0]: https://github.com/sarmadiq9090-coder/scan-kiwi/releases/tag/v1.0.0
