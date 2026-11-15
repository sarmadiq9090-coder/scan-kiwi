<!-- SPDX-FileCopyrightText: 2026 FastKiwi -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# ميزات 2.0 — التحديث من GitHub داخل التطبيق / 2.0 features — In-app update from GitHub

الفرع `feat/2.0-update` (بأمر المالك المباشر 2026-11-15، يلغي جزء «لا شبكة» من D-077؛ القرار D30 في `DECISIONS.md`).
كل الاختبارات على الحاسوب (JVM + Robolectric) مع خادم وهمي محلي على 127.0.0.1؛ لا اتصال حقيقي بـ GitHub في أي اختبار
(تحت Robolectric يرى محدِّث التطبيق نفسه «لا اتصال» دائماً). لم يُجرَّب على هاتف ولا على إصدار حقيقي منشور.

## ملخص بالعربية

- يتحقق التطبيق من آخر إصدار على `api.github.com/repos/sarmadiq9090-coder/scan-kiwi/releases/latest` مرة كل ٧ أيام على الأكثر
  عند فتحه فقط (بلا خدمة خلفية)، وبزر «تحقق من التحديثات» في «التحديثات» و«حول» في أي وقت.
- لا يُثبَّت شيء إلا إذا كان الإصدار أحدث (SemVer)، وطابقت بصمة SHA-256 ملف SHA256SUMS في الإصدار نفسه، ووُقّع الملف
  بشهادة التطبيق المثبّت (PackageManager على مسار الملف)؛ وإلا يُحذف الملف وتظهر رسالة. التثبيت عبر PackageInstaller بموافقة
  المستخدم في شاشة أندرويد.
- إعدادان مفعّلان افتراضياً: «التحقق التلقائي من التحديثات» و«التنزيل عبر Wi-Fi فقط». إشعار لمرة واحدة بنص المالك قبل أول فحص.

## جدول الميزات

