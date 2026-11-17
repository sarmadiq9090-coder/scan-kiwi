# ميزات 2.0 — المستندات (البحث، النص، المفضلة، المجلدات، الصفحة الرئيسية)

الفرع `feat/2.0-docs`. كل الأرقام مقيسة على الحاسوب (JVM + Robolectric، SQLite 3.44 الأصلي في وضع Robolectric الأصيل)، ببيانات مصنوعة بالكامل في كود الاختبار (لا مستند حقيقي).

| الميزة | English | المعرّف | الحالة | معيار القبول | النتيجة المقيسة | الاختبار |
|---|---|---|---|---|---|---|
| البحث في كل المستندات بالعربية | Search across all documents | OCR-07، 1.6.0 بند 1 | منجز | استرجاع ≥ ٩٥٪ على ٢٠٠ مستند مصنوع لاستعلامات بلا حركات أو بصور ألف/ياء مختلفة | **الاسترجاع ١٠٠٪**: ١٠٠٠ استعلام (٥٥٢ منها بصورة مختلفة عن النص)، ٣٣٤٩٠ صفحة ذات صلة، وُجدت كلها؛ ٢٠٠ مستند، ٣٩٩ صفحة؛ الحكم بمطبّع مرجعي مستقل في الاختبار | `search.SearchIndexTest.recallOn200SyntheticDocumentsWithTypedVariants` |
| سرعة البحث | Search speed | 1.6.0 قبول | منجز | استعلام على ١٠٠٠ صفحة ≤ ٣٠٠ مللي ثانية على JVM | **أسوأ استعلام ١٠٫٨ مللي ثانية، الوسيط ٢٫٢** (١٤ استعلاماً منها حرف واحد «ا» يطابق ١٠٠٠ صفحة). بناء الفهرس لأول مرة ٩٫٦ ثانية لـ ١٠٠٠ صفحة (في الخلفية، مرة واحدة)، والمزامنة دون تغيير ٠٫٦٤ ثانية | `search.SearchIndexTest.queryOver1000PagesIsFast` |
| مطبّع العربية | Arabic normaliser | 1.6.0 خطة الاختبار | منجز | جدول صور الحروف | ٢٩ صورة في الجدول: الحركات، التطويل، أ إ آ ٱ→ا، ى ی→ي، ة→ه، ک→ك، الأرقام الهندية والفارسية = الغربية، فاصل الآلاف، علامات الاتجاه | `search.ArabicNormalizerTest` |
| مزامنة الفهرس وإعادة بنائه | Index sync / rebuild | 1.6.0 المخاطر | منجز | يُعاد بناؤه من ذاكرة OCR إذا فُقد أو تغيّر إصداره | يتبع حفظ OCR (أي مسار)، التصحيح، حذف صفحة، صفحة جديدة المراجعة، حذف مستند؛ ملف محذوف أو تالف أو بإصدار قديم يُعاد بناؤه | `search.SearchIndexTest.indexStaysInSyncAndRebuildsFromCache` |
| تظليل الكلمة على صورة الصفحة | Highlight on the page image | OCR-07 | منجز | النتيجة تُظهر الصفحة وتظلل الكلمة | الكلمتان المطابقتان مظللتان في موضعهما (من صناديق ذاكرة OCR) | `DocsArabicUiTest.homeSearchAndTextInArabic` |
| تصدير TXT ونسخ النص | TXT export, copy text | OCR-08 | منجز | TXT صفحات المعيار = مخرجات المحرك بايتاً ببايت | **٥٢ صفحة بكل الفئات (عربي، مشكول، أرقام هندية وغربية، مبالغ، تواريخ، مختلط، جداول): متطابقة بايتاً ببايت**، UTF-8 بلا BOM، بلا علامات اتجاه | `export.TextExporterTest.txtEqualsEngineOutputByteForByte` |
| عرض النص وتصحيحه | View, copy, correct text | OCR-10 | منجز | الكلمة المصححة يجدها البحث وتظهر في طبقة نص PDF (PDFBox) | «1200»→«1500» و«مارس»→«أبريل»: البحث بـ «ابريل» يجد الصفحة، PDFBox يقرأ 1500 لا 1200، الصورة لم تتغير بايتاً، التراجع يعيد النص | `DocsArabicUiTest.correctedWordIsSearchableAndInPdfTextLayer`، `data.TextOverlayTest` |
| المفضلة | Favorites | A3، 1.6.0 بند 3 | منجز | نجمة على المستند، فلتر في الرئيسية | النجمة لا تغيّر تاريخ التعديل؛ «المفضلة» تجمع المميّز من كل المجلدات | `DocsArabicUiTest.folderMoveCopyDeleteAndFavorite`، `data.FolderStoreTest.sortByDateNameAndSize` |
| المجلدات | Folders | A3، 2.0.0 بند 1 | منجز | ترحيل 1.x يحفظ كل مستند؛ حذف المجلد لا يحذف مستندات | ٢٥ مستنداً بصيغة 1.x: كلها في المستوى الأعلى بلا تغيير؛ حذف مجلد فيه ٣ مستندات: ٥/٥ مستندات باقية، الصفحات وتاريخ التعديل كما هي؛ نقل ونسخ (بالنص والتصحيح) | `data.FolderStoreTest`، `DocsArabicUiTest.folderMoveCopyDeleteAndFavorite` |
| الترتيب | Sort by date / name / size | 2.0.0 بند 1 | منجز | — | الأحدث، الاسم (Collator للغة)، الحجم على القرص؛ يُحفظ الاختيار | `data.FolderStoreTest.sortByDateNameAndSize` |
| الصفحة الرئيسية 2.0 | Home (PRD §7.1) | 2.0.0 بند 1 | منجز | مسح، استيراد، الأخيرة، بحث، مجلدات، مفضلة؛ RTL، TalkBack، ٤٨ dp، خط ٢٠٠٪ | كل الأهداف ≥ ٤٨ dp في الرئيسية والمجلد والبحث والنص (عربي وإنجليزي)؛ «مسح» داخل النافذة بخط ٢٠٠٪ | `DocsArabicUiTest`، `DocsEnglishUiTest`، و`DesignTest` القائم |