| الميزة | Feature | المعرّف | الحالة | معيار القبول | النتيجة المقيسة | الاختبار |
|---|---|---|---|---|---|---|
| مصدر واحد: GitHub عبر HTTPS | Single source, HTTPS, GitHub hosts only | D30 | منجز | لا اتصال بغير GitHub؛ لا HTTP | HTTPS فقط إلى 4 نطاقات (api.github.com، github.com، objects/release-assets.githubusercontent.com)؛ كل إعادة توجيه تُفحص قبل اتباعها؛ `network_security_config` بشهادات النظام فقط وبلا نص صريح؛ رفض http و`example.com` و`api.github.com.evil.test` وعنوان بكلمة مرور و`file:` | `UpdaterTest.onlyAllowedHostsOverHttpsAndAGenericUserAgent` |
| لا معرّف للجهاز | No device identifier, generic User-Agent | D30 | منجز | User-Agent عام | كل الطلبات `User-Agent: ScanKiwi/2.0.0` فقط؛ بلا ملفات تعريف ارتباط ولا معرّف | `UpdaterTest.onlyAllowedHostsOverHttpsAndAGenericUserAgent` |
| مقارنة الإصدارات | SemVer comparison | D30 | منجز | SemVer 2.0.0 §11 | أرقام، ما قبل الإصدار، بيانات البناء مُهملة، وسوم غريبة مرفوضة؛ إصدار غير أحدث لا يُعرض ولا يُنزَّل | `UpdaterTest.semverPrecedence`, `UpdaterTest.onlyANewerReleaseIsOffered` |
| تطابق SHA-256 | sha256 equals the release's SHA256SUMS | D30 | منجز | فشل ← حذف ورسالة ولا تثبيت | بصمة خاطئة: الملف محذوف، فحص الشهادة لم يُستدعَ؛ لا سطر للملف في SHA256SUMS: لا تنزيل أصلاً | `UpdaterTest.shaMismatchDeletesTheFileAndInstallsNothing` |
| تطابق شهادة التوقيع | Signing certificate equals the installed app's | D30 | منجز (على JVM بمحقّق وهمي) | فشل ← حذف ورسالة ولا تثبيت | شهادة مختلفة أو ملف تالف: محذوف؛ المحقّق الحقيقي `AndroidApkVerifier` (اسم الحزمة + SHA-256 لكل شهادة، `GET_SIGNING_CERTIFICATES` على 28+ و`GET_SIGNATURES` قبلها) لم يُجرَّب على هاتف | `UpdaterTest.certificateMismatchDeletesTheFileAndInstallsNothing` |
| غياب الشبكة | No network | D30 | منجز | رسالة واضحة، لا طلب | بلا اتصال: 0 طلبات و«لا يوجد اتصال بالإنترنت.»؛ خادم غير متاح: خطأ شبكة ولا ملفات متبقية | `UpdaterTest.noNetworkMeansNoRequestAndAClearError`, `UpdateUiTest` |
| مرة كل ٧ أيام | Automatic check at most once every 7 days, at app open | D30 (تعديل المالك) | منجز | لا فحص قبل مرور ٧ أيام؛ الزر اليدوي في أي وقت | فتح في الأيام 1 و3 و6: لا فحص جديد؛ اليوم 7: فحص؛ بلا اتصال: لا يُسجَّل فيُعاد عند الفتح التالي؛ موقوف: لا فحص؛ ساعة أُرجعت: لا فحص؛ الزر اليدوي مرتين متتاليتين: فحصان | `UpdaterTest.automaticCheckAtMostOnceEverySevenDays` |
| التنزيل عبر Wi-Fi فقط | Download over Wi-Fi only (default on) | D30 | منجز | لا تنزيل عبر بيانات الهاتف | بيانات الهاتف + الخيار مفعّل: 0 طلبات تنزيل ورسالة؛ الفحص نفسه مسموح | `UpdaterTest.wifiOnlyRefusesMobileDataButStillChecks` |
| الإشعار لمرة واحدة | One-time notice (owner's wording) | D30 | منجز | يظهر مرة، قبل أي فحص | النص حرفياً؛ لا فحص قبل إغلاقه؛ لا يظهر مرة ثانية؛ «الإعدادات» يفتح «التحديثات» | `UpdaterTest.noticeComesBeforeAnyAutomaticCheck`, `UpdateUiTest.noticeOnceThenUpdatesScreenWithItsSettings` |
| شاشة «التحديثات» و«حول» | Updates screen + About button | D30 | منجز | زر وإعدادان ≥ 48 dp | «تحقق من التحديثات»، «تنزيل وتثبيت»، المفتاحان، النص؛ زر في «حول» | `UpdateUiTest`, `UpdatesSettingsTest` |
| التثبيت | PackageInstaller with Android's confirmation | D30 | منجز (لم يُجرَّب على هاتف) | موافقة المستخدم المعتادة | جلسة PackageInstaller، `USER_ACTION_REQUIRED` على 31+، `UpdateInstallReceiver` يفتح شاشة التأكيد | فحص المالك على الهاتف |
| الأذونات وCI | Permissions + CI | D30 | منجز | CAMERA وINTERNET وACCESS_NETWORK_STATE وREQUEST_INSTALL_PACKAGES فقط | ACCESS_NETWORK_STATE لازم لمعرفة Wi-Fi من بيانات الهاتف (**قبله القائد 2026-11-15**)؛ CI يسمح بهذه الأربعة فقط ويفشل إن ظهر كود شبكة خارج `update/UpdateClient.kt` | CI `Check permissions (exact list)` |
| الحجم | Size | D30 | منجز | بلا مكتبات جديدة | `HttpURLConnection` ومكتبة جافا القياسية فقط؛ لا تبعية Maven جديدة | — |

---

# English

Branch `feat/2.0-update`. Owner's order 2026-11-15 («دع فاست كيوي سكان يحصل على تحديث من كيت هاب…»), amended the same day to
«التحقق من التحديث مره في الاسبوع». Code: `app/src/main/java/com/fastkiwi/scan/update/` — `SemVer`, `UpdatePolicy` (7-day
rule, Wi-Fi rule), `UpdateClient` (the only network code), `Updater` (check → SHA256SUMS → download with streaming sha256 →
certificate check; any failure deletes the file), `AndroidUpdate.kt` (`AndroidApkVerifier`, `currentNetwork`,
`UpdateInstaller`, `UpdateInstallReceiver`), `UpdateController` (app state, no background service). UI: `ui/UpdatesSection.kt`
(check button, two switches, other ways, statement), About dialog button, `UpdateNoticeDialog` in `MainActivity`.

Release asset contract (for the release phase): the GitHub release must carry `ScanKiwi-<version>.apk` and
`SHA256SUMS.txt` with a `sha256sum` line for that APK; the tag is SemVer (`v2.0.1`); drafts and pre-releases are ignored.

Not verified here: a real GitHub release (none exists for 2.0 yet), the PackageManager certificate read on a phone,
PackageInstaller's confirmation on Android 7–16, Wi-Fi detection on a phone. These are owner phone checks.

Leader decisions (2026-11-15): ACCESS_NETWORK_STATE is accepted (a normal, install-time permission that reads only
the connection type; «التنزيل عبر Wi-Fi فقط» cannot work without it), so the permissions are CAMERA, INTERNET,
ACCESS_NETWORK_STATE and REQUEST_INSTALL_PACKAGES. The repository is `sarmadiq9090-coder/scan-kiwi` everywhere. The notice,
About and the privacy text use the app's own Arabic name «ماسح كيوي».