## قرارات

- **التصحيح طبقة فوق كلمات OCR** (`p_<id>_text.json`) يطبّقها `DocumentStore.loadOcr` (السطر الوحيد الذي يقرأ الكلمات المخزنة)، فطبقة نص PDF والبحث وTXT والنسخ كلها تستخدم النص المصحح دون تغيير المُصدِّر، والصورة لا تتغير. الكلمة المصححة تحتفظ بصندوقها؛ الكلمة المضافة تشارك صندوق الكلمة قبلها (أو بعدها في البداية)؛ المحذوفة تسقط.
- **يُلغى التصحيح إذا تغيّرت الصفحة نفسها** (مراجعة جديدة: قص، تدوير، مرشّح، أو التراجع عنها)، لأن OCR يُعاد على صورة مختلفة وكلماته غير الكلمات التي صُحّحت. كذلك لا يُطبَّق إذا تغيّرت نتيجة OCR نفسها (مثلاً تصدير بلغة أخرى أعاد القراءة)؛ يعود تلقائياً إذا عادت النتيجة نفسها. يُذكر ذلك للمستخدم في شاشة التصحيح.
- **التراجع عن التصحيح**: زر «تراجع عن التصحيح» في شاشة النص (آخر ٢٠ تصحيحاً لكل صفحة، في الذاكرة)، لأن سجل التراجع في المحرر يحفظ حالة المستند لا ملفات النص.
- **الفهرس ذاكرة مؤقتة فقط** (`files/search/search.db`، FTS4 بمُجزّئ `simple` على نص مطبَّع مسبقاً). الإصدار = إصدار الجداول × ١٠٠٠ + إصدار المطبّع. المزامنة بختم لكل صفحة (المراجعة، وقت وحجم ملف OCR وملف التصحيح) عند كل `refresh` وكل حفظ مستند، فتلتقط OCR المحفوظ من أي مسار (تصدير أو OCR في الخلفية) دون تعديل `saveOcr`.
- **البحث**: كل كلمة في الاستعلام بادئة كلمة، والكلمات مجتمعة (AND). تُفهرس الكلمة ومعها جذعها بلا «ال/وال/بال/كال/فال/لل»، فـ «كهرباء» تجد «الكهرباء». ؤ وئ وء تبقى كما هي (ليست في القواعد المعتمدة).
- **المجلدات بمستوى واحد** (لا مجلدات متداخلة في 2.0). مستند يشير إلى مجلد غير موجود يظهر في المستوى الأعلى. حذف المجلد ينقل مستنداته أولاً ثم يحذف المجلد. المستند الجديد (مسح، استيراد، دمج) يُنشأ داخل المجلد المفتوح.
- **TXT**: نص كل صفحة هو كلمات المحرك بترتيبها، كلمة جديدة في سطر جديد إذا نزلت تحت الكلمة السابقة. يفصل الصفحات سطر فارغ وعنوان «— صفحة ٢ —» (للمستند متعدد الصفحات فقط). الصفحات بلا نص مخزّن تُقرأ عند التصدير بالمحرك نفسه وتُخزَّن.

## حدود معروفة

- إذا أعاد وكيل OCR تعريف صيغة ذاكرة OCR، يبقى المفتاح `frame` مطلوباً لتظليل الكلمات (`DocumentStore.ocrFrame`)؛ الذاكرات القديمة بلا `frame` تُبحث ولا تُظلَّل.
- قراءة الصفحات الناقصة لـ TXT و«اقرأ النص الآن» تمر عبر `PageOcr`/`OcrEngine` الحاليين في كتلة `MissingTextReader` في `AppViewModel` (نقطة واحدة يكيّفها الدمج مع واجهة OCR الجديدة).
- بناء الفهرس لأول مرة بطيء نسبياً (≈ ١٠ مللي ثانية لكل صفحة على الحاسوب)؛ يجري في الخلفية بعد التحديث ولا يحجب الواجهة.

---

# 2.0 features — Documents (search, text, favorites, folders, Home)

Branch `feat/2.0-docs`. All numbers measured on the PC (JVM + Robolectric; Robolectric's native-mode SQLite 3.44 with FTS4 compiled in, so the real FTS4 tokenizer and MATCH are tested). Synthetic data generated in test code only.

| Feature | ID | Status | Criterion | Measured | Test |
|---|---|---|---|---|---|
| Arabic-aware search across documents | OCR-07 | done | recall ≥ 95 % on 200 synthetic docs, queries without harakat / with alef-ya variants | **recall 100 %** (1,000 queries, 552 typed with a variant; 33,490 relevant pages, all found), judged with an independent reference fold | `SearchIndexTest.recallOn200SyntheticDocumentsWithTypedVariants` |
| Search speed | 1.6.0 | done | ≤ 300 ms over 1,000 pages (JVM) | **worst 10.8 ms, median 2.2 ms**; first index build 9.6 s / 1,000 pages (background, once); unchanged re-sync 0.64 s | `SearchIndexTest.queryOver1000PagesIsFast` |
| Normaliser | 1.6.0 | done | variant table | 29 variants | `ArabicNormalizerTest` |
| Index sync / rebuild | 1.6.0 risk | done | rebuilt from cached OCR if missing / schema change | OCR save (any path), correction, page delete, new revision, doc delete; missing, damaged and old-version files rebuilt | `SearchIndexTest.indexStaysInSyncAndRebuildsFromCache` |
| Highlight on page image | OCR-07 | done | — | both matching words boxed at their OCR boxes | `DocsArabicUiTest.homeSearchAndTextInArabic` |
| TXT export + copy text | OCR-08 | done | byte-for-byte = engine output | **52 benchmark-like pages, all categories: identical**; UTF-8, no BOM, no direction marks | `TextExporterTest.txtEqualsEngineOutputByteForByte` |
| View / copy / correct text | OCR-10 | done | edited word found by search and in PDF text layer (PDFBox) | yes; image bytes unchanged; Undo restores | `DocsArabicUiTest.correctedWordIsSearchableAndInPdfTextLayer`, `TextOverlayTest` |
| Favorites | A3 | done | star + Home filter | star keeps modified time; filter spans folders | `DocsArabicUiTest.folderMoveCopyDeleteAndFavorite` |
| Folders | A3 / 2.0 | done | 1.x migration keeps every doc; folder delete never deletes docs | 25/25 1.x docs at top level unchanged; delete of a 3-doc folder: 5/5 docs kept, pages and modified time unchanged; move / copy (with text) | `FolderStoreTest`, `DocsArabicUiTest.folderMoveCopyDeleteAndFavorite` |
| Sort | 2.0 | done | date / name / size | remembered; locale collator for names | `FolderStoreTest.sortByDateNameAndSize` |
| Home 2.0 | PRD §7.1 | done | New scan, Import, Recent, Search, Folders, Favorites; RTL, TalkBack, 48 dp, 200 % | all targets ≥ 48 dp on Home, folder, Search, Text (ar + en); primary visible at 200 % | `DocsArabicUiTest`, `DocsEnglishUiTest` |

Decisions (corrections overlay, dropped on a new page revision, Undo in the Text screen, index as a cache with stamps, one-level folders) are described in the Arabic section above.
